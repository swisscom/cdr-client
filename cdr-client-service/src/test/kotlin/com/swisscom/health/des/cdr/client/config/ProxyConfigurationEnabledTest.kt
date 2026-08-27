package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.handler.CdrApiClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Integration test that verifies THE ACTUAL APPLICATION routes all HTTP traffic through a configured proxy.
 *
 * This test:
 * 1. Starts MockWebServer as a proxy BEFORE Spring context initialization
 * 2. Configures the application with the proxy URL pointing to the mock server
 * 3. Starts the application with proxy configuration
 * 4. Makes actual calls using the application's beans (OkHttpClient, OAuth2AuthNService, CdrApiClient)
 * 5. Verifies that the proxy server received ALL the requests
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.main.lazy-initialization=true",
        "spring.jmx.enabled=false",
        "client.idp-credentials.renew-credential=false",
        "client.retry-template.retries=1",
        "client.retry-template.initial-delay=10ms",
        "client.retry-template.multiplier=1.1",
        "client.retry-template.max-delay=100ms",
    ]
)
@ActiveProfiles("test", "noPollingUploadScheduler", "noEventTriggerUploadScheduler", "noDownloadScheduler")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("integration-test")
internal class ProxyConfigurationEnabledTest {

    @Autowired
    private lateinit var okHttpClient: OkHttpClient

    @Autowired
    private lateinit var oauth2AuthNService: OAuth2AuthNService

    @Autowired
    private lateinit var proxy: Optional<Proxy>

    @Autowired
    private lateinit var cdrApiClient: CdrApiClient

    @Autowired
    private lateinit var config: CdrClientConfig

    @Test
    fun `application ProxyConfiguration bean is Enabled with correct proxy settings`() {
        // Given: Application started with proxy-url configured
        // Then: ProxyConfiguration bean should be Enabled
        assertTrue(proxy.isPresent)
        val address = (proxy.get().address() as InetSocketAddress)
        assertEquals(mockProxyServer.hostName, address.hostString)
        assertEquals(mockProxyServer.port, address.port)
    }

    @Test
    fun `application OkHttpClient routes HTTP requests through configured proxy`() {
        // Given: Application's OkHttpClient bean with proxy configured
        // Note: OkHttpClient has an auth interceptor that requests OAuth2 token first
        mockProxyServer.enqueue(
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
                .body(OAUTH2_TOKEN_RESPONSE)
                .build()
        )
        mockProxyServer.enqueue(
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
                .body("""{"result":"success"}""")
                .build()
        )

        // When: Making HTTP request using the application's OkHttpClient
        val request = okhttp3.Request.Builder()
            .url("http://${mockApiServer.hostName}:${mockApiServer.port}/api/documents")
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()

        // Then: Both OAuth2 token request AND HTTP request should have gone through the proxy
        // First request: OAuth2 token (from auth interceptor)
        val oauth2Request: RecordedRequest? = mockProxyServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(oauth2Request, "APPLICATION's auth interceptor did NOT route OAuth2 request through proxy!")
        oauth2Request?.let {
            assertTrue(it.target.contains("token")) {
                "Expected OAuth2 token request through proxy, but got: ${it.target}"
            }
        }

        // Second request: Actual HTTP request
        val proxyRequest: RecordedRequest? = mockProxyServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(proxyRequest, "APPLICATION's OkHttpClient did NOT route request through proxy!")
        proxyRequest?.let {
            assertTrue(it.target.contains("documents")) {
                "Expected request to /api/documents through proxy, but got: ${it.target}"
            }
        }
        assertEquals(HttpStatus.OK.value(), response.code)
    }

    @Test
    fun `application OAuth2AuthNService routes token requests through configured proxy`() {
        // Given: Application's OAuth2AuthNService with proxy configured
        mockProxyServer.enqueue(
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
                .body(OAUTH2_TOKEN_RESPONSE)
                .build()
        )

        // When: OAuth2 service requests an access token
        try {
            oauth2AuthNService.getNewAccessToken(
                config.idpCredentials,
                java.net.URI("http://${mockIdpServer.hostName}:${mockIdpServer.port}/oauth2/token").toURL(),
                shouldRetry = false
            )
        } catch (_: Exception) {
            // May fail due to response parsing, but we verify proxy was called
        }

        // Then: The token request should have gone through the proxy
        val proxyRequest: RecordedRequest? = mockProxyServer.takeRequest(5, TimeUnit.SECONDS)

        assertNotNull(proxyRequest, "APPLICATION's OAuth2AuthNService did NOT route request through proxy!")
        proxyRequest?.let {
            assertTrue(it.target.contains("token")) {
                "Expected OAuth2 token request through proxy, but got: ${it.target}"
            }
        }
    }

