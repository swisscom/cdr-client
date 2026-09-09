package com.swisscom.health.des.cdr.client.handler

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import tools.jackson.databind.json.JsonMapper
import com.swisscom.health.des.cdr.client.LogCorrelation
import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.config.CdrApi
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.ConnectorId
import com.swisscom.health.des.cdr.client.config.Host
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.Companion.PULL_RESULT_ID_HEADER
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.retry.RetryCallback
import org.springframework.retry.support.RetryTemplate
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import org.slf4j.LoggerFactory

@ExtendWith(MockKExtension::class)
internal class PullFileHandlingTest {
    @MockK
    private lateinit var config: CdrClientConfig

    @MockK
    private lateinit var retryIoErrorsThrice: RetryTemplate

    @TempDir
    private lateinit var tmpDir: Path

    @StartStop
    private val cdrServiceMock = MockWebServer()

    private lateinit var pullFileHandling: PullFileHandling

    private lateinit var cdrApiClient: CdrApiClient

    private val inflightDirectory = "inflight"
    private val targetDirectory = "customer"
    private val sourceDirectory = "source"
    private lateinit var endpoint: CdrApi

    @BeforeEach
    fun setup() {
        endpoint = CdrApi(
            host = Host(cdrServiceMock.hostName),
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )

        tmpDir.resolve(targetDirectory).also { it.createDirectories() }
        val inflightDir = tmpDir.resolve(inflightDirectory).also { it.createDirectories() }

        every { config.cdrApi } returns endpoint
        every { config.localFolder } returns TempDownloadDir(inflightDir)
        every { config.idpCredentials.tenantId } returns TenantId("something")

        every { retryIoErrorsThrice.execute(any<RetryCallback<String, Exception>>()) } returns "Mocked Result"

        cdrApiClient = CdrApiClient(config, OkHttpClient.Builder().build(), retryIoErrorsThrice, JsonMapper.builder().findAndAddModules().build(), "OS")
        pullFileHandling = PullFileHandling(cdrApiClient)
    }

    @Test
    fun `test sync of single file to directory`() {
        enqueueFileResponseWithReportResponse()
        enqueueEmptyResponse()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }

        assertEquals(3, cdrServiceMock.requestCount, "more requests were done than expected")
        val listFiles = tmpDir.listDirectoryEntries()
        assertEquals(2, listFiles.size)

        tmpDir.resolve(targetDirectory).listDirectoryEntries().let {
            assertEquals(1, it.size)
            assertTrue(it[0].extension == "xml", "File extension is not .xml")
        }
        tmpDir.resolve(inflightDirectory).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `pull sync emits one trace id across log lines and request headers`() {
        enqueueFileResponseWithReportResponse()
        enqueueEmptyResponse()
        val appender = attachAppender()

        try {
            runBlocking {
                pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
            }
        } finally {
            detachAppender(appender)
        }

        val requests = listOfNotNull(
            cdrServiceMock.takeRequest(),
            cdrServiceMock.takeRequest(),
            cdrServiceMock.takeRequest(),
        )
        val requestTraceIds = requests.mapNotNull { it.headers[CdrApiClient.AZURE_TRACE_ID_HEADER] }
        assertEquals(3, requestTraceIds.size)
        assertTrue(requestTraceIds.all { it.isNotBlank() })
        assertEquals(1, requestTraceIds.distinct().size)

        val logTraceIds = appender.list
            .mapNotNull { it.mdcPropertyMap[LogCorrelation.TRACE_ID_KEY] }
            .filter { it.isNotBlank() }
        assertFalse(logTraceIds.isEmpty())
        assertEquals(1, logTraceIds.distinct().size)
        assertEquals(requestTraceIds.first(), logTraceIds.first())
    }

