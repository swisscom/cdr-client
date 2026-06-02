package com.swisscom.health.des.cdr.client.handler

import com.mayakapps.kache.ObjectKache
import com.ninjasquad.springmockk.SpykBean
import com.swisscom.health.des.cdr.client.AlwaysSameTempDirFactory
import com.swisscom.health.des.cdr.client.common.Constants.ARCHIVE_DIR_NAME
import com.swisscom.health.des.cdr.client.common.Constants.ERROR_DIR_NAME
import com.swisscom.health.des.cdr.client.common.Constants.RESTART_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.common.DomainObjects
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
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
import com.swisscom.health.des.cdr.client.xml.DocumentType
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
import org.junit.jupiter.api.Assertions.assertTrue
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
        "client.idp-credentials.renew-credential=false", //DO NOT REMOVE! Or your spy(k)ed config won't take effect; see code about race condition below
    ]
)
// only test polling, not filesystem event handling
@ActiveProfiles("test", "noEventTriggerUploadScheduler", "noDownloadScheduler", "noFileMonitoringScheduler")
@DirtiesContext(classMode = ClassMode.BEFORE_CLASS)
internal class PollingPushFileHandlingTest {

    @SpykBean
    private lateinit var config: CdrClientConfig

    @Autowired
    private lateinit var fileCache: ObjectKache<String, Path>

    @Autowired
    private lateinit var scheduledTaskHolder: ScheduledTaskHolder

    @TempDir
    private lateinit var tmpDir: Path

    private val inflightDir = "inflight"
    private val targetDirectory = "customer"
    private val sourceDirectory = "source"
    private val forumDatenaustauschMediaType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")

    @StartStop
    private val cdrServiceMock = MockWebServer()

    @StartStop
    private val idpMock = MockWebServer()

