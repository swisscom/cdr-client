package com.swisscom.health.des.cdr.client.config

import com.nimbusds.oauth2.sdk.AccessTokenResponse
import com.swisscom.health.des.cdr.client.config.auth.AuthNResponse
import com.swisscom.health.des.cdr.client.config.auth.AuthNState
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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

@ExtendWith(MockKExtension::class)
class OAuth2AuthNServiceTest {

    @MockK
    private lateinit var config: CdrClientConfig

    @StartStop
    private val idpMock = MockWebServer()

    private val retryIoExceptionsTwice = RetryTemplate.builder()
        .maxAttempts(MAX_ATTEMPTS)
        .fixedBackoff(Duration.ofMillis(10))
        .retryOn(IOException::class.java)
        .build()

    private val testScopes = mutableListOf<CoroutineScope>()
    private val testServices = mutableListOf<OAuth2AuthNService>()

    @BeforeEach
    fun setUp() {
        every { config.fileSynchronizationEnabled } returns FileSynchronization.ENABLED
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
        every { config.denyRetryAttempts } returns 1
        every { config.authRefreshBeforeExpiry } returns Duration.ofSeconds(1)
        every { config.authRetry } returns CdrClientConfig.RetryPolicy(
            initialDelay = Duration.ofMillis(10),
            backoffMultiplier = 2.0,
            maxDelay = Duration.ofSeconds(1),
        )
    }

    @AfterEach
    fun tearDown() {
        testServices.forEach { it.cleanup() }
        testServices.clear()
        testScopes.forEach { it.cancel() }
        testScopes.clear()
    }

    @Test
    fun `getAccessToken is pure reader when file synchronization is disabled`() {
        every { config.fileSynchronizationEnabled } returns FileSynchronization.DISABLED
        val authNService = newService()

        val authNResponse: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }

        assertInstanceOf<AuthNResponse.NotAuthenticated>(authNResponse)
        assertEquals(0, idpMock.requestCount)
        assertEquals(AuthNState.UNAUTHENTICATED, authNService.currentAuthNStateNonBlocking())
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `auth manager starts eagerly and acquires token before readers ask for it`() {
        idpMock.enqueue(successTokenResponse(expiresAtEpochSecond = Instant.now().epochSecond + 120))

        val authNService = newService()

        waitForAuthState(authNService, AuthNState.AUTHENTICATED)
        val authNResponse: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }
        val serverSideRequest: RecordedRequest = requireNotNull(idpMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }

