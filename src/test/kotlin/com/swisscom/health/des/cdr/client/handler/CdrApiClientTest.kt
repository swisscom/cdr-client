package com.swisscom.health.des.cdr.client.handler

import com.ninjasquad.springmockk.SpykBean
import com.swisscom.health.des.cdr.client.AlwaysSameTempDirFactory
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.Companion.AZURE_TRACE_ID_HEADER
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.Companion.CDR_PROCESSING_MODE_HEADER
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.Companion.CONNECTOR_ID_HEADER
import io.mockk.every
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.annotation.DirtiesContext.ClassMode
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText


@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.main.lazy-initialization=true",
        "spring.jmx.enabled=false",
        "client.idp-credentials.renew-credential-at-startup=false",
        "client.retry-template.retries=4",
        "client.retry-template.initial-delay=10ms",
        "client.retry-template.multiplier=1.1",
        "client.retry-template.max-delay=1s",
    ]
)
@ActiveProfiles("test", "noPollingUploadScheduler", "noEventTriggerUploadScheduler", "noDownloadScheduler")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@Tag("integration-test")
class CdrApiClientTest {

    @SpykBean
    private lateinit var config: CdrClientConfig

    @Autowired
    private lateinit var cdrApiClient: CdrApiClient

    @TempDir
    private lateinit var sourceDir: Path

    private lateinit var apiServerMock: MockWebServer

    @BeforeEach
    fun setup() {
        apiServerMock = MockWebServer()
        apiServerMock.start()

        every { config.credentialApi } returns CdrClientConfig.Endpoint().apply {
            scheme = "http"
            host = apiServerMock.hostName
            port = apiServerMock.port
            basePath = "client-credentials"
        }

        every { config.cdrApi } returns CdrClientConfig.Endpoint().apply {
            scheme = "http"
            host = apiServerMock.hostName
            port = apiServerMock.port
            basePath = "documents"
        }
    }

    @AfterEach
    fun tearDown() {
        apiServerMock.shutdown()
    }

    @Test
    fun `test client credential renewal - status code 200, valid response`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .setBody(RENEW_SECRET_RESPONSE)
        apiServerMock.enqueue(mockResponse)

        val result: CdrApiClient.RenewClientSecretResult = cdrApiClient.renewClientCredential(DEFAULT_TRACE_ID)

        assertInstanceOf<CdrApiClient.RenewClientSecretResult.Success>(result) { "Success expected but got $result" }
        assertEquals(TEST_CLIENT_ID, result.clientId)
        assertEquals(RENEWED_SECRET, result.clientSecret)

        val request: RecordedRequest = requireNotNull(apiServerMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }
        assertEquals("PATCH", request.method)
        assertEquals("/client-credentials/$TEST_CLIENT_ID", request.path)
        assertTrue(requireNotNull(request.headers[HttpHeaders.AUTHORIZATION]).startsWith("Bearer "))
        assertTrue(requireNotNull(request.headers[HttpHeaders.AUTHORIZATION]).removePrefix("Bearer ").isNotBlank())
        assertEquals(DEFAULT_TRACE_ID, request.headers[AZURE_TRACE_ID_HEADER])

