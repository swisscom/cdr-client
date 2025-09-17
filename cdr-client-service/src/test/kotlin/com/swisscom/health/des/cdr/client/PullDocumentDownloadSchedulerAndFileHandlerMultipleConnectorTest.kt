package com.swisscom.health.des.cdr.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IAuthenticationResult
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.microsoft.aad.msal4j.TokenSource
import com.swisscom.health.des.cdr.client.config.CdrApi
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.ConnectorId
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.config.Host
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
import com.swisscom.health.des.cdr.client.handler.CdrApiClient
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.Companion.CONNECTOR_ID_HEADER
import com.swisscom.health.des.cdr.client.handler.PULL_RESULT_ID_HEADER
import com.swisscom.health.des.cdr.client.handler.PullFileHandling
import com.swisscom.health.des.cdr.client.handler.SchedulingValidationService
import com.swisscom.health.des.cdr.client.scheduling.DocumentDownloadScheduler
import com.swisscom.health.des.cdr.client.xml.XmlUtil
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

@ExtendWith(MockKExtension::class)
internal class PullDocumentDownloadSchedulerAndFileHandlerMultipleConnectorTest {

    @MockK
    private lateinit var config: CdrClientConfig

    @MockK
    private lateinit var schedulingValidationService: SchedulingValidationService

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

    @MockK
    private lateinit var xmlParser: XmlUtil

    @TempDir
    private lateinit var tmpDir: Path

    @StartStop
    private val cdrServiceMock = MockWebServer()

    private lateinit var documentDownloadScheduler: DocumentDownloadScheduler
    private lateinit var pullFileHandling: PullFileHandling
    private lateinit var cdrApiClient: CdrApiClient

    private var counterOne = 0
    private var counterTwo = 0

    private val directory1 = "customer"
    private val directory2 = "otherOne"
    private val inflightDir = "inflight"
    private val connectorId1 = "1234"
    private val connectorId2 = "3456"

    private val forumDatenaustauschMediaType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")

    @BeforeEach
    fun setup() {
        val endpoint = CdrApi(
            host = Host(cdrServiceMock.hostName),
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )
        val connector1 =
            Connector(
                connectorId = ConnectorId(connectorId1),
                targetFolder = tmpDir.resolve(directory1),
                sourceFolder = tmpDir.resolve(directory1).resolve("source"),
                contentType = forumDatenaustauschMediaType.toString(),
                mode = CdrClientConfig.Mode.TEST,
            )
        val connector2 =
            Connector(
                connectorId = ConnectorId(connectorId2),
                targetFolder = tmpDir.resolve(directory2),
                sourceFolder = tmpDir.resolve(directory2).resolve("source"),
                contentType = forumDatenaustauschMediaType.toString(),
                mode = CdrClientConfig.Mode.PRODUCTION,
            )
        val localDir = tmpDir.resolve(inflightDir)

        connector1.sourceFolder.createDirectories()
        connector2.sourceFolder.createDirectories()
        localDir.createDirectories()

        every { config.customer } returns Customer(mutableListOf(connector1, connector2))
        every { config.cdrApi } returns endpoint
        every { config.localFolder } returns TempDownloadDir(localDir)
        every { config.idpCredentials.tenantId } returns TenantId("something")
        every { schedulingValidationService.isSchedulingAllowed } returns true

        every { retryIoErrorsThrice.execute(any<RetryCallback<String, Exception>>()) } answers { "Mocked Result" }

        val resultMock: CompletableFuture<IAuthenticationResult> = mockk()
        val authMock: IAuthenticationResult = mockk()
        every { resultMock.get() } returns authMock
        every { authMock.metadata().tokenSource() } returns TokenSource.CACHE
        every { authMock.accessToken() } returns "123"
        every { securedApp.acquireToken(any<ClientCredentialParameters>()) } returns resultMock

        mockTracer()

        cdrApiClient = CdrApiClient(config, OkHttpClient.Builder().build(), clientCredentialParams, retryIoErrorsThrice, securedApp, ObjectMapper())
        pullFileHandling = PullFileHandling(tracer, cdrApiClient, xmlParser)
        documentDownloadScheduler = DocumentDownloadScheduler(
            config,
            schedulingValidationService,
            pullFileHandling,
            Dispatchers.IO,
        )

    }

