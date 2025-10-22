package com.swisscom.health.des.cdr.client.config

import com.nimbusds.oauth2.sdk.AccessTokenResponse
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNResponse
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.retry.support.RetryTemplate
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime


@ExtendWith(MockKExtension::class)
@MockKExtension.CheckUnnecessaryStub
class OAuth2AuthNServiceTest {

    private lateinit var authNService: OAuth2AuthNService

    private val retryIoExceptionsTwice = RetryTemplate.builder()
        .maxAttempts(MAX_ATTEMPTS)
        .fixedBackoff(Duration.ofMillis(10))
        .retryOn(IOException::class.java)
        .build()

    @MockK
    private lateinit var config: CdrClientConfig

    @StartStop
    private val idpMock = MockWebServer()

    @OptIn(ExperimentalTime::class)
    @BeforeEach
    fun setUp() {
        every { config.idpCredentials } returns IdpCredentials(
            tenantId = TenantId("fake-tenant-id"),
            clientId = ClientId("fake-client-id"),
            clientSecret = ClientSecret("fake-client-secret"),
            scope = Scope("fake-scope"),
            renewCredential = RenewCredential(false),
            maxCredentialAge = Duration.ofDays(365),
            lastCredentialRenewalTime = LastCredentialRenewalTime(Instant.now()),
        )
        every { config.idpEndpoint } returns URI("http://${idpMock.hostName}:${idpMock.port}/${config.idpCredentials.tenantId.id}/oauth2/v2.0/token").toURL()

        authNService = OAuth2AuthNService(config = config, retryIoErrors = retryIoExceptionsTwice)
    }

    @Test
    fun `no token in cache - get new token`() {
        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(
                Headers.Builder()
                    .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .build()
            )
            .body(SUCCESS_TOKEN_RESPONSE)
            .build()
        idpMock.enqueue(mockResponse)

        assertEquals(OAuth2AuthNService.AuthNState.UNAUTHENTICATED, authNService.currentAuthNState())

        val authNResponse: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }
        val serverSideRequest: RecordedRequest = requireNotNull(idpMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }

        assertEquals(1, idpMock.requestCount)
        assertEquals("/fake-tenant-id/oauth2/v2.0/token", serverSideRequest.target)
        assertInstanceOf<AuthNResponse.Success>(authNResponse)
        val accessTokenResponse: AccessTokenResponse = authNResponse.response
        assertTrue(accessTokenResponse.indicatesSuccess())
        assertEquals(ACCESS_TOKEN, accessTokenResponse.tokens.accessToken.value)