        assertEquals(1, apiServerMock.requestCount)
    }

    @Test
    fun `test client credential renewal - status code 200, invalid response type`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML)
            .setBody(INVALID_RENEW_SECRET_RESPONSE)
        apiServerMock.enqueue(mockResponse)

        val result: CdrApiClient.RenewClientSecretResult = cdrApiClient.renewClientCredential(DEFAULT_TRACE_ID)

        assertInstanceOf<CdrApiClient.RenewClientSecretResult.RenewError>(result) { "RenewError expected but got $result" }
        assertTrue(result.message.startsWith("Client credential renewal response is not a JSON content type"))
        assertInstanceOf<IllegalArgumentException>(result.cause) { "IllegalArgumentException expected but got ${result.cause::class.java}" }

        assertEquals(1, apiServerMock.requestCount)
    }

    @Test
    fun `test client credential renewal - status code 404`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.NOT_FOUND.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
            .setBody(NOT_FOUND_PROBLEM_JSON)
        apiServerMock.enqueue(mockResponse)

        val result: CdrApiClient.RenewClientSecretResult = cdrApiClient.renewClientCredential(DEFAULT_TRACE_ID)

        assertInstanceOf<CdrApiClient.RenewClientSecretResult.RenewHttpErrorResponse>(result) { "RenewHttpErrorResponse expected but got $result" }
        assertEquals(HttpStatus.NOT_FOUND.value(), result.code)

        assertEquals(1, apiServerMock.requestCount)
    }

    @Test
    fun `test client credential renewal - status code 500`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
            .setBody(INTERNAL_SERVER_ERROR_PROBLEM_JSON)
        apiServerMock.enqueue(mockResponse)
        apiServerMock.enqueue(mockResponse)
        apiServerMock.enqueue(mockResponse)
        apiServerMock.enqueue(mockResponse)

        val result: CdrApiClient.RenewClientSecretResult = cdrApiClient.renewClientCredential(DEFAULT_TRACE_ID)

        assertInstanceOf<CdrApiClient.RenewClientSecretResult.RenewHttpErrorResponse>(result) { "RenewHttpErrorResponse expected but got $result" }
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), result.code)

        assertEquals(4, apiServerMock.requestCount)
    }

    @Test
    fun `test document upload - status 202`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.ACCEPTED.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .setBody("""{"message": "Upload successful"}""")
        apiServerMock.enqueue(mockResponse)

        val testFile: Path = sourceDir.resolve("test-file.txt").apply { writeText("test content") }

        val result: CdrApiClient.UploadDocumentResult = cdrApiClient.uploadDocument(
            contentType = "application/forumdatenaustausch+xml;charset=UTF-8",
            file = testFile,
            connectorId = "test-connector-id",
            mode = CdrClientConfig.Mode.TEST,
            traceId = DEFAULT_TRACE_ID
        )

        assertInstanceOf<CdrApiClient.UploadDocumentResult.Success>(result) { "CdrApiClient.UploadDocumentResult.Success expected but got $result" }

        val request: RecordedRequest = requireNotNull(apiServerMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }
        assertEquals("POST", request.method)
        assertEquals("/documents", request.path)
        assertEquals("application/forumdatenaustausch+xml;charset=UTF-8", request.headers[HttpHeaders.CONTENT_TYPE])
        assertEquals("test-connector-id", request.headers[CONNECTOR_ID_HEADER])
        assertEquals(CdrClientConfig.Mode.TEST.value, request.headers[CDR_PROCESSING_MODE_HEADER])
        assertEquals(DEFAULT_TRACE_ID, request.headers[AZURE_TRACE_ID_HEADER])
        assertTrue(requireNotNull(request.headers[HttpHeaders.AUTHORIZATION]).startsWith("Bearer "))
        assertTrue(requireNotNull(request.headers[HttpHeaders.AUTHORIZATION]).removePrefix("Bearer ").isNotBlank())

        assertEquals(1, apiServerMock.requestCount)
    }

    @Test
    fun `test document download - status 200`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, "application/forumdatenaustausch+xml;charset=UTF-8")
            .setHeader(PULL_RESULT_ID_HEADER, "test-pull-result-id")
            .setBody("test content")
        apiServerMock.enqueue(mockResponse)

        val result: CdrApiClient.DownloadDocumentResult = cdrApiClient.downloadDocument(
            connectorId = "some-other-connector-id",
            mode = CdrClientConfig.Mode.PRODUCTION,
            traceId = DEFAULT_TRACE_ID
        )

        assertInstanceOf<CdrApiClient.DownloadDocumentResult.Success>(result) { "CdrApiClient.DownloadDocumentResult.Success expected but got $result" }

        assertEquals("test-pull-result-id", result.pullResultId)
        assertTrue(result.file.exists())
        result.file.readText().let { content ->
            assertEquals("test content", content)
        }

        val request: RecordedRequest = requireNotNull(apiServerMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }
        assertEquals("GET", request.method)
        assertEquals("/documents?limit=1", request.path)
        assertEquals("some-other-connector-id", request.headers[CONNECTOR_ID_HEADER])
        assertEquals(CdrClientConfig.Mode.PRODUCTION.value, request.headers[CDR_PROCESSING_MODE_HEADER])
        assertEquals(DEFAULT_TRACE_ID, request.headers[AZURE_TRACE_ID_HEADER])
        assertTrue(requireNotNull(request.headers[HttpHeaders.AUTHORIZATION]).startsWith("Bearer "))
        assertTrue(requireNotNull(request.headers[HttpHeaders.AUTHORIZATION]).removePrefix("Bearer ").isNotBlank())

        assertEquals(1, apiServerMock.requestCount)
    }

    @Test
    fun `test document download - status 204`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.NO_CONTENT.value())
        apiServerMock.enqueue(mockResponse)

        val result: CdrApiClient.DownloadDocumentResult = cdrApiClient.downloadDocument(
            connectorId = "some-other-connector-id",
            mode = CdrClientConfig.Mode.PRODUCTION,
            traceId = DEFAULT_TRACE_ID
        )

        assertInstanceOf<CdrApiClient.DownloadDocumentResult.NoDocumentPending>(result) {
            "CdrApiClient.DownloadDocumentResult.NoDocumentPending expected but got $result"
        }

        assertEquals(1, apiServerMock.requestCount)
    }

    @Test
    fun `test download acknowledgement - status 200`() {
        val mockResponse = MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.ALL_VALUE)
            .setBody("")
        apiServerMock.enqueue(mockResponse)

        val result: CdrApiClient.DownloadDocumentResult = cdrApiClient.acknowledgeDocumentDownload(
            downloadId = "test-pull-result-id",
            connectorId = "some-other-connector-id",
            mode = CdrClientConfig.Mode.PRODUCTION,
            traceId = DEFAULT_TRACE_ID
        )

        assertInstanceOf<CdrApiClient.DownloadDocumentResult.Success>(result) {
            "CdrApiClient.AcknowledgeDownloadResult.Success expected but got $result"
        }

        val request: RecordedRequest = requireNotNull(apiServerMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }
        assertEquals("DELETE", request.method)
        assertEquals("/documents/test-pull-result-id", request.path)
        assertEquals("some-other-connector-id", request.headers[CONNECTOR_ID_HEADER])
        assertEquals(CdrClientConfig.Mode.PRODUCTION.value, request.headers[CDR_PROCESSING_MODE_HEADER])
        assertEquals(DEFAULT_TRACE_ID, request.headers[AZURE_TRACE_ID_HEADER])
        assertTrue(requireNotNull(request.headers[HttpHeaders.AUTHORIZATION]).startsWith("Bearer "))
        assertTrue(requireNotNull(request.headers[HttpHeaders.AUTHORIZATION]).removePrefix("Bearer ").isNotBlank())

        assertEquals(1, apiServerMock.requestCount)
    }

    private companion object {
        private const val DEFAULT_TRACE_ID = "fake_trace_id"
        private const val TEST_CLIENT_ID = "easy-system-test-user-id"
        private const val RENEWED_SECRET = "Placeholder_eWN8Q~MkHkK4vI.97tH2S65AQcP4qBsg2KzBcbSy"
        private const val RENEW_SECRET_RESPONSE = """
            {
                "id": "25944ef9-adc5-47f7-b607-9e85c572e576",
                "displayName": "cdr client #1 by client id",
                "clientId": "$TEST_CLIENT_ID",
                "clientSecret": "$RENEWED_SECRET",
                "notOnOrAfter": "2026-11-13T17:32:00.1851404Z",
                "orgId": "uuid-in-real-life",
                "warnings": []
            }
        """
        private const val INVALID_RENEW_SECRET_RESPONSE = """
            <!DOCTYPE html>
            <html>
              <body>
                <h1>I could be a proxy response header</h1>
                <p>I could be a proxy response message</p>
              </body>
            </html> 
        """
        private const val NOT_FOUND_PROBLEM_JSON = """
            {
                "type": "about:blank",
                "title": "Not found",
                "status": 404,
            }
        """
        private const val INTERNAL_SERVER_ERROR_PROBLEM_JSON = """
            {
                "type": "about:blank",
                "title": "Internal server error",
                "status": 500,
            }
        """

        @TempDir(factory = AlwaysSameTempDirFactory::class)
        @JvmStatic
        @Suppress("unused")
        private lateinit var inflightDirInApplicationTestYaml: Path
    }

}
