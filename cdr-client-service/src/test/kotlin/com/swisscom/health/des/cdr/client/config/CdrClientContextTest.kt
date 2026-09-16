package com.swisscom.health.des.cdr.client.config

import com.nimbusds.oauth2.sdk.AccessTokenResponse
import com.swisscom.health.des.cdr.client.config.auth.AuthNResponse
import com.swisscom.health.des.cdr.client.config.auth.AuthStateSnapshot
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

@ExtendWith(MockKExtension::class)
internal class CdrClientContextTest {

    @MockK
    private lateinit var authNService: OAuth2AuthNService

    @StartStop
    private val server = MockWebServer()

    @Test
    fun `okhttp interceptor returns synthetic 503 while reauthenticating`() {
        every { authNService.currentStateSnapshot() } returns AuthStateSnapshot(response = AuthNResponse.Authenticating)

        val client = CdrClientContext().okHttpClient(
            builder = OkHttpClient.Builder(),
            oAuth2AuthNService = authNService,
            timeout = 1000,
            readTimeout = 1000,
            proxy = null,
            proxyCredentials = null,
        )

        val request = Request.Builder()
            .url(server.url("/api/test"))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(503, response.code)
            assertEquals("Authentication in progress", response.message)
            assertEquals("Authentication in progress.", response.body.string())
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `okhttp interceptor blocks outbound request while unauthenticated`() {
        every { authNService.currentStateSnapshot() } returns AuthStateSnapshot(response = AuthNResponse.NotAuthenticated)

        val client = CdrClientContext().okHttpClient(
            builder = OkHttpClient.Builder(),
            oAuth2AuthNService = authNService,
            timeout = 1000,
            readTimeout = 1000,
            proxy = null,
            proxyCredentials = null,
        )

        val request = Request.Builder()
            .url(server.url("/api/test"))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(503, response.code)
            assertEquals("Authentication unavailable", response.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `okhttp interceptor returns 401 while credentials are denied`() {
        every { authNService.currentStateSnapshot() } returns AuthStateSnapshot(
            response = AuthNResponse.Deny(WrongCredentialsException("denied"))
        )

        val client = CdrClientContext().okHttpClient(
            builder = OkHttpClient.Builder(),
            oAuth2AuthNService = authNService,
            timeout = 1000,
            readTimeout = 1000,
            proxy = null,
            proxyCredentials = null,
        )

        val request = Request.Builder()
            .url(server.url("/api/test"))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(401, response.code)
            assertEquals("Authentication denied", response.message)
            assertEquals("Authentication credentials were rejected.", response.body.string())
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `okhttp interceptor triggers reauthentication on downstream 401`() {
        every { authNService.forceReauthentication(any()) } just runs
        val accessTokenResponse = mockk<AccessTokenResponse>(relaxed = true)
        every { accessTokenResponse.tokens.accessToken.value } returns "test-access-token"
        every { authNService.currentStateSnapshot() } returns AuthStateSnapshot(
            response = AuthNResponse.Success(
                response = accessTokenResponse,
                expiresAtEpochSecond = Long.MAX_VALUE,
            ),
            managerJob = null,
        )

        server.enqueue(
            MockResponse.Builder()
                .code(HttpStatus.UNAUTHORIZED.value())
                .headers(
                    Headers.Builder()
                        .add(HttpHeaders.CONTENT_TYPE, "application/json")
                        .build()
                )
                .body("{\"error\":\"unauthorized\"}")
                .build()
        )

        val client = CdrClientContext().okHttpClient(
            builder = OkHttpClient.Builder(),
            oAuth2AuthNService = authNService,
            timeout = 1000,
            readTimeout = 1000,
            proxy = null,
            proxyCredentials = null,
        )

        val request = Request.Builder()
            .url(server.url("/api/protected"))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(401, response.code)
        }

        verify(exactly = 1) {
            authNService.forceReauthentication(match { it.contains("401") })
        }
    }

    @Test
    fun `okhttp interceptor injects bearer token for authenticated requests`() {
        val accessTokenResponse = mockk<AccessTokenResponse>(relaxed = true)
        every { accessTokenResponse.tokens.accessToken.value } returns "test-access-token"
        every { authNService.currentStateSnapshot() } returns AuthStateSnapshot(
            response = AuthNResponse.Success(
                response = accessTokenResponse,
                expiresAtEpochSecond = Long.MAX_VALUE,
            ),
            managerJob = null,
        )

        server.enqueue(
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(
                    Headers.Builder()
                        .add(HttpHeaders.CONTENT_TYPE, "application/json")
                        .build()
                )
                .body("{\"ok\":true}")
                .build()
        )

        val client = CdrClientContext().okHttpClient(
            builder = OkHttpClient.Builder(),
            oAuth2AuthNService = authNService,
            timeout = 1000,
            readTimeout = 1000,
            proxy = null,
            proxyCredentials = null,
        )

        client.newCall(
            Request.Builder()
                .url(server.url("/api/protected"))
                .build()
        ).execute().use { response ->
            assertEquals(200, response.code)
        }

        val request = requireNotNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("Bearer test-access-token", request.headers[HttpHeaders.AUTHORIZATION])
        assertTrue(request.headers[HttpHeaders.AUTHORIZATION]?.contains("******") != true)
    }
}
