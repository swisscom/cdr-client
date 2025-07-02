package com.swisscom.health.des.cdr.client.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.swisscom.health.des.cdr.client.config.CdrApi
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Host
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
import com.swisscom.health.des.cdr.client.xml.DocumentType
import com.swisscom.health.des.cdr.client.xml.XmlUtil
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.retry.RetryCallback
import org.springframework.retry.support.RetryTemplate
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

@ExtendWith(MockKExtension::class)
internal class PullFileHandlingTest {
    @MockK
    private lateinit var config: CdrClientConfig

    @MockK
    private lateinit var tracer: Tracer

    @MockK
    private lateinit var spanBuilder: Span.Builder

    @MockK
    private lateinit var span: Span

    @MockK
    private lateinit var spanInScope: Tracer.SpanInScope

    @MockK
    private lateinit var traceContext: TraceContext

    @MockK
    private lateinit var clientCredentialParams: ClientCredentialParameters

    @MockK
    private lateinit var retryIoErrorsThrice: RetryTemplate

    @MockK
    private lateinit var securedApp: IConfidentialClientApplication

    @TempDir
    private lateinit var tmpDir: Path

    private lateinit var cdrServiceMock: MockWebServer

    private lateinit var pullFileHandling: PullFileHandling

    private lateinit var cdrApiClient: CdrApiClient

    private val inflightFolder = "inflight"
    private val targetDirectory = "customer"
    private val sourceDirectory = "source"
    private lateinit var endpoint: CdrApi

    @BeforeEach
    fun setup() {
        cdrServiceMock = MockWebServer()
        cdrServiceMock.start()
        mockTracer()

        endpoint = CdrApi(
            host = Host(cdrServiceMock.hostName),
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )

        tmpDir.resolve(targetDirectory).also { it.createDirectories() }
        val inflightDir = tmpDir.resolve(inflightFolder).also { it.createDirectories() }

        every { config.cdrApi } returns endpoint
        every { config.localFolder } returns TempDownloadDir(inflightDir)
        every { config.idpCredentials.tenantId } returns TenantId("something")

        every { retryIoErrorsThrice.execute(any<RetryCallback<String, Exception>>()) } returns "Mocked Result"

        cdrApiClient = CdrApiClient(config, OkHttpClient.Builder().build(), clientCredentialParams, retryIoErrorsThrice, securedApp, ObjectMapper())
        pullFileHandling = PullFileHandling(tracer, cdrApiClient, XmlUtil())
    }

    @AfterEach
    fun tearDown() {
        cdrServiceMock.shutdown()
    }

