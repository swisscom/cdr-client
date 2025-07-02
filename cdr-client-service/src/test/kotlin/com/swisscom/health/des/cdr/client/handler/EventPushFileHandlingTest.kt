package com.swisscom.health.des.cdr.client.handler

import com.mayakapps.kache.ObjectKache
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IAuthenticationResult
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.microsoft.aad.msal4j.TokenSource
import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.SpykBean
import com.swisscom.health.des.cdr.client.AlwaysSameTempDirFactory
import com.swisscom.health.des.cdr.client.config.CdrApi
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.config.Host
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.scheduling.config.TaskExecutionOutcome.Status.NONE
import org.springframework.scheduling.config.TaskExecutionOutcome.Status.STARTED
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.annotation.DirtiesContext.ClassMode
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream
import kotlin.io.path.walk

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.main.lazy-initialization=true",
        "spring.jmx.enabled=false",
        "client.idp-credentials.renew-credential=false",
    ]
)
// only test filesystem event handling, no polling
@ActiveProfiles("test", "noPollingUploadScheduler", "noDownloadScheduler")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
internal class EventPushFileHandlingTest {

    @SpykBean
    private lateinit var config: CdrClientConfig

    @MockkBean
    private lateinit var securedApp: IConfidentialClientApplication

    @Autowired
    private lateinit var fileCache: ObjectKache<String, Path>

    @Autowired
    private lateinit var scheduledTaskHolder: ScheduledTaskHolder

    private val targetDirectory = "customer"
    private val sourceDirectory = "source"
    private val forumDatenaustauschMediaType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")

    private lateinit var cdrServiceMock: MockWebServer

