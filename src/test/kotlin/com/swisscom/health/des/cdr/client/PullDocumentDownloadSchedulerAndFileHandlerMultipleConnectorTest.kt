package com.swisscom.health.des.cdr.client

import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IAuthenticationResult
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.microsoft.aad.msal4j.TokenSource
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.CONNECTOR_ID_HEADER
import com.swisscom.health.des.cdr.client.handler.PULL_RESULT_ID_HEADER
import com.swisscom.health.des.cdr.client.handler.PullFileHandling
import com.swisscom.health.des.cdr.client.scheduling.DocumentDownloadScheduler
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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

    private lateinit var documentDownloadScheduler: DocumentDownloadScheduler
    private lateinit var pullFileHandling: PullFileHandling

    private var counterOne = 0
    private var counterTwo = 0

    private val directory1 = "customer"
    private val directory2 = "otherOne"
    private val inflightFolder = "inflight"
    private val connectorId1 = "1234"
    private val connectorId2 = "3456"

    private val forumDatenaustauschMediaType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")

    @BeforeEach
    fun setup() {
        cdrServiceMock = MockWebServer()
        cdrServiceMock.start()

        val endpoint = CdrClientConfig.Endpoint(
            host = cdrServiceMock.hostName,
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )
        val connector1 =
            CdrClientConfig.Connector(
                connectorId = connectorId1,
                targetFolder = tmpDir.resolve(directory1),
                sourceFolder = tmpDir.resolve(directory1).resolve("source"),
                contentType = forumDatenaustauschMediaType,
                mode = CdrClientConfig.Mode.TEST
            )
        val connector2 =
            CdrClientConfig.Connector(
                connectorId = connectorId2,
                targetFolder = tmpDir.resolve(directory2),
                sourceFolder = tmpDir.resolve(directory2).resolve("source"),
                contentType = forumDatenaustauschMediaType,
                mode = CdrClientConfig.Mode.PRODUCTION
            )
        val localFolder = tmpDir.resolve(inflightFolder)

        connector1.sourceFolder.createDirectories()
        connector2.sourceFolder.createDirectories()
        localFolder.createDirectories()

        every { config.customer } returns listOf(connector1, connector2)
        every { config.endpoint } returns endpoint
        every { config.localFolder } returns localFolder
        every { config.idpCredentials.tenantId } returns "something"

        every { retryIoErrorsThrice.execute(any<RetryCallback<String, Exception>>()) } answers { "Mocked Result" }

        val resultMock: CompletableFuture<IAuthenticationResult> = mockk()
        val authMock: IAuthenticationResult = mockk()
        every { resultMock.get() } returns authMock
        every { authMock.metadata().tokenSource() } returns TokenSource.CACHE
        every { authMock.accessToken() } returns "123"
        every { securedApp.acquireToken(any<ClientCredentialParameters>()) } returns resultMock

        mockTracer()

        pullFileHandling = PullFileHandling(config, OkHttpClient.Builder().build(), clientCredentialParams, retryIoErrorsThrice, securedApp, tracer)
        documentDownloadScheduler = DocumentDownloadScheduler(
            config,
            pullFileHandling,
            Dispatchers.IO,
        )

    }

    @AfterEach
    fun tearDown() {
        cdrServiceMock.shutdown()
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
        every { span.end() } returns Unit
        every { span.tag(any(), any<String>()) } returns span
        every { span.context() } returns traceContext
        every { spanInScope.close() } returns Unit
    }

    private fun handleDispatcher(request: RecordedRequest, practOneMaxCount: Int, practTwoMaxCount: Int): MockResponse {
        return if (request.method == "GET" && request.headers[CONNECTOR_ID_HEADER] == connectorId1
        ) {
            mockResponseDependingOnPath(request) { handleConnectorOne(practOneMaxCount) }
        } else if (request.method == "GET" && request.headers[CONNECTOR_ID_HEADER] == connectorId2
        ) {
            mockResponseDependingOnPath(request) { handleConnectorTwo(practTwoMaxCount) }
        } else if (request.method == "DELETE") {
            MockResponse().setResponseCode(HttpStatus.OK.value())
        } else {
            MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .setBody("I'm sorry. My responses are limited. You must ask the right questions.")
        }
    }

    private fun mockResponseDependingOnPath(request: RecordedRequest, handleMethod: () -> MockResponse): MockResponse {
        return with(request.path!!) {
            when {
                contains("limit=1") -> handleMethod()
                else -> MockResponse().setResponseCode(HttpStatus.NOT_FOUND.value())
            }
        }
    }

    private fun handleConnectorOne(maxCount: Int): MockResponse {
        return if (counterOne < maxCount) {
            counterOne++
            MockResponse().setResponseCode(HttpStatus.OK.value())
                .setHeader(PULL_RESULT_ID_HEADER, UUID.randomUUID().toString())
                .setBody(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
        } else {
            MockResponse().setResponseCode(HttpStatus.NO_CONTENT.value())
        }
    }

    private fun handleConnectorTwo(maxCount: Int): MockResponse {
        return if (counterTwo < maxCount) {
            counterTwo++
            MockResponse().setResponseCode(HttpStatus.OK.value())
                .setHeader(PULL_RESULT_ID_HEADER, UUID.randomUUID().toString())
                .setBody(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
        } else {
            MockResponse().setResponseCode(HttpStatus.NO_CONTENT.value())
        }
    }

    @Test
    fun `test sync of multiple files to folder for two connectors`() = runTest {
        val practOneMaxCount = 75
        val practTwoMaxCount = 50
        cdrServiceMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return handleDispatcher(request, practOneMaxCount, practTwoMaxCount)
            }
        }

        documentDownloadScheduler.syncFilesToClientFolders()


        assertEquals(practTwoMaxCount * 2 + practOneMaxCount * 2 + 2, cdrServiceMock.requestCount)

        val listFiles: List<Path> = tmpDir.listDirectoryEntries()
        assertNotNull(listFiles)
        assertEquals(3, listFiles.size)
        assertTrue(listFiles.first { !it.endsWith(inflightFolder) }.listDirectoryEntries().size > 5)
        assertEquals(counterOne, listFiles.first { it.endsWith(directory1) }.listDirectoryEntries().filter { it.extension == "xml" }.size)
        assertEquals(counterTwo, listFiles.first { it.endsWith(directory2) }.listDirectoryEntries().filter { it.extension == "xml" }.size)
        assertTrue(listFiles.first { it.endsWith(inflightFolder) }.listDirectoryEntries().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `test sync of multiple files to folder for one connector`() = runTest {
        val practOneMaxCount = 10
        val practTwoMaxCount = 0
        cdrServiceMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return handleDispatcher(request, practOneMaxCount, practTwoMaxCount)
            }
        }

        documentDownloadScheduler.syncFilesToClientFolders()
        advanceUntilIdle()

        assertEquals(practTwoMaxCount * 2 + practOneMaxCount * 2 + 2, cdrServiceMock.requestCount)
        val listFiles = tmpDir.listDirectoryEntries()
        assertEquals(counterOne, listFiles.first { it.endsWith(directory1) }.listDirectoryEntries().filter { it.extension == "xml" }.size)
        assertEquals(counterTwo, listFiles.first { it.endsWith(directory2) }.listDirectoryEntries().filter { it.extension == "xml" }.size)
        assertTrue(listFiles.first { it.endsWith(inflightFolder) }.listDirectoryEntries().isEmpty())
    }

}