    @Test
    fun `pull sync assigns different trace ids to separate operations`() {
        val firstAppender = attachAppender()
        enqueueEmptyResponse()
        val firstTraceId = try {
            runBlocking {
                pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
            }
            cdrServiceMock.takeRequest().headers[CdrApiClient.AZURE_TRACE_ID_HEADER]
        } finally {
            detachAppender(firstAppender)
        }

        val secondAppender = attachAppender()
        enqueueEmptyResponse()
        val secondTraceId = try {
            runBlocking {
                pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
            }
            cdrServiceMock.takeRequest().headers[CdrApiClient.AZURE_TRACE_ID_HEADER]
        } finally {
            detachAppender(secondAppender)
        }

        assertNotNull(firstTraceId)
        assertNotNull(secondTraceId)
        assertNotEquals(firstTraceId, secondTraceId)
    }

    @Test
    fun `test sync of single file to type directory`() {
        enqueueFileResponseWithReportResponse("generalInvoice450_qr_dt.xml")
        enqueueEmptyResponse()

        val invoiceDir = tmpDir.resolve("invoice").also { it.createDirectories() }

        val connector = Connector(
            connectorId = ConnectorId("1-2-3-4"),
            targetFolder = tmpDir.resolve(targetDirectory),
            sourceFolder = tmpDir.resolve(sourceDirectory),
            contentType = "application/forumdatenaustausch+xml;charset=UTF-8",
            mode = CdrClientConfig.Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to Connector.DocTypeFolders(
                    targetFolder = invoiceDir,
                )
            )
        )

        runBlocking {
            pullFileHandling.pullSyncConnector(connector)
        }

        assertEquals(3, cdrServiceMock.requestCount, "the wrong amount of requests where done")
        val listFiles = tmpDir.listDirectoryEntries()
        assertEquals(3, listFiles.size)

        invoiceDir.listDirectoryEntries().let {
            assertEquals(1, it.size)
            assertTrue(it[0].extension == "xml", "File extension is not .xml")
        }
        tmpDir.resolve(inflightDirectory).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of single file to default directory but another type is defined`() {
        enqueueFileResponseWithReportResponse("notification_example_with_attachment.xml")
        enqueueEmptyResponse()

        val invoiceDir = tmpDir.resolve("invoice").also { it.createDirectories() }
        val targetDir = tmpDir.resolve(targetDirectory)

        val connector = Connector(
            connectorId = ConnectorId("1-2-3-4"),
            targetFolder = targetDir,
            sourceFolder = tmpDir.resolve(sourceDirectory),
            contentType = "application/forumdatenaustausch+xml;charset=UTF-8",
            mode = CdrClientConfig.Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to Connector.DocTypeFolders(
                    targetFolder = invoiceDir,
                )
            )
        )

        runBlocking {
            pullFileHandling.pullSyncConnector(connector)
        }

        assertEquals(3, cdrServiceMock.requestCount, "the wrong amount of requests where done")
        val listFiles = tmpDir.listDirectoryEntries()
        assertEquals(3, listFiles.size)

        targetDir.listDirectoryEntries().let {
            assertEquals(1, it.size)
            assertTrue(it[0].extension == "xml", "File extension is not .xml")
        }
        tmpDir.resolve(inflightDirectory).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of single file to default directory for unknown type and another type is defined`() {
        enqueueFileResponseWithReportResponse()
        enqueueEmptyResponse()

        val invoiceDir = tmpDir.resolve("invoice").also { it.createDirectories() }
        val targetDir = tmpDir.resolve(targetDirectory)

        val connector = Connector(
            connectorId = ConnectorId("1-2-3-4"),
            targetFolder = targetDir,
            sourceFolder = tmpDir.resolve(sourceDirectory),
            contentType = "application/forumdatenaustausch+xml;charset=UTF-8",
            mode = CdrClientConfig.Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to Connector.DocTypeFolders(
                    targetFolder = invoiceDir,
                )
            )
        )

        runBlocking {
            pullFileHandling.pullSyncConnector(connector)
        }