    @AfterEach
    fun tearDown() {
        counterOne = 0
        counterTwo = 0
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

    private fun handleDispatcher(request: RecordedRequest, practOneMaxCount: Int, practTwoMaxCount: Int): MockResponse {
        return when (request.method) {
            "GET" if request.headers[CONNECTOR_ID_HEADER] == connectorId1 -> {
                mockResponseDependingOnPath(request) { handleConnectorOne(practOneMaxCount) }
            }
            "GET" if request.headers[CONNECTOR_ID_HEADER] == connectorId2 -> {
                mockResponseDependingOnPath(request) { handleConnectorTwo(practTwoMaxCount) }
            }
            "DELETE" -> {
                MockResponse.Builder().code(HttpStatus.OK.value()).build()
            }
            else -> {
                MockResponse.Builder().code(HttpStatus.INTERNAL_SERVER_ERROR.value())
                    .body("I'm sorry. My responses are limited. You must ask the right questions.")
                    .build()
            }
        }
    }

    private fun mockResponseDependingOnPath(request: RecordedRequest, handleMethod: () -> MockResponse): MockResponse {
        return with(request.target) {
            when {
                contains("limit=1") -> handleMethod()
                else -> MockResponse.Builder().code(HttpStatus.NOT_FOUND.value()).build()
            }
        }
    }

    private fun handleConnectorOne(maxCount: Int): MockResponse {
        return if (counterOne < maxCount) {
            counterOne++
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(Headers.Builder().add(PULL_RESULT_ID_HEADER, UUID.randomUUID().toString()).build())
                .body(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
                .build()
        } else {
            MockResponse.Builder().code(HttpStatus.NO_CONTENT.value()).build()
        }
    }

    private fun handleConnectorTwo(maxCount: Int): MockResponse {
        return if (counterTwo < maxCount) {
            counterTwo++
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(Headers.Builder().add(PULL_RESULT_ID_HEADER, UUID.randomUUID().toString()).build())
                .body(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
                .build()
        } else {
            MockResponse.Builder().code(HttpStatus.NO_CONTENT.value()).build()
        }
    }

    @Test
    fun `test sync of multiple files to directory for two connectors`() = runTest {
        val practOneMaxCount = 75
        val practTwoMaxCount = 50
        cdrServiceMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return handleDispatcher(request, practOneMaxCount, practTwoMaxCount)
            }
        }

        documentDownloadScheduler.syncFilesToClientDirectories()


        assertEquals(practTwoMaxCount * 2 + practOneMaxCount * 2 + 2, cdrServiceMock.requestCount)

        val listFiles: List<Path> = tmpDir.listDirectoryEntries()
        assertNotNull(listFiles)
        assertEquals(3, listFiles.size)
        assertTrue(listFiles.first { !it.endsWith(inflightDir) }.listDirectoryEntries().size > 5)
        assertEquals(counterOne, listFiles.first { it.endsWith(directory1) }.listDirectoryEntries().filter { it.extension == "xml" }.size)
        assertEquals(counterTwo, listFiles.first { it.endsWith(directory2) }.listDirectoryEntries().filter { it.extension == "xml" }.size)
        assertTrue(listFiles.first { it.endsWith(inflightDir) }.listDirectoryEntries().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `test sync of multiple files to directory for one connector`() = runTest {
        val practOneMaxCount = 10
        val practTwoMaxCount = 0
        cdrServiceMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return handleDispatcher(request, practOneMaxCount, practTwoMaxCount)
            }
        }

        documentDownloadScheduler.syncFilesToClientDirectories()
        advanceUntilIdle()

        assertEquals(practTwoMaxCount * 2 + practOneMaxCount * 2 + 2, cdrServiceMock.requestCount)
        val listFiles = tmpDir.listDirectoryEntries()
        assertEquals(counterOne, listFiles.first { it.endsWith(directory1) }.listDirectoryEntries().filter { it.extension == "xml" }.size)
        assertEquals(counterTwo, listFiles.first { it.endsWith(directory2) }.listDirectoryEntries().filter { it.extension == "xml" }.size)
        assertTrue(listFiles.first { it.endsWith(inflightDir) }.listDirectoryEntries().isEmpty())
    }

}