    @BeforeEach
    fun setup() {
        val inflightDir = tmpDir.resolve(inflightDir).also { it.createDirectories() }
        val sourceDir0 = tmpDir.resolve(sourceDirectory)
            .also { it.resolve(ERROR_DIR_NAME).createDirectories() }
            .also { it.resolve(ARCHIVE_DIR_NAME).resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)).createDirectories() }
        val targetDir0 = tmpDir.resolve(targetDirectory).also { it.createDirectories() }

        every { config.localFolder } returns TempDownloadDir(inflightDir)
        // 1st call is made by validator, which expects the port to be set to 87; subsequent calls need to know the actual port
        every { config.cdrApi } returns CdrApi(
            host = Host("localhost"),
            basePath = "documents",
            scheme = "http",
            port = 87,
        ) andThen CdrApi(
            host = Host(cdrServiceMock.hostName),
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )
        every { config.idpCredentials } returns IdpCredentials(
            tenantId = TenantId(DomainObjects.TenantId.LOCALHOST.tenantId),
            clientId = ClientId("test-client-id"),
            clientSecret = ClientSecret("test-client-secret"),
            scope = Scope(DomainObjects.OAuthScope.LOCALHOST.scope),
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

        if (isFirstTest.compareAndSet(true, false)) {
            val filePoller = scheduledTaskHolder.scheduledTasks.filter { it.task.toString().endsWith("launchFilePoller") }
            assertEquals(1, filePoller.size)
            assertEquals(NONE, filePoller.first().task.lastExecutionOutcome.status) {
                "we cannot be sure whether we won or lost the race against the file poller task; so let's bail out to err on the safe side"
            }
            await().until { filePoller.first().task.lastExecutionOutcome.status == STARTED }
            // give the file polling task some time to start up
            Thread.sleep(1_000L)
        }
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            fileCache.clear()
        }
    }

    @Test
    fun `test successfully write two files to API - no archive`() {
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

        assertEquals(2, sourceDir.listDirectoryEntries().filter { it.isRegularFile() }.size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))

        await().during(1000L, TimeUnit.MILLISECONDS).until(sourceDir::listDirectoryEntries) { paths -> paths.none { path -> path.isRegularFile() } }

        await().during(100L, TimeUnit.MILLISECONDS).until({
            tmpDir.walk().filter { it.isRegularFile() }.toList()
        }) { it.isEmpty() } // make sure no error files have been written or .tmp files have been left

        assertEquals(2, cdrServiceMock.requestCount)

        // processed files should be removed from cache
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    @Test
    fun `test successfully write two files to API - with archive`() {
        val sourceDir0 = tmpDir.resolve(sourceDirectory)
        val targetDir0 = tmpDir.resolve(targetDirectory)
        val relativeArchiveDir = Path.of(ARCHIVE_DIR_NAME)
        every { config.customer } returns Customer(
            mutableListOf(
                Connector(
                    connectorId = ConnectorId("2345"),
                    targetFolder = targetDir0,
                    sourceFolder = sourceDir0,
                    contentType = forumDatenaustauschMediaType.toString(),
                    mode = CdrClientConfig.Mode.TEST,
                    sourceArchiveEnabled = true,
                    sourceArchiveFolder = relativeArchiveDir,
                )
            )
        )

        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
            .body("{\"message\": \"Upload successful\"}")
            .build()
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)

        val payload1 = sourceDir0.resolve("dummy.xml.tmp")
        payload1.outputStream().use { it.write("Hello".toByteArray()) }
        val payload2 = sourceDir0.resolve("dummy-2.xml.tmp")
        payload2.outputStream().use { it.write("Hello 2".toByteArray()) }

        // 2 files and a subdirectory for the archive
        assertEquals(2, sourceDir0.listDirectoryEntries().filter { it.isRegularFile() }.size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))

        val archiveDir = config.customer.first().getEffectiveSourceArchiveFolder(DocumentType.UNDEFINED)!!

        await().during(1000L, TimeUnit.MILLISECONDS)
            .until({ sourceDir0.listDirectoryEntries().also { println("XXXXX : $it") } }) { paths -> paths.none { path -> path.isRegularFile() } }
        await().during(100L, TimeUnit.MILLISECONDS)
            .until({
                archiveDir.walk().filter { path -> path.isRegularFile() }.toList()
            }) { paths -> paths.size == 2 && paths.all { path -> path.extension == "xml" } }

        // make sure no error files have been written or temporary files have been left
        await().during(100L, TimeUnit.MILLISECONDS).until(
            { tmpDir.walk().filter { it.isRegularFile() && it.parent != archiveDir } })
        { it.none() }

        assertEquals(2, cdrServiceMock.requestCount)

        // processed files should be removed from cache
        await().until(
            { runBlocking { fileCache.getKeys() } })
        { it.isEmpty() }
    }

    @Test
    @Suppress("LongMethod")
    fun `test successfully write two files to API - with archive for specific type`() {
        val sourceDir0 = tmpDir.resolve(sourceDirectory)
        val targetDir0 = tmpDir.resolve(targetDirectory)
        val archiveDir0 = sourceDir0.resolve(ARCHIVE_DIR_NAME)
            .also { it.resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)).createDirectories() }
        val relativeInvoiceSourceDir = Path.of("invoice")
        val relativeInvoiceArchiveDir = Path.of(ARCHIVE_DIR_NAME)

        every { config.customer } returns Customer(
            mutableListOf(
                Connector(
                    connectorId = ConnectorId("2345"),
                    targetFolder = targetDir0,
                    sourceFolder = sourceDir0,
                    contentType = forumDatenaustauschMediaType.toString(),
                    mode = CdrClientConfig.Mode.TEST,
                    sourceArchiveEnabled = true,
                    sourceArchiveFolder = archiveDir0,
                    docTypeFolders = mapOf(
                        DocumentType.INVOICE to Connector.DocTypeFolders(
                            sourceFolder = relativeInvoiceSourceDir,
                            archiveFolder = relativeInvoiceArchiveDir
                        )
                    ),
                )
            )
        )

        config.customer.first().getEffectiveSourceArchiveFolder(DocumentType.INVOICE)!!
            .resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)).createDirectories()
        config.customer.first().getEffectiveSourceArchiveFolder(DocumentType.NOTIFICATION)!!
            .resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)).createDirectories()

        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
            .body("{\"message\": \"Upload successful\"}")
            .build()
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)

        val invoiceSourceDir = config.customer.first().getEffectiveSourceFolder(DocumentType.INVOICE)
        assertTrue(invoiceSourceDir.contains(Path.of("invoice")))
        assertTrue(invoiceSourceDir.startsWith(sourceDir0))

        val notificationSourceDir = config.customer.first().getEffectiveSourceFolder(DocumentType.NOTIFICATION)
        assertEquals(sourceDir0, notificationSourceDir)

        val payload1 = invoiceSourceDir.resolve("dummy.xml.tmp")
        payload1.outputStream().use {
            it.write(
                """
                <invoice:request xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:invoice="http://www.forum-datenaustausch.ch/invoice" 
                xsi:schemaLocation="http://www.forum-datenaustausch.ch/invoice generalInvoiceRequest_450.xsd" />
                """.trimIndent().toByteArray()
            )
        }
        val payload2 = invoiceSourceDir.resolve("dummy-2.xml.tmp")
        payload2.outputStream().use {
            it.write(
                """
                <invoice:request xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:invoice="http://www.forum-datenaustausch.ch/invoice" 
                xsi:schemaLocation="http://www.forum-datenaustausch.ch/invoice generalInvoiceRequest_450.xsd" />
                """.trimIndent().toByteArray()
            )
        }
        val payload3 = notificationSourceDir.resolve("dummy-3.xml.tmp")
        payload3.outputStream().use {
            it.write(
                """
                <notification:notification xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
                xmlns:notification="http://www.forum-datenaustausch.ch/notification" 
                xsi:schemaLocation="http://www.forum-datenaustausch.ch/notification generalNotification_450.xsd" />
                    """.trimIndent().toByteArray()
            )
        }

        assertEquals(2, invoiceSourceDir.listDirectoryEntries().filter { it.isRegularFile() }.size)
        assertEquals(1, notificationSourceDir.listDirectoryEntries().filter { it.isRegularFile() }.size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))
        Files.move(payload3, payload3.resolveSibling(payload3.nameWithoutExtension))

        val invoiceArchiveDir = config.customer.first().getEffectiveSourceArchiveFolder(DocumentType.INVOICE)!!
        val notificationArchiveDir = config.customer.first().getEffectiveSourceArchiveFolder(DocumentType.NOTIFICATION)!!

        assertEquals(invoiceSourceDir, invoiceArchiveDir.parent)

        await().during(1000L, TimeUnit.MILLISECONDS)
            .until(invoiceSourceDir::listDirectoryEntries) { paths -> paths.none { path -> path.isRegularFile() } }
        // the two invoices should be archived under the document type specific archive directory
        await().during(100L, TimeUnit.MILLISECONDS)
            .until(invoiceArchiveDir::walk) { paths -> paths.toList().run { size == 2 && all { path -> path.extension == "xml" } } }
        // the notification should be archived under the global archive directory as no document type specific archive directory has been defined
        await().during(100L, TimeUnit.MILLISECONDS)
            .until(notificationArchiveDir::walk) { paths -> paths.toList().run { size == 1 && all { path -> path.extension == "xml" } } }

        // make sure no error files have been written or temporary files have been left
        await().during(100L, TimeUnit.MILLISECONDS)
            .until(tmpDir::walk) { paths ->
                paths.filter { !it.parent.startsWith(invoiceArchiveDir) }.filter { !it.parent.startsWith(notificationArchiveDir) }
                    .none { path -> path.isRegularFile() }
            }

        assertEquals(3, cdrServiceMock.requestCount)

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

        assertEquals(3, sourceDir.listDirectoryEntries().filter { it.isRegularFile() }.size)

        // the polling process must leave non-xml files where they are
        await().during(1000L, TimeUnit.MILLISECONDS).until { sourceDir.listDirectoryEntries().filter { it.isRegularFile() }.size == 3 }

        assertEquals(0, cdrServiceMock.requestCount)

        // ignored files don't get processed and thus are not supposed to be in the processing cache
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    @Test
    fun `test successfully write two files to API fail with third BAD_REQUEST - move file to source-error directory`() {
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
        val errorDir = sourceDir.resolve(ERROR_DIR_NAME)

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

        // above we give the client service 1 second to move the upload file to the error directory and to create the `.response`
        // file alongside it by means of checking that during 1 second the request count stays stable at 6 requests; nonetheless,
        // there is a race condition in the assertions for files below (a race that, at the time of writing, we always win).
        assertEquals(0, sourceDir.listDirectoryEntries("*xml").size)
        assertEquals(1, errorDir.listDirectoryEntries("*xml").size)
        assertEquals(1, errorDir.listDirectoryEntries("*response").size)

        // but no additional requests for the error and response file should have been made, i.e. the request count
        // should still be 6 (2 successful and four failed requests)
        await().during(100, TimeUnit.MILLISECONDS).until(cdrServiceMock::requestCount) { it == 6 }

        // ignored files like the error and response file don't get processed and thus are not supposed to be in the processing cache
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    @Test
    fun `test successfully write two files to API fail with third TEAPOT - no separate error directory, rename file`() {
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
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.I_AM_A_TEAPOT.value()).body("{\"message\": \"Exception\"}").build())

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
        await().during(100L, TimeUnit.MILLISECONDS)
            .until { sourceDir.listDirectoryEntries("*$RESTART_FILE_EXTENSION").size == 1 }

        assertEquals(0, sourceDir.listDirectoryEntries("*xml").size)

        // but no additional requests for the renamed file should have been made, i.e. the request count
        // should still be 6 (2 successful and four failed requests)
        await().during(100, TimeUnit.MILLISECONDS).until(cdrServiceMock::requestCount) { it == 6 }

        // ignored files like the error and response file don't get processed and thus are not supposed to be in the processing cache
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    @Test
    fun `test successfully write two files to API fail with third NOT_FOUND - file is retried`() {
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
        // 404s simulate a known Azure Function App hiccup where the function runtime returns intermittent 404s for all function endpoints for whatever reason
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.NOT_FOUND.value()).body("{\"message\": \"Exception\"}").build())
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.NOT_FOUND.value()).body("{\"message\": \"Exception\"}").build())
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.NOT_FOUND.value()).body("{\"message\": \"Exception\"}").build())
        cdrServiceMock.enqueue(mockResponse)

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

        val expectedMinRequests = 6
        await().atMost(5, TimeUnit.SECONDS).until { cdrServiceMock.requestCount == expectedMinRequests }
        // eventually the upload succeeds and the state gets cleared
        await().until({ runBlocking { fileCache.getKeys() } }) { it.isEmpty() }
    }

    @Test
    @Suppress("LongMethod")
    fun `test successfully write two files to API fail with third - with error folder for specific type`() {
        val relativeErrorDir = Path.of(ERROR_DIR_NAME) // will be assumed to be a subdirectory of the source directory
        val sourceDir0 = tmpDir.resolve(sourceDirectory)
        val targetDir0 = tmpDir.resolve(targetDirectory)
        val invoiceSourceDir = sourceDir0.resolve("invoice").also { it.resolve(ERROR_DIR_NAME).createDirectories() }

        every { config.customer } returns Customer(
            mutableListOf(
                Connector(
                    connectorId = ConnectorId("2345"),
                    targetFolder = targetDir0,
                    sourceFolder = sourceDir0,
                    contentType = forumDatenaustauschMediaType.toString(),
                    mode = CdrClientConfig.Mode.TEST,
                    sourceErrorFolder = relativeErrorDir,
                    docTypeFolders = mapOf(
                        DocumentType.INVOICE to Connector.DocTypeFolders(
                            sourceFolder = invoiceSourceDir,
                            errorFolder = relativeErrorDir,
                        )
                    )
                )
            )
        )

        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
            .body("{\"message\": \"Upload successful\"}")
            .build()
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.BAD_REQUEST.value()).body("{\"message\": \"Exception\"}").build())

        val payload1 = invoiceSourceDir.resolve("dummy.xml.tmp")
        payload1.outputStream()
            .use {
                it.write(
                    """
                <invoice:request xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:invoice="http://www.forum-datenaustausch.ch/invoice" 
                xsi:schemaLocation="http://www.forum-datenaustausch.ch/invoice generalInvoiceRequest_450.xsd" />
                """.trimIndent().toByteArray()
                )
            }
        val payload2 = invoiceSourceDir.resolve("dummy-2.xml.tmp")
        payload2.outputStream().use {
            it.write(
                """
                <invoice:request xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:invoice="http://www.forum-datenaustausch.ch/invoice" 
                xsi:schemaLocation="http://www.forum-datenaustausch.ch/invoice generalInvoiceRequest_450.xsd" />
                """.trimIndent().toByteArray()
            )
        }
        val payload3 = invoiceSourceDir.resolve("dummy-3.xml.tmp")
        payload3.outputStream().use {
            it.write(
                """
                <invoice:request xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:invoice="http://www.forum-datenaustausch.ch/invoice" 
                xsi:schemaLocation="http://www.forum-datenaustausch.ch/invoice generalInvoiceRequest_450.xsd" />
                """.trimIndent().toByteArray()
            )
        }

        assertEquals(3, invoiceSourceDir.listDirectoryEntries().filter { it.isRegularFile() }.size)

        Files.move(payload1, payload1.resolveSibling(payload1.nameWithoutExtension))
        Files.move(payload2, payload2.resolveSibling(payload2.nameWithoutExtension))
        Files.move(payload3, payload3.resolveSibling(payload3.nameWithoutExtension))

        val errorDir = config.customer.first().getEffectiveSourceErrorFolder(DocumentType.INVOICE)
        assertTrue(errorDir.contains(Path.of("invoice")))

        await().during(1, TimeUnit.SECONDS).until(cdrServiceMock::requestCount) { it == 3 }
        await().during(100L, TimeUnit.MILLISECONDS).until(invoiceSourceDir::listDirectoryEntries) { paths -> paths.none { path -> path.isRegularFile() } }
        await().during(100L, TimeUnit.MILLISECONDS).until { errorDir.listDirectoryEntries("*response").size == 1 }

        assertTrue(errorDir.startsWith(invoiceSourceDir))
        assertEquals(1, errorDir.listDirectoryEntries("*xml").size)
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