        assertEquals(1, idpMock.requestCount)
        assertEquals("/fake-tenant-id/oauth2/v2.0/token", serverSideRequest.target)
        assertInstanceOf<AuthNResponse.Success>(authNResponse)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `cached token readers do not trigger additional token requests`() {
        idpMock.enqueue(successTokenResponse(expiresAtEpochSecond = Instant.now().epochSecond + 120))
        idpMock.enqueue(denyTokenResponse())

        val authNService = newService()
        waitForAuthState(authNService, AuthNState.AUTHENTICATED)

        val authNResponse1: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }
        val authNResponse2: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }
        requireNotNull(idpMock.takeRequest(1, TimeUnit.SECONDS)) { "No request received" }

        assertEquals(1, idpMock.requestCount)
        assertTrue(authNResponse1 === authNResponse2)
        assertInstanceOf<AuthNResponse.Success>(authNResponse1)
        val accessTokenResponse: AccessTokenResponse = authNResponse1.response
        assertTrue(accessTokenResponse.indicatesSuccess())
        assertEquals(ACCESS_TOKEN, accessTokenResponse.tokens.accessToken.value)
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.SECONDS)
    fun `auth manager refreshes token before expiry without request trigger`() {
        idpMock.enqueue(successTokenResponse(expiresAtEpochSecond = Instant.now().epochSecond + 2, accessToken = "first-token"))
        idpMock.enqueue(successTokenResponse(expiresAtEpochSecond = Instant.now().epochSecond + 120, accessToken = "second-token"))

        val authNService = newService()

        waitForRequestCount(2)
        val authNResponse: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }

        assertEquals(AuthNState.AUTHENTICATED, authNService.currentAuthNStateNonBlocking())
        val successResponse = assertInstanceOf<AuthNResponse.Success>(authNResponse)
        assertEquals("second-token", successResponse.response.tokens.accessToken.value)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `auth manager resolves expiry from ext_expires_in when expires_on is absent`() {
        val extExpiresIn = 60L
        idpMock.enqueue(
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(
                    Headers.Builder()
                        .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                        .build()
                )
                .body(
                    successTokenBody(
                        expiresAtEpochSecond = null,
                        accessToken = "ext-token",
                        expiresIn = extExpiresIn,
                        extExpiresIn = extExpiresIn,
                    )
                )
                .build()
        )

        val authNService = newService()

        waitForAuthState(authNService, AuthNState.AUTHENTICATED)
        val successResponse = assertInstanceOf<AuthNResponse.Success>(authNService.getAccessToken())
        assertEquals("ext-token", successResponse.response.tokens.accessToken.value)
        assertTrue(successResponse.expiresAtEpochSecond > Instant.now().epochSecond)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `idp deny response transitions via reauthenticating to denied`() {
        every { config.denyRetryAttempts } returns 1
        every { config.authRetry } returns CdrClientConfig.RetryPolicy(
            initialDelay = Duration.ZERO,
            backoffMultiplier = 2.0,
            maxDelay = Duration.ofSeconds(1),
        )

        idpMock.enqueue(denyTokenResponse())
        idpMock.enqueue(denyTokenResponse())

        val authNService = newService()

        waitForAuthState(authNService, AuthNState.DENIED)
        val authNResponse: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }

        assertEquals(2, idpMock.requestCount)
        assertInstanceOf<AuthNResponse.Deny>(authNResponse)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `idp deny then retry success transitions to authenticated`() {
        every { config.denyRetryAttempts } returns 1
        every { config.authRetry } returns CdrClientConfig.RetryPolicy(
            initialDelay = Duration.ZERO,
            backoffMultiplier = 2.0,
            maxDelay = Duration.ofSeconds(1),
        )

        idpMock.enqueue(denyTokenResponse())
        idpMock.enqueue(successTokenResponse(expiresAtEpochSecond = Instant.now().epochSecond + 120))

        val authNService = newService()

        waitForAuthState(authNService, AuthNState.AUTHENTICATED)
        val authNResponse: AuthNResponse = assertDoesNotThrow { authNService.getAccessToken() }

        assertEquals(2, idpMock.requestCount)
        assertInstanceOf<AuthNResponse.Success>(authNResponse)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `idp deny retries configured number of attempts before denied`() {
        every { config.denyRetryAttempts } returns 3
        every { config.authRetry } returns CdrClientConfig.RetryPolicy(
            initialDelay = Duration.ZERO,
            backoffMultiplier = 2.0,
            maxDelay = Duration.ofSeconds(1),
        )

        repeat(4) {
            idpMock.enqueue(denyTokenResponse())
        }

        val authNService = newService()

        waitForAuthState(authNService, AuthNState.DENIED)

        repeat(4) {
            requireNotNull(idpMock.takeRequest(1, TimeUnit.SECONDS)) { "Expected request #${it + 1} was not sent" }
        }

        assertEquals(4, idpMock.requestCount)
        assertInstanceOf<AuthNResponse.Deny>(authNService.getAccessToken())
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `idp deny retries disabled keeps denied without launching extra reauth request`() {
        every { config.denyRetryAttempts } returns 0

        idpMock.enqueue(denyTokenResponse())

        val authNService = newService()

        waitForAuthState(authNService, AuthNState.DENIED)

        assertEquals(1, idpMock.requestCount)
        assertInstanceOf<AuthNResponse.Deny>(authNService.getAccessToken())
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `retryable startup failure remains reauthenticating until background retry succeeds`() {
        every { config.authRetry } returns CdrClientConfig.RetryPolicy(
            initialDelay = Duration.ZERO,
            backoffMultiplier = 2.0,
            maxDelay = Duration.ofSeconds(1),
        )

        idpMock.enqueue(
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(
                    Headers.Builder()
                        .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                        .build()
                )
                .body(successTokenBody(expiresAtEpochSecond = Instant.now().epochSecond + 120))
                .onRequestStart(SocketEffect.CloseSocket())
                .build()
        )
        idpMock.enqueue(successTokenResponse(expiresAtEpochSecond = Instant.now().epochSecond + 120))

        val authNService = newService()

        waitForAuthState(authNService, AuthNState.AUTHENTICATED)
        assertEquals(AuthNState.AUTHENTICATED, authNService.currentAuthNStateNonBlocking())
        assertFalse(authNService.getAccessToken() is AuthNResponse.Reauthenticating)
    }


    private fun newService(): OAuth2AuthNService =
        OAuth2AuthNService(
            config = config,
            retryIoErrors = retryIoExceptionsTwice,
            proxy = null,
        ).also { testServices += it }

    private fun successTokenResponse(expiresAtEpochSecond: Long, accessToken: String = ACCESS_TOKEN): MockResponse =
        MockResponse.Builder()
            .code(HttpStatus.OK.value())
            .headers(
                Headers.Builder()
                    .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .build()
            )
            .body(successTokenBody(expiresAtEpochSecond, accessToken))
            .build()

    private fun successTokenBody(
        expiresAtEpochSecond: Long?,
        accessToken: String = ACCESS_TOKEN,
        expiresIn: Long? = null,
        extExpiresIn: Long? = null,
    ): String {
        val resolvedExpiresIn = expiresIn ?: expiresAtEpochSecond?.let { (it - Instant.now().epochSecond).coerceAtLeast(1) } ?: 60L
        val resolvedExtExpiresIn = extExpiresIn ?: resolvedExpiresIn
        return """
            {
                "access_token": "$accessToken",
                "token_type": "Bearer",
                "not_before": 1760436404,
                "expires_in": $resolvedExpiresIn,
                "ext_expires_in": $resolvedExtExpiresIn,
                "resource": "f1eb5a11-b12c-413c-82a4-2fabcb08480a"
            }
        """.trimIndent()
    }

    private fun denyTokenResponse(): MockResponse =
        MockResponse.Builder()
            .code(HttpStatus.BAD_REQUEST.value())
            .headers(
                Headers.Builder()
                    .add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .build()
            )
            .body(ERROR_TOKEN_RESPONSE)
            .build()

    private fun waitForAuthState(
        authNService: OAuth2AuthNService,
        targetState: AuthNState,
        maxWaitMillis: Long = 1500L,
    ) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMillis)
        var state = authNService.currentAuthNStateNonBlocking()
        while (state != targetState && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10)
            state = authNService.currentAuthNStateNonBlocking()
        }
        assertEquals(targetState, state)
    }

    private fun waitForRequestCount(expectedRequestCount: Int, maxWaitMillis: Long = 3000L) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMillis)
        while (idpMock.requestCount < expectedRequestCount && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10)
        }
        assertEquals(expectedRequestCount, idpMock.requestCount)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val ACCESS_TOKEN = "test-access-token"
        const val ERROR_TOKEN_RESPONSE = """
            {
                "error": "invalid_client",
                "error_description": "AADB2C90081: The specified client_secret does not match the expected value for this client."
            }
        """
    }
}
