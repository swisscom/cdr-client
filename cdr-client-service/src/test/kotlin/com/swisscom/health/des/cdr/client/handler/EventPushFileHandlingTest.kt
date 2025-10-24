package com.swisscom.health.des.cdr.client.handler

import com.mayakapps.kache.ObjectKache
import com.ninjasquad.springmockk.SpykBean
import com.swisscom.health.des.cdr.client.AlwaysSameTempDirFactory
import com.swisscom.health.des.cdr.client.config.CdrApi
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.ClientId
import com.swisscom.health.des.cdr.client.config.ClientSecret
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.ConnectorId
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.config.Host
import com.swisscom.health.des.cdr.client.config.IdpCredentials
import com.swisscom.health.des.cdr.client.config.LastCredentialRenewalTime
import com.swisscom.health.des.cdr.client.config.RenewCredential
import com.swisscom.health.des.cdr.client.config.Scope
import com.swisscom.health.des.cdr.client.config.TenantId
import io.mockk.every
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import okhttp3.Headers
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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
        "client.idp-credentials.renew-credential=false", //DO NOT REMOVE! Or your spy(k)ed config won't take effect; see code about race condition below
    ]
)
// only test filesystem event handling, no polling
@ActiveProfiles("test", "noPollingUploadScheduler", "noDownloadScheduler")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
internal class EventPushFileHandlingTest {

    @SpykBean
    private lateinit var config: CdrClientConfig

    @SpykBean
    private lateinit var schedulingValidationService: SchedulingValidationService

    @Autowired
    private lateinit var fileCache: ObjectKache<String, Path>

    @Autowired
    private lateinit var scheduledTaskHolder: ScheduledTaskHolder

    private val targetDirectory = "customer"
    private val sourceDirectory = "source"
    private val forumDatenaustauschMediaType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")

    @StartStop
    private val cdrServiceMock = MockWebServer()

    @StartStop
    private val idpMock = MockWebServer()

    @BeforeEach
    fun setup() {
        val sourceDir0 = tmpDir.resolve(sourceDirectory).also { it.createDirectories() }
        val targetDir0 = tmpDir.resolve(targetDirectory).also { it.createDirectories() }

        every { config.cdrApi } returns CdrApi(
            host = Host(cdrServiceMock.hostName),
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )
        every { config.idpCredentials } returns IdpCredentials(
            tenantId = TenantId("fake-tenant-id"),
            clientId = ClientId("test-client-id"),
            clientSecret = ClientSecret("test-client-secret"),
            scope = Scope("https://dev.identity.health.swisscom.ch/CdrApi/.default"),
            renewCredential = RenewCredential(false),
            maxCredentialAge = Duration.ofDays(365),
            lastCredentialRenewalTime = LastCredentialRenewalTime(Instant.now()),
        )
        every { config.idpEndpoint } returns URI("http://${idpMock.hostName}:${idpMock.port}/oauth2/v2.0/token").toURL()
        every { config.customer } returns Customer(
            mutableListOf(
                Connector(
                    connectorId = ConnectorId("2345"),
                    targetFolder = targetDir0,
                    sourceFolder = sourceDir0,
                    contentType = forumDatenaustauschMediaType.toString(),
                    mode = CdrClientConfig.Mode.TEST,
                )
            )
        )
        every { schedulingValidationService.isSchedulingAllowed } returns true

        idpMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .code(HttpStatus.OK.value())
                    .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
                    .body(
                        """
                        {
                          "token_type" : "Bearer",
                          "access_token" : "eyJraWQiOiJ0ZXN0LXRlbmFudC1pZC9vYXV0aDIvdjIuMCIsInR5cCI6IkpXVCIsImFsZyI6IkVTMjU2In0.eyJzdWIiOiJ0ZXN0LWNsaWVudC1pZCIsIm5iZiI6MTc2MDU0NjA0Nywicm9sZXMiOlsiQ2RyQXBwbGljYXRpb25NYW5hZ2VyLlJlYWRXcml0ZS5BbGwiXSwiaXNzIjoiaHR0cDovL2xvY2FsaG9zdC90ZXN0LXRlbmFudC1pZC9vYXV0aDIvdjIuMCIsImV4cCI6MTc2MDU0NjQwNywiaWF0IjoxNzYwNTQ2MDQ3LCJqdGkiOiI4MDEzNzc2YS1jMWMyLTRhZGYtODE3Yi1hOWZiMDIxMTc3NmQifQ.RiL2mqiU7e40hmXzPzMIJQJUTa1gjgusFbav1TcXLjnuehaC944AVCWIvg1QQW4dTm89d_YoRTqo6SlIR8qySQ",
                          "expires_in" : 359,
                          "scope" : "https://dev.identity.health.swisscom.ch/CdrApi/.default"
                        }
                        """
                    )
                    .build()
        }

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
        runBlocking {
            fileCache.clear()
        }

        // do not delete the source directory itself; the file watcher task does not survive if its watched directory gets deleted (and re-created)
        tmpDir.resolve(sourceDirectory).listDirectoryEntries().forEach {
            it.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `test successfully write two files to API`() {
        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
            .body("{\"message\": \"Upload successful\"}")
            .build()
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
        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
            .body("{\"message\": \"Upload successful\"}")
            .build()
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.INTERNAL_SERVER_ERROR.value()).body("{\"message\": \"Exception\"}").build())
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.INTERNAL_SERVER_ERROR.value()).body("{\"message\": \"Exception\"}").build())
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.INTERNAL_SERVER_ERROR.value()).body("{\"message\": \"Exception\"}").build())
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.BAD_REQUEST.value()).body("{\"message\": \"Exception\"}").build())

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
        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
            .body("{\"message\": \"Upload successful\"}")
            .build()
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.BAD_REQUEST.value()).body("{\"message\": \"Exception\"}").build())

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