    @Test
    fun `test sync of single file to folder`() {
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
        tmpDir.resolve(inflightFolder).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of single file to type folder`() {
        enqueueFileResponseWithReportResponse("generalInvoice450_qr_dt.xml")
        enqueueEmptyResponse()

        val invoiceFolder = tmpDir.resolve("invoice").also { it.createDirectories() }

        val connector = CdrClientConfig.Connector(
            connectorId = "1-2-3-4",
            targetFolder = tmpDir.resolve(targetDirectory),
            sourceFolder = tmpDir.resolve(sourceDirectory),
            contentType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8"),
            mode = CdrClientConfig.Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to CdrClientConfig.Connector.DocTypeFolders(
                    targetFolder = invoiceFolder,
                )
            )
        )

        runBlocking {
            pullFileHandling.pullSyncConnector(connector)
        }

        assertEquals(3, cdrServiceMock.requestCount, "the wrong amount of requests where done")
        val listFiles = tmpDir.listDirectoryEntries()
        assertEquals(3, listFiles.size)

        invoiceFolder.listDirectoryEntries().let {
            assertEquals(1, it.size)
            assertTrue(it[0].extension == "xml", "File extension is not .xml")
        }
        tmpDir.resolve(inflightFolder).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of single file to default folder but another type is defined`() {
        enqueueFileResponseWithReportResponse("notification_example_with_attachment.xml")
        enqueueEmptyResponse()

        val invoiceFolder = tmpDir.resolve("invoice").also { it.createDirectories() }
        val targetDir = tmpDir.resolve(targetDirectory)

        val connector = CdrClientConfig.Connector(
            connectorId = "1-2-3-4",
            targetFolder = targetDir,
            sourceFolder = tmpDir.resolve(sourceDirectory),
            contentType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8"),
            mode = CdrClientConfig.Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to CdrClientConfig.Connector.DocTypeFolders(
                    targetFolder = invoiceFolder,
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
        tmpDir.resolve(inflightFolder).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of single file to default folder for unknown type and another type is defined`() {
        enqueueFileResponseWithReportResponse()
        enqueueEmptyResponse()

        val invoiceFolder = tmpDir.resolve("invoice").also { it.createDirectories() }
        val targetDir = tmpDir.resolve(targetDirectory)

        val connector = CdrClientConfig.Connector(
            connectorId = "1-2-3-4",
            targetFolder = targetDir,
            sourceFolder = tmpDir.resolve(sourceDirectory),
            contentType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8"),
            mode = CdrClientConfig.Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to CdrClientConfig.Connector.DocTypeFolders(
                    targetFolder = invoiceFolder,
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
        tmpDir.resolve(inflightFolder).listDirectoryEntries().let {
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
        tmpDir.resolve(inflightFolder).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of single file to folder with failed report for success`() {
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
        tmpDir.resolve(inflightFolder).listDirectoryEntries().let {
            assertEquals(1, it.size)
        }
    }

    @Test
    fun `test sync of multiple files to folder`() {
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
        tmpDir.resolve(inflightFolder).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `test sync of multiple files with an exception to folder`() {
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
        tmpDir.resolve(inflightFolder).listDirectoryEntries().let {
            assertTrue(it.isEmpty())
        }
    }

    private fun createConnector(
        connectorId0: String,
        targetFolder0: Path = tmpDir.resolve(targetDirectory),
        sourceFolder0: Path = tmpDir.resolve(sourceDirectory),
    ): CdrClientConfig.Connector =
        CdrClientConfig.Connector(
            connectorId = connectorId0,
            targetFolder = targetFolder0,
            sourceFolder = sourceFolder0,
            contentType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8"),
            mode = CdrClientConfig.Mode.PRODUCTION,
        )

    private fun enqueueFileResponseWithReportResponse(fileName: String = "dummy.txt") {
        enqueueFileResponse(fileName)
        enqueueReportResponse()
    }

    private fun enqueueFileResponse(fileName: String = "dummy.txt") {
        val pullRequestId = UUID.randomUUID().toString()
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .setHeader(PULL_RESULT_ID_HEADER, pullRequestId)
            .setBody(String(ClassPathResource("messages/$fileName").inputStream.readAllBytes(), StandardCharsets.UTF_8))
        cdrServiceMock.enqueue(mockResponse)
    }

    private fun enqueueFileResponseNoHeader() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .setBody(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
        cdrServiceMock.enqueue(mockResponse)
    }

    private fun enqueueReportResponse() {
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.OK.value()))
    }

    private fun enqueueEmptyResponse() {
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.NO_CONTENT.value()))
    }

    private fun enqueueExceptionResponse() {
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()))
    }

    private fun mockTracer() {
        every { tracer.spanBuilder() } returns spanBuilder
        every { tracer.currentSpan() } returns null
        every { spanBuilder.setNoParent() } returns spanBuilder
        every { spanBuilder.name(any()) } returns spanBuilder
        every { spanBuilder.start() } returns span
        every { tracer.withSpan(any()) } returns spanInScope
        every { span.name(any()) } returns span
        every { span.start() } returns span
        every { span.event(any()) } returns span
        every { span.tag(any(), any<String>()) } returns span
        every { span.context() } returns traceContext
        every { spanInScope.close() } returns Unit
    }

}