        assertEquals(OAuth2AuthNService.AuthNState.AUTHENTICATED, authNService.currentAuthNState())
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `token in cache and token has not expired - use cached token`() {
        // first request to get a token into the cache
        MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(
                Headers.Builder()
                    .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .build()
            )
            .body(SUCCESS_TOKEN_RESPONSE)
            .build().also {
                idpMock.enqueue(it)
            }
        // a second call should never be made by the test, i.e. this error should never be returned
        MockResponse.Builder()
            .code(HttpStatus.BAD_REQUEST.value())
            .headers(
                Headers.Builder()
                    .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .build()
            )
            .body(ERROR_TOKEN_RESPONSE)
            .build().also {
                idpMock.enqueue(it)
            }

        val constantTimeClock = mockk<Clock>()
        every { constantTimeClock.now().epochSeconds } returns 1760437304L // IdP response `expires_on` - 1

        assertEquals(OAuth2AuthNService.AuthNState.UNAUTHENTICATED, authNService.currentAuthNState())

        authNService = OAuth2AuthNService(config = config, clock = constantTimeClock, retryIoErrors = retryIoExceptionsTwice)

        val authNResponse1: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }
        val authNResponse2: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }
        val serverSideRequest: RecordedRequest = requireNotNull(idpMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }

        assertEquals(1, idpMock.requestCount)
        assertEquals("/fake-tenant-id/oauth2/v2.0/token", serverSideRequest.target)
        assertTrue(authNResponse1 === authNResponse2)
        assertInstanceOf<AuthNResponse.Success>(authNResponse1)
        val accessTokenResponse: AccessTokenResponse = authNResponse1.response
        assertTrue(accessTokenResponse.indicatesSuccess())
        assertEquals(ACCESS_TOKEN, accessTokenResponse.tokens.accessToken.value)

        assertEquals(OAuth2AuthNService.AuthNState.AUTHENTICATED, authNService.currentAuthNState())
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `health graph token in cache but token has expired - renew token`() {
        // first request to get a token into the cache
        MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(
                Headers.Builder()
                    .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .build()
            )
            .body(SUCCESS_TOKEN_RESPONSE)
            .build().also {
                idpMock.enqueue(it) // initial response
                idpMock.enqueue(it) // "renewal" response
            }

        val constantTimeClock = mockk<Clock>()
        every { constantTimeClock.now().epochSeconds } returns 1760437306L // IdP response `expires_on` + 1

        assertEquals(OAuth2AuthNService.AuthNState.UNAUTHENTICATED, authNService.currentAuthNState())

        authNService = OAuth2AuthNService(config = config, clock = constantTimeClock, retryIoErrors = retryIoExceptionsTwice)

        val authNResponse1: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }
        val authNResponse2: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }
        val serverSideRequest: RecordedRequest = requireNotNull(idpMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }

        assertEquals(2, idpMock.requestCount)
        assertEquals("/fake-tenant-id/oauth2/v2.0/token", serverSideRequest.target)
        assertFalse(authNResponse1 === authNResponse2)
        assertInstanceOf<AuthNResponse.Success>(authNResponse2)
        val accessTokenResponse: AccessTokenResponse = authNResponse2.response
        assertTrue(accessTokenResponse.indicatesSuccess())
        assertEquals(ACCESS_TOKEN, accessTokenResponse.tokens.accessToken.value)

        assertEquals(OAuth2AuthNService.AuthNState.AUTHENTICATED, authNService.currentAuthNState())
    }

    @Test
    fun `idp error response - authN denied`() {
        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.BAD_REQUEST.value())
            .headers(
                Headers.Builder()
                    .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .build()
            )
            .body(ERROR_TOKEN_RESPONSE)
            .build()
        idpMock.enqueue(mockResponse)

        assertEquals(OAuth2AuthNService.AuthNState.UNAUTHENTICATED, authNService.currentAuthNState())

        val authNResponse: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }
        val serverSideRequest: RecordedRequest = requireNotNull(idpMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }

        assertEquals(1, idpMock.requestCount)
        assertEquals("/fake-tenant-id/oauth2/v2.0/token", serverSideRequest.target)
        assertInstanceOf<AuthNResponse.Deny>(authNResponse)
        val wrappedException: WrongCredentialsException = authNResponse.error
        assertTrue(wrappedException.message!!.startsWith("Failed to login; client id: 'ClientId(id=fake-client-id)'"))

        assertEquals(OAuth2AuthNService.AuthNState.DENIED, authNService.currentAuthNState())
    }

    @Test
    fun `io exception - authN retryable`() {
        val mockResponse = MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(
                Headers.Builder()
                    .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .build()
            )
            .body(SUCCESS_TOKEN_RESPONSE)
            .onRequestStart(SocketEffect.CloseSocket()) // makes request fail with an IOException
            .build()
        idpMock.enqueue(mockResponse)
        idpMock.enqueue(mockResponse)
        idpMock.enqueue(mockResponse)
        idpMock.enqueue(mockResponse)
        idpMock.enqueue(mockResponse)
        idpMock.enqueue(mockResponse)

        assertEquals(OAuth2AuthNService.AuthNState.UNAUTHENTICATED, authNService.currentAuthNState())

        val authNResponse: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }

        // we have to compare MAX_ATTEMPTS * 2 because nimbus makes two requests per failed attempt: the original request, and
        // then they try to retrieve the response code in a best-effort way, which makes MockWebServer count another request
        assertEquals(MAX_ATTEMPTS * 2, idpMock.requestCount)
        assertInstanceOf<AuthNResponse.RetryableFailure>(authNResponse)
        val wrappedException: IOException = assertInstanceOf<IOException>(authNResponse.error)
        assertEquals("Error writing to server", wrappedException.message)

        assertEquals(OAuth2AuthNService.AuthNState.RETRYABLE_FAILURE, authNService.currentAuthNState())
    }

    private companion object {
        const val MAX_ATTEMPTS = 3

        const val ACCESS_TOKEN =
            "eyJhbGciOiJSUzI1NiIsImtpZCI6IkhlLWxhOHVrRVNtLVhOZmxDeEFGb3BCbnVMQ1pheXBBODRQLTVaV0ljT1kiLCJ0eXAiOiJKV1QifQ.eyJhdWQiOiJmMWViNWExMS1iMTJj" +
                    "LTQxM2MtODJhNC0yZmFiY2IwODQ4MGEiLCJpc3MiOiJodHRwczovL2Rldi5pZGVudGl0eS5oZWFsdGguc3dpc3Njb20uY2gvZjVhOTlmOGQtZGNhNi00MTNjLWJhMz" +
                    "YtOWUyM2I0NzIwOTMwL3YyLjAvIiwiZXhwIjoxNzYwNDM3MzA0LCJuYmYiOjE3NjA0MzY0MDQsInN1YiI6ImI5ZjFhYzA0LTFiMzMtNDIxNC1iNzgxLTE1OGVlMzgyZ" +
                    "TAwOCIsInRpZCI6ImY1YTk5ZjhkLWRjYTYtNDEzYy1iYTM2LTllMjNiNDcyMDkzMCIsInRmcCI6IkIyQ18xQV9IZWFsdGhHcmFwaCIsImNvcnJlbGF0aW9uSWQiOiIzZ" +
                    "mUyYjYyYi1kNDBlLTQ2NmItOTYxMS1jZjllYmE0ODgxZjIiLCJzY3AiOiJIZWFsdGhHcmFwaC5SZWFkLkFsbCIsImF6cGFjciI6IjEiLCJvaWQiOiI3Mjk2NGFkNy05Y" +
                    "2ExLTQ4YzgtOGEyNi1iMTQ3YTNkM2E2ZDQiLCJ2ZXIiOiIyLjAiLCJhenAiOiJiOWYxYWMwNC0xYjMzLTQyMTQtYjc4MS0xNThlZTM4MmUwMDgiLCJpYXQiOjE3NjA0Mz" +
                    "Y0MDR9.bwaRRjanFfNHT5FJQV-POnc-wOW5SxLRqivjLiyW-GOFx0YgV9PwGR7j0d_QdkVTf-MGvqXUNeY6miy70s959P1l52qa4H4QXNM4trK8DaPr1-pqPRIV_xEStR" +
                    "_0FS-45Btd6TdOjjqKDZSU3Y9Xc0SQSYCbWFzf4YFqC2TRUlUI_ZdB_g8PdOZkyJeh3feJI9vn0vZZR4sj8aoD4xGcKMOAI1shFMxNU4pKFFRMIqwkGuvxcUAKu-qwdsV" +
                    "b68X4NierOCWqaNACB2OWA29tJahOhOk_LhNlxB4kMoPWNf3XqFCN5bKPle_psheNaiL-4bZ_UAQnctgARzj7KDIG2A"
        const val SUCCESS_TOKEN_RESPONSE = """
            {
                "access_token": "$ACCESS_TOKEN",
                "token_type": "Bearer",
                "not_before": 1760436404,
                "expires_in": 900,
                "expires_on": 1760437304,
                "resource": "f1eb5a11-b12c-413c-82a4-2fabcb08480a"
            }
        """

        // delivered with HTTP 400 Bad Request
        const val ERROR_TOKEN_RESPONSE = """
            {
                "error": "invalid_client",
                "error_description": "AADB2C90081: The specified client_secret does not match the expected value for this client. Please correct the client_secret and try again.\r\nCorrelation ID: 4e846c10-2005-4d0c-b1b3-468f3862dd06\r\nTimestamp: 2025-10-14 10:08:00Z\r\n"
            }
        """
    }
}
