package com.swisscom.health.des.cdr.client.handler

import com.mayakapps.kache.ObjectKache
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IAuthenticationResult
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.microsoft.aad.msal4j.TokenSource
import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.SpykBean
import com.swisscom.health.des.cdr.client.AlwaysSameTempDirFactory
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
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
import kotlin.io.path.extension
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
        "client.idp-credentials.renew-credential-at-startup=false",
    ]
)
// only test polling, not filesystem event handling
@ActiveProfiles("test", "noEventTriggerUploadScheduler", "noDownloadScheduler")
@DirtiesContext(classMode = ClassMode.BEFORE_CLASS)
internal class PollingPushFileHandlingTest {

    @SpykBean
    private lateinit var config: CdrClientConfig

    @MockkBean
    private lateinit var securedApp: IConfidentialClientApplication

    @Autowired
    private lateinit var fileCache: ObjectKache<String, Path>

    @Autowired
    private lateinit var scheduledTaskHolder: ScheduledTaskHolder

    @TempDir
    private lateinit var tmpDir: Path

    private val inflightFolder = "inflight"
    private val targetDirectory = "customer"
    private val sourceDirectory = "source"
    private val forumDatenaustauschMediaType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")

    private lateinit var cdrServiceMock: MockWebServer

    @BeforeEach
    fun setup() {
        cdrServiceMock = MockWebServer()
        cdrServiceMock.start()

        val inflightDir = tmpDir.resolve(inflightFolder).also { it.createDirectories() }
        val sourceFolder0 = tmpDir.resolve(sourceDirectory).also { it.createDirectories() }
        val targetFolder0 = tmpDir.resolve(targetDirectory).also { it.createDirectories() }

        every { config.localFolder } returns inflightDir
        every { config.cdrApi } returns CdrClientConfig.Endpoint().apply {
            host = cdrServiceMock.hostName
            basePath = "documents"
            scheme = "http"
            port = cdrServiceMock.port
        }
        every { config.customer } returns listOf(
            CdrClientConfig.Connector().apply {
                connectorId = "2345"
                targetFolder = targetFolder0
                sourceFolder = sourceFolder0
                contentType = forumDatenaustauschMediaType
                mode = CdrClientConfig.Mode.TEST
            }
        )

        val resultMock: CompletableFuture<IAuthenticationResult> = mockk()
        val authMock: IAuthenticationResult = mockk()
        every { resultMock.get() } returns authMock
        every { authMock.metadata().tokenSource() } returns TokenSource.CACHE
        every { authMock.accessToken() } returns "123"
        every { securedApp.acquireToken(any<ClientCredentialParameters>()) } returns resultMock

        if (isFirstTest.compareAndSet(true, false)) {
            val filePoller = scheduledTaskHolder.scheduledTasks.filter { it.task.toString().endsWith("launchFilePoller") }
            assertEquals(1, filePoller.size)
            assertEquals(NONE, filePoller.first().task.lastExecutionOutcome.status) {
                "we cannot be sure whether we won or lost the race against the event watcher task; so let's bail out to err on the safe side"
            }
            await().until { filePoller.first().task.lastExecutionOutcome.status == STARTED }
            // give the file polling task some time to start up
            Thread.sleep(1_000L)
        }
    }