    @BeforeEach
    fun setup() {
        cdrServiceMock = MockWebServer()
        cdrServiceMock.start()

        val sourceFolder0 = tmpDir.resolve(sourceDirectory).also { it.createDirectories() }
        val targetFolder0 = tmpDir.resolve(targetDirectory).also { it.createDirectories() }

        every { config.cdrApi } returns CdrApi(
            host = Host(cdrServiceMock.hostName),
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )
        every { config.customer } returns Customer(
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "2345",
                    targetFolder = targetFolder0,
                    sourceFolder = sourceFolder0,
                    contentType = forumDatenaustauschMediaType,
                    mode = CdrClientConfig.Mode.TEST,
                )
            )
        )

        val resultMock: CompletableFuture<IAuthenticationResult> = mockk()
        val authMock: IAuthenticationResult = mockk()
        every { resultMock.get() } returns authMock
        every { authMock.metadata().tokenSource() } returns TokenSource.CACHE
        every { authMock.accessToken() } returns "123"
        every { securedApp.acquireToken(any<ClientCredentialParameters>()) } returns resultMock

        // The test is racing the code under test. We need to make sure that the event watcher task
        // is not yet started to be sure that when it starts, it picks up the configuration we injected
        // above in the @SpykBean.
        if (isFirstTest.compareAndSet(true, false)) {
            val eventWatcher = scheduledTaskHolder.scheduledTasks.filter { it.task.toString().endsWith("launchFileWatcher") }
            assertEquals(1, eventWatcher.size)
            assertEquals(NONE, eventWatcher.first().task.lastExecutionOutcome.status) {
                "we cannot be sure whether we won or lost the race against the event watcher task; so let's bail out to err on the safe side"
            }
            await().until { eventWatcher.first().task.lastExecutionOutcome.status == STARTED }
            // give the event watcher task some time to start up
            Thread.sleep(1_000L)
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterEach
    fun tearDown() {
        cdrServiceMock.shutdown()

        runBlocking {
            fileCache.clear()
        }

        // do not delete the source folder itself; the file watcher task does not survive if its watched directory gets deleted (and re-created)
        tmpDir.resolve(sourceDirectory).listDirectoryEntries().forEach {
            it.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `test successfully write two files to API`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .setBody("{\"message\": \"Upload successful\"}")
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)

        val sourceDir = tmpDir.resolve(sourceDirectory)

        val payload1 = sourceDir.resolve("dummy.xml.tmp")
        payload1.outputStream().use { it.write("Hello".toByteArray()) }
        val payload2 = sourceDir.resolve("dummy-2.xml.tmp")
        payload2.outputStream().use { it.write("Hello 2".toByteArray()) }

        assertEquals(2, sourceDir.listDirectoryEntries().size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))

        await().during(1000L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { it.isEmpty() }

        await().during(100L, TimeUnit.MILLISECONDS).until({
            tmpDir.walk().filter { it.isRegularFile() }.toList()
        }) { it.isEmpty() } // make sure no error files have been written or .tmp files have been left

        assertEquals(2, cdrServiceMock.requestCount)

        // processed files should be removed from cache
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }

        // processed files should be removed from source directory
        await().during(100L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { it.isEmpty() }
    }

    @Test
    fun `test ignore non xml files`() {
        val sourceDir = tmpDir.resolve(sourceDirectory)

        val payload1 = sourceDir.resolve("dummy.txt")
        payload1.outputStream().use { it.write("Hello".toByteArray()) }
        val payload2 = sourceDir.resolve("dummy-2.error")
        payload2.outputStream().use { it.write("Hello 2".toByteArray()) }
        val payload3 = sourceDir.resolve("dummy-3.log")
        payload3.outputStream().use { it.write("Hello 3".toByteArray()) }

        assertEquals(3, sourceDir.listDirectoryEntries().size)

        // polling process must leave non-xml files where they are
        await().during(1000L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { it.size == 3 }

        assertEquals(0, cdrServiceMock.requestCount)

        // ignored files don't get processed and thus are not supposed to be in the processing cache
        await().during(100L, TimeUnit.MILLISECONDS).until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    @Test
    fun `test successfully write two files to API fail with third`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .setBody("{\"message\": \"Upload successful\"}")
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()).setBody("{\"message\": \"Exception\"}"))
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()).setBody("{\"message\": \"Exception\"}"))
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()).setBody("{\"message\": \"Exception\"}"))
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.BAD_REQUEST.value()).setBody("{\"message\": \"Exception\"}"))

        val sourceDir = tmpDir.resolve(sourceDirectory)
        val errorDir = sourceDir.resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))

        val payload1 = sourceDir.resolve("dummy.xml.tmp")
        payload1.outputStream().use { it.write("Hello".toByteArray()) }
        val payload2 = sourceDir.resolve("dummy-2.xml.tmp")
        payload2.outputStream().use { it.write("Hello 2".toByteArray()) }
        val payload3 = sourceDir.resolve("dummy-3.xml.tmp")
        payload3.outputStream().use { it.write("Hello 3".toByteArray()) }

        assertEquals(3, sourceDir.listDirectoryEntries().size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))
        Files.move(payload3, payload3.resolveSibling(payload3.nameWithoutExtension))

        await().during(1, TimeUnit.SECONDS).until(cdrServiceMock::requestCount) { it == 6 }
        await().during(100L, TimeUnit.MILLISECONDS).until { errorDir.listDirectoryEntries("*response").size == 1 }

        assertEquals(1, errorDir.listDirectoryEntries("*error").size)
        assertEquals(1, errorDir.listDirectoryEntries("*response").size)

        // but no additional requests for the error and response file should have been made, i.e. the request count
        // should still be 6 (2 successful and four failed requests)
        await().during(100, TimeUnit.MILLISECONDS).until(cdrServiceMock::requestCount) { it == 6 }

        // ignored files like the error and response files don't get processed and thus are not supposed to be in the processing cache
        await().during(100L, TimeUnit.MILLISECONDS).until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }

        // error and response files are written to a subdirectory of the source directory
        await().during(100L, TimeUnit.MILLISECONDS).until(errorDir::listDirectoryEntries) { it.size == 2 }
        await().during(100L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { it.none { it.isRegularFile() } }
    }

    @Test
    fun `test successfully write two files to API fail with third do not retry`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .setBody("{\"message\": \"Upload successful\"}")
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.BAD_REQUEST.value()).setBody("{\"message\": \"Exception\"}"))

        val sourceDir = tmpDir.resolve(sourceDirectory)
        val errorDir = sourceDir.resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))

        val payload1 = sourceDir.resolve("dummy.xml.tmp")
        payload1.outputStream().use { it.write("Hello".toByteArray()) }
        val payload2 = sourceDir.resolve("dummy-2.xml.tmp")
        payload2.outputStream().use { it.write("Hello 2".toByteArray()) }
        val payload3 = sourceDir.resolve("dummy-3.xml.tmp")
        payload3.outputStream().use { it.write("Hello 3".toByteArray()) }

        assertEquals(3, sourceDir.listDirectoryEntries().size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))
        Files.move(payload3, payload3.resolveSibling(payload3.nameWithoutExtension))

        await().during(1, TimeUnit.SECONDS).until(cdrServiceMock::requestCount) { it == 3 }
        await().during(100L, TimeUnit.MILLISECONDS).until { errorDir.listDirectoryEntries("*response").size == 1 }

        assertEquals(1, errorDir.listDirectoryEntries("*error").size)
        assertEquals(1, errorDir.listDirectoryEntries("*response").size)

        // but no additional requests for the error and response file should have been made, i.e. the request count
        // should still be 6 (2 successful and four failed requests)
        await().during(100, TimeUnit.MILLISECONDS).until(cdrServiceMock::requestCount) { it == 3 }

        // ignored files like the error and response files don't get processed and thus are not supposed to be in the processing cache
        await().during(100L, TimeUnit.MILLISECONDS).until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }

        // error and response files are written to a subdirectory of the source directory
        await().during(100L, TimeUnit.MILLISECONDS).until(errorDir::listDirectoryEntries) { it.size == 2 }
        await().during(100L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { it.none { it.isRegularFile() } }
    }

    private companion object {

        @TempDir(factory = AlwaysSameTempDirFactory::class)
        @JvmStatic
        @Suppress("unused")
        private lateinit var inflightDirInApplicationTestYaml: Path

        @TempDir
        @JvmStatic
        private lateinit var tmpDir: Path

        @JvmStatic
        private val isFirstTest: AtomicBoolean = AtomicBoolean(true)

    }

}