        assertEquals(3, cdrServiceMock.requestCount, "the wrong amount of requests where done")
        val listFiles = tmpDir.listDirectoryEntries()
        assertEquals(3, listFiles.size)

        targetDir.listDirectoryEntries().let {
            assertEquals(1, it.size)
            assertTrue(it[0].extension == "xml", "File extension is not .xml")
        }
        tmpDir.resolve(inflightDirectory).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of single file no header present`() {
        enqueueFileResponseNoHeader()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }

        assertEquals(1, cdrServiceMock.requestCount, "more requests were done than expected")

        tmpDir.resolve(targetDirectory).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
        tmpDir.resolve(inflightDirectory).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of single file to directory with failed report for success`() {
        enqueueFileResponse()
        enqueueExceptionResponse()
        enqueueEmptyResponse()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }

        assertEquals(2, cdrServiceMock.requestCount, "more requests were done than expected")

        tmpDir.resolve(targetDirectory).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
        tmpDir.resolve(inflightDirectory).listDirectoryEntries().let {
            assertEquals(1, it.size)
        }
    }

    @Test
    fun `test sync of multiple files to directory`() {
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueEmptyResponse()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }


        assertEquals(11, cdrServiceMock.requestCount, "more requests were done than expected")

        tmpDir.resolve(targetDirectory).listDirectoryEntries().let {
            assertEquals(5, it.size)
        }
        tmpDir.resolve(inflightDirectory).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of multiple files with an exception to directory`() {
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueExceptionResponse()
        enqueueFileResponseWithReportResponse()
        enqueueEmptyResponse()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }

        assertEquals(7, cdrServiceMock.requestCount, "more requests were done than expected")


        tmpDir.resolve(targetDirectory).listDirectoryEntries().let {
            assertEquals(3, it.size)
        }
        tmpDir.resolve(inflightDirectory).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    private fun createConnector(
        connectorId0: String,
        targetDir0: Path = tmpDir.resolve(targetDirectory),
        sourceDir0: Path = tmpDir.resolve(sourceDirectory),
    ): Connector =
        Connector(
            connectorId = ConnectorId(connectorId0),
            targetFolder = targetDir0,
            sourceFolder = sourceDir0,
            contentType = "application/forumdatenaustausch+xml;charset=UTF-8",
            mode = CdrClientConfig.Mode.PRODUCTION,
        )

    private fun enqueueFileResponseWithReportResponse(fileName: String = "dummy.txt") {
        enqueueFileResponse(fileName)
        enqueueReportResponse()
    }

    private fun enqueueFileResponse(fileName: String = "dummy.txt") {
        val pullRequestId = UUID.randomUUID().toString()
        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(Headers.Builder().add(PULL_RESULT_ID_HEADER, pullRequestId).build())
            .body(String(ClassPathResource("messages/$fileName").inputStream.readAllBytes(), StandardCharsets.UTF_8))
            .build()
        cdrServiceMock.enqueue(mockResponse)
    }

    private fun enqueueFileResponseNoHeader() {
        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .body(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
            .build()
        cdrServiceMock.enqueue(mockResponse)
    }

    private fun enqueueReportResponse() {
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.OK.value()).build())
    }

    private fun enqueueEmptyResponse() {
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.NO_CONTENT.value()).build())
    }

    private fun enqueueExceptionResponse() {
        cdrServiceMock.enqueue(MockResponse.Builder().code(HttpStatus.INTERNAL_SERVER_ERROR.value()).build())
    }

    private fun attachAppender(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("com.swisscom.health.des.cdr.client") as Logger
        return ListAppender<ILoggingEvent>().apply {
            start()
            logger.addAppender(this)
        }
    }

    private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
        val logger = LoggerFactory.getLogger("com.swisscom.health.des.cdr.client") as Logger
        logger.detachAppender(appender)
        appender.stop()
    }

}