    @AfterEach
    fun tearDown() {
        cdrServiceMock.shutdown()

        runBlocking {
            fileCache.clear()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `test successfully write two files to API - no archive`() {
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
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `test successfully write two files to API - with archive`() {
        val relativeArchiveFolder = Path.of("archive")
        val sourceFolder0 = tmpDir.resolve(sourceDirectory)
        val targetFolder0 = tmpDir.resolve(targetDirectory)
        every { config.customer } returns listOf(
            CdrClientConfig.Connector().apply {
                connectorId = "2345"
                targetFolder = targetFolder0
                sourceFolder = sourceFolder0
                contentType = forumDatenaustauschMediaType
                mode = CdrClientConfig.Mode.TEST
                sourceArchiveEnabled = true
                sourceArchiveFolder = relativeArchiveFolder
            }
        )
        val archiveFolder = config.customer.first().effectiveSourceArchiveFolder

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

        // 2 files and a subdirectory for the archive
        assertEquals(2, sourceDir.listDirectoryEntries().filter { it.isRegularFile() }.size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))

        await().during(1000L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { it.none { it.isRegularFile() } }
        await().during(100L, TimeUnit.MILLISECONDS)
            .until(archiveFolder::walk) { it.filter { it.isRegularFile() }.toList().run { size == 2 && all { it.extension == "xml" } } }

        // make sure no error files have been written or temporary files have been left
        await().during(100L, TimeUnit.MILLISECONDS).until({ tmpDir.walk().filter { it.isRegularFile() && it.parent != archiveFolder } }) { it.none() }

        assertEquals(2, cdrServiceMock.requestCount)

        // processed files should be removed from cache
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
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
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    @Test
    fun `test successfully write two files to API fail with third - no separate error directory`() {
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
        await().during(100L, TimeUnit.MILLISECONDS)
            .until { errorDir.listDirectoryEntries("*response").size == 1 }

        assertEquals(1, errorDir.listDirectoryEntries("*error").size)
        assertEquals(1, errorDir.listDirectoryEntries("*response").size)

        // but no additional requests for the error and response file should have been made, i.e. the request count
        // should still be 6 (2 successful and four failed requests)
        await().during(100, TimeUnit.MILLISECONDS).until(cdrServiceMock::requestCount) { it == 6 }

        // ignored files like the error and response file don't get processed and thus are not supposed to be in the processing cache
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    @Test
    fun `test successfully write two files to API fail with third - with separate error directory`() {
        val relativeErrorFolder = Path.of("error")
        val sourceFolder0 = tmpDir.resolve(sourceDirectory)
        val targetFolder0 = tmpDir.resolve(targetDirectory)
        every { config.customer } returns listOf(
            CdrClientConfig.Connector().apply {
                connectorId = "2345"
                targetFolder = targetFolder0
                sourceFolder = sourceFolder0
                contentType = forumDatenaustauschMediaType
                mode = CdrClientConfig.Mode.TEST
                sourceErrorFolder = relativeErrorFolder
            }
        )
        val errorFolder = config.customer.first().effectiveSourceErrorFolder

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

        val payload1 = sourceDir.resolve("dummy.xml.tmp")
        payload1.outputStream().use { it.write("Hello".toByteArray()) }
        val payload2 = sourceDir.resolve("dummy-2.xml.tmp")
        payload2.outputStream().use { it.write("Hello 2".toByteArray()) }
        val payload3 = sourceDir.resolve("dummy-3.xml.tmp")
        payload3.outputStream().use { it.write("Hello 3".toByteArray()) }

        assertEquals(3, sourceDir.listDirectoryEntries().filter { it.isRegularFile() }.size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))
        Files.move(payload3, payload3.resolveSibling(payload3.nameWithoutExtension))

        await().during(1, TimeUnit.SECONDS).until(cdrServiceMock::requestCount) { it == 6 }
        await().during(100L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { it.none { it.isRegularFile() } }
        await().during(100L, TimeUnit.MILLISECONDS).until { errorFolder.listDirectoryEntries("*response").size == 1 }

        assertEquals(1, errorFolder.listDirectoryEntries("*error").size)
        assertEquals(1, errorFolder.listDirectoryEntries("*response").size)

        // but no additional requests for the error and response file should have been made, i.e. the request count
        // should still be 6 (2 successful and four failed requests)
        await().during(100, TimeUnit.MILLISECONDS).until(cdrServiceMock::requestCount) { it == 6 }

        // ignored files like the error and response file don't get processed and thus are not supposed to be in the processing cache
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    @Test
    fun `test successfully write two files to API fail with third do not retry - no separate error directory`() {
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

        // ignored files like the error and response file don't get processed and thus are not supposed to be in the processing cache
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    private companion object {

        @TempDir(factory = AlwaysSameTempDirFactory::class)
        @JvmStatic
        @Suppress("unused")
        private lateinit var inflightDirInApplicationTestYaml: Path

        @JvmStatic
        private val isFirstTest: AtomicBoolean = AtomicBoolean(true)

    }

}