    @Test
    fun `application CdrApiClient routes API calls through configured proxy`() {
        // Given: Application's CdrApiClient with proxy configured
        // Note: OkHttpClient has an auth interceptor that requests OAuth2 token first
        mockProxyServer.enqueue(
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
                .body(OAUTH2_TOKEN_RESPONSE)
                .build()
        )
        mockProxyServer.enqueue(
            MockResponse.Builder()
                .code(HttpStatus.OK.value())
                .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
                .body(RENEW_SECRET_RESPONSE)
                .build()
        )

        // When: CdrApiClient makes API call
        cdrApiClient.renewClientCredential("test-trace-id")

        // Then: Both OAuth2 token request AND API call should have gone through the proxy
        // First request: OAuth2 token (from auth interceptor)
        val oauth2Request: RecordedRequest? = mockProxyServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(oauth2Request, "APPLICATION's auth interceptor did NOT route OAuth2 request through proxy!")
        oauth2Request?.let {
            assertTrue(it.target.contains("token")) {
                "Expected OAuth2 token request through proxy, but got: ${it.target}"
            }
        }

        // Second request: Actual client-credentials API call
        val apiRequest: RecordedRequest? = mockProxyServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(apiRequest, "APPLICATION's CdrApiClient did NOT route request through proxy!")
        apiRequest?.let {
            assertTrue(it.target.contains("client-credentials")) {
                "Expected client-credentials request through proxy, but got: ${it.target}"
            }
        }
    }

    @Test
    fun `application routes multiple HTTP calls through proxy`() {
        // Given: Application with proxy configured
        // Note: OkHttpClient may make OAuth2 token requests via auth interceptor, so enqueue enough responses
        repeat(5) {
            mockProxyServer.enqueue(MockResponse.Builder().code(200).body("ok").build())
        }

        // When: Making multiple HTTP requests using application's OkHttpClient
        repeat(3) { i ->
            try {
                val request = okhttp3.Request.Builder()
                    .url("http://${mockApiServer.hostName}:${mockApiServer.port}/api/call$i")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute()
            } catch(_: Exception) {
                // Ignore exceptions, we only care if requests went through proxy
            }
        }

        // Then: All requests should have gone through the proxy
        // Note: May be more than 3 due to OAuth2 token requests from auth interceptor
        val requestCount = mockProxyServer.requestCount
        assertTrue(requestCount >= 3) {
            "Expected at least 3 requests through proxy, but got $requestCount"
        }

        // Verify that our API calls went through proxy (may have OAuth2 requests mixed in)
        val allRequests = mutableListOf<RecordedRequest>()
        repeat(requestCount) {
            mockProxyServer.takeRequest(1, TimeUnit.SECONDS)?.let { allRequests.add(it) }
        }

        // Count how many of our actual API calls went through
        val apiCallCount = allRequests.count { it.target.contains("call") }
        assertTrue(apiCallCount >= 3) {
            "Expected at least 3 API calls through proxy, but got $apiCallCount"
        }
    }

    companion object {
        private lateinit var mockProxyServer: MockWebServer
        private lateinit var mockApiServer: MockWebServer
        private lateinit var mockIdpServer: MockWebServer

        @JvmStatic
        @DynamicPropertySource
        @Suppress("UNUSED_PARAMETER")
        fun properties(registry: DynamicPropertyRegistry) {
            // Start mock servers BEFORE Spring context initialization
            mockProxyServer = MockWebServer()
            mockProxyServer.start()

            mockApiServer = MockWebServer()
            mockApiServer.start()

            mockIdpServer = MockWebServer()
            mockIdpServer.start()

            // Configure application with proxy pointing to our mock server
            registry.add("client.proxy-config.url") { "http://${mockProxyServer.hostName}:${mockProxyServer.port}" }
            registry.add("client.proxy-config.username") { "test-proxy-user" }
            registry.add("client.proxy-config.password") { "test-proxy-pass" }
            registry.add("client.idp-endpoint") { "http://${mockIdpServer.hostName}:${mockIdpServer.port}/oauth2/token" }
            registry.add("client.cdr-api.host") { mockApiServer.hostName }
            registry.add("client.cdr-api.port") { mockApiServer.port }
            registry.add("client.credential-api.host") { mockApiServer.hostName }
            registry.add("client.credential-api.port") { mockApiServer.port }
        }

        @JvmStatic
        @BeforeAll
        fun setupClass() {
            // MockWebServers are started in @DynamicPropertySource
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            mockProxyServer.close()
            mockApiServer.close()
            mockIdpServer.close()
        }

        private const val OAUTH2_TOKEN_RESPONSE = """{
            "access_token": "test-access-token",
            "token_type": "Bearer",
            "expires_in": 3600,
            "ext_expires_in": 9999999999
        }"""

        private const val RENEW_SECRET_RESPONSE = """{
            "clientId": "test-client-id",
            "clientSecret": "new-test-secret",
            "expiresAt": "2026-12-31T23:59:59Z"
        }"""
    }
}
