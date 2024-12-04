package com.swisscom.health.des.cdr.client.handler

import com.mayakapps.kache.ObjectKache
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IAuthenticationResult
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.microsoft.aad.msal4j.TokenSource
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.OneTimeTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.annotation.DirtiesContext.ClassMode
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Files
import java.nio.file.Path
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
        "spring.jmx.enabled=false",
    ]
)
// only test filesystem event handling, no polling
@ActiveProfiles("test", "noPollingUploadScheduler", "noDownloadScheduler")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
internal class EventPushFileHandlingTest {

    @SpykBean
    private lateinit var config: CdrClientConfig

    @SpykBean
    private lateinit var securedApp: IConfidentialClientApplication

    @Autowired
    private lateinit var fileCache: ObjectKache<String, Path>

    @Autowired
    private lateinit var scheduledTaskHolder: ScheduledTaskHolder

    @Autowired
    private lateinit var threadPoolTaskScheduler: ThreadPoolTaskScheduler

    private val targetDirectory = "customer"
    private val sourceDirectory = "source"
    private val forumDatenaustauschMediaType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")

    private lateinit var cdrServiceMock: MockWebServer

    private val isFirstTest: AtomicBoolean = AtomicBoolean(true)

    @BeforeEach
    fun setup() {
        cdrServiceMock = MockWebServer()
        cdrServiceMock.start()

        val sourceFolder = tmpDir.resolve(sourceDirectory).also { it.createDirectories() }
        val targetFolder = tmpDir.resolve(targetDirectory).also { it.createDirectories() }

        every { config.endpoint } returns CdrClientConfig.Endpoint(
            host = cdrServiceMock.hostName,
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )
        every { config.customer } returns listOf(
            CdrClientConfig.Connector(
                connectorId = "2345",
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
                contentType = forumDatenaustauschMediaType,
                mode = CdrClientConfig.Mode.TEST
            )
        )

        val resultMock: CompletableFuture<IAuthenticationResult> = mockk()
        val authMock: IAuthenticationResult = mockk()
        every { resultMock.get() } returns authMock
        every { authMock.metadata().tokenSource() } returns TokenSource.CACHE
        every { authMock.accessToken() } returns "123"
        every { securedApp.acquireToken(any<ClientCredentialParameters>()) } returns resultMock

        // The test is in a race condition with the code under test. The code under test starts a file watcher task with the help of the
        // `@Scheduled` annotation. I have found no deterministic way to either delay the scheduled task until we set the behavior on the
        // spy of the CdrClientConfig bean, to not run it at all, or to cancel it if it is already running.
        // So we hope for the best (original task has started before we set the spy behavior) and then re-submit the task after setting the
        // spy's behavior. That we have two instances of the watcher task running, but only one of them is watching the directory we use
        // in this test.
        if (isFirstTest.compareAndSet(true, false)) {
            val oneTimeTasks = scheduledTaskHolder.scheduledTasks.filter { it.task is OneTimeTask }
            // as we disabled all other schedulers via profiles, we expect exactly the one task we are testing in the list
            assertEquals(1, oneTimeTasks.size)
            // re-submit the file watcher task; effectively there will be two instances of the task running:
            // the original one and the copy we just submitted, but only the second one will receive the events from the source directory created by the test
            oneTimeTasks.forEach { ott ->
                val future = threadPoolTaskScheduler.submit(ott.task.runnable)
                await().until { future.isDone || future.isCancelled }
                assertTrue(future.isDone)
            }
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
        val mockResponse = MockResponse().setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .setResponseCode(HttpStatus.OK.value())
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
        val mockResponse = MockResponse().setResponseCode(HttpStatus.OK.value())
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

        assertEquals(3, sourceDir.listDirectoryEntries().size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))
        Files.move(payload3, payload3.resolveSibling(payload3.nameWithoutExtension))

        await().during(1, TimeUnit.SECONDS).until(cdrServiceMock::requestCount) { it == 6 }
        await().during(100L, TimeUnit.MILLISECONDS).until { sourceDir.listDirectoryEntries("*response").size == 1 }

        assertEquals(1, sourceDir.listDirectoryEntries("*error").size)
        assertEquals(1, sourceDir.listDirectoryEntries("*response").size)

        // but no additional requests for the error and response file should have been made, i.e. the request count
        // should still be 6 (2 successful and four failed requests)
        await().during(100, TimeUnit.MILLISECONDS).until(cdrServiceMock::requestCount) { it == 6 }

        // ignored files like the error and response files don't get processed and thus are not supposed to be in the processing cache
        await().during(100L, TimeUnit.MILLISECONDS).until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }

        // error and response files remain in source directory
        await().during(100L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { it.size == 2 }
    }

    @Test
    fun `test successfully write two files to API fail with third do not retry`() {
        val mockResponse = MockResponse().setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .setBody("{\"message\": \"Upload successful\"}")
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.BAD_REQUEST.value()).setBody("{\"message\": \"Exception\"}"))

        val sourceDir = tmpDir.resolve(sourceDirectory)

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
        await().during(100L, TimeUnit.MILLISECONDS).until { sourceDir.listDirectoryEntries("*response").size == 1 }

        assertEquals(1, sourceDir.listDirectoryEntries("*error").size)
        assertEquals(1, sourceDir.listDirectoryEntries("*response").size)

        // but no additional requests for the error and response file should have been made, i.e. the request count
        // should still be 6 (2 successful and four failed requests)
        await().during(100, TimeUnit.MILLISECONDS).until(cdrServiceMock::requestCount) { it == 3 }

        // ignored files like the error and response files don't get processed and thus are not supposed to be in the processing cache
        await().during(100L, TimeUnit.MILLISECONDS).until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }

        // error and response files remain in source directory
        await().during(100L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { it.size == 2 }
    }

    private companion object {

        @TempDir(factory = AlwaysSameTempDirFactory::class)
        @JvmStatic
        @Suppress("unused")
        private lateinit var inflightDirInApplicationTestYaml: Path

        @TempDir
        @JvmStatic
        private lateinit var tmpDir: Path

    }

}
