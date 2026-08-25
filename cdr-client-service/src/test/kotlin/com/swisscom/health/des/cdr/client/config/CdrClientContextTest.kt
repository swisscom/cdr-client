package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNResponse
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
internal class CdrClientContextTest {

    @MockK
    private lateinit var authNService: OAuth2AuthNService

    @StartStop
    private val server = MockWebServer()

    @Test
    fun `okhttp interceptor returns synthetic 503 while reauthenticating`() {
        every { authNService.getAccessToken() } returns AuthNResponse.Reauthenticating

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
            assertEquals("Authentication re-establishment in progress", response.message)
            assertEquals("Authentication is being re-established.", response.body.string())
        }
        assertEquals(0, server.requestCount)
    }
}
