package com.swisscom.health.des.cdr.client.config.auth

import com.nimbusds.oauth2.sdk.AccessTokenResponse
import com.nimbusds.oauth2.sdk.AuthorizationGrant
import com.nimbusds.oauth2.sdk.ClientCredentialsGrant
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.TokenResponse
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.http.HTTPResponse
import com.nimbusds.oauth2.sdk.id.ClientID
import com.swisscom.health.des.cdr.client.config.IdpCredentials
import com.swisscom.health.des.cdr.client.config.WrongCredentialsException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.retry.support.RetryTemplate
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

private val logger = KotlinLogging.logger {}

internal class OAuth2TokenClient(
    private val retryIoErrors: RetryTemplate,
    private val proxy: Proxy?,
    private val authTiming: OAuth2AuthNTiming,
    private val config: TokenClientConfig = TokenClientConfig(),
) {
    /**
     * Performs a single OAuth token acquisition attempt.
     *
     * Retry ownership contract:
     * - This class owns only transport-level retries for a single acquisition attempt (`shouldRetry=true`).
     * - The auth manager loop owns state transitions and inter-attempt scheduling/backoff.
     * - Callers can set `shouldRetry=false` for single-shot probes (for example credential validation).
     */
    fun getNewAccessToken(idpCredentials: IdpCredentials, idpEndpoint: URL, shouldRetry: Boolean = true): AuthNResponse {
        logger.debug { "Starting OAuth token acquisition for client (retryEnabled=$shouldRetry)" }

        val clientSecret = Secret(idpCredentials.clientSecret.value)
        return try {
            val request = buildTokenRequest(idpCredentials, idpEndpoint, clientSecret)
            val response = runCatching { sendTokenRequestWithTimeout(request, shouldRetry) }
                .fold(
                    onSuccess = { httpResponse -> toAuthNResponse(httpResponse) },
                    onFailure = { t -> toFailureResponse(t) }
                )

            when (response) {
                is AuthNResponse.Failed -> logger.error { "OAuth token acquisition failed permanently; error=${response.error.message}" }
                is AuthNResponse.Deny -> logger.warn { "OAuth token acquisition denied by IdP (invalid credentials?)" }
                is AuthNResponse.RetryableFailure -> {
                    val suffix = if (shouldRetry) "will retry" else "single-shot probe, will not retry"
                    logger.warn { "Transient OAuth token acquisition failure ($suffix): ${response.error.javaClass.simpleName}" }
                }
                is AuthNResponse.Success -> logger.debug { "OAuth token acquisition succeeded" }
                else -> {}
            }
            response
        } finally {
            clientSecret.erase()
        }
    }

    private fun buildTokenRequest(idpCredentials: IdpCredentials, idpEndpoint: URL, clientSecret: Secret): TokenRequest {
        val clientGrant: AuthorizationGrant = ClientCredentialsGrant()
        val clientID = ClientID(idpCredentials.clientId.id)
        val clientAuth: ClientAuthentication = ClientSecretPost(clientID, clientSecret)
        val scope = Scope(idpCredentials.scope.scope)
        val tokenEndpoint: URI = idpEndpoint.toURI()
        return TokenRequest(tokenEndpoint, clientAuth, clientGrant, scope)
    }

    private fun sendTokenRequestWithTimeout(request: TokenRequest, shouldRetry: Boolean): TokenResponse {
        val httpRequest = request.toHTTPRequest()
        httpRequest.connectTimeout = config.connectTimeoutMs.toInt()
        httpRequest.readTimeout = config.readTimeoutMs.toInt()
        proxy?.let { p ->
            httpRequest.proxy = p
            logger.debug { "OAuth2 token request will use proxy" }
        }
        return if (shouldRetry) {
            retryIoErrors.execute<HTTPResponse, Throwable> { _ ->
                httpRequest.send()
            }.run { TokenResponse.parse(this) }
        } else {
            httpRequest.send().run { TokenResponse.parse(this) }
        }
    }

    private fun toAuthNResponse(httpResponse: TokenResponse): AuthNResponse =
        if (httpResponse.indicatesSuccess()) {
            toSuccessfulAuthResponse(httpResponse.toSuccessResponse())
        } else {
            logger.debug { "OAuth token request failed (check server logs for details)" }
            AuthNResponse.Deny(WrongCredentialsException("Failed to acquire token from IdP"))
        }

    private fun toSuccessfulAuthResponse(successResponse: AccessTokenResponse): AuthNResponse =
        authTiming.resolveTokenExpiryEpochSecond(successResponse)?.let { expiresAtEpochSecond ->
            AuthNResponse.Success(
                response = successResponse,
                expiresAtEpochSecond = expiresAtEpochSecond,
            )
        } ?: AuthNResponse.Failed(
            IllegalStateException(
                "Token acquisition succeeded but expiry metadata is missing"
            )
        )

    private fun toFailureResponse(t: Throwable): AuthNResponse {
        logger.debug { "Token acquisition failed: ${t.javaClass.simpleName}" }
        return when (t) {
        is SocketTimeoutException -> AuthNResponse.RetryableFailure(
            IOException("Token acquisition timed out (read timeout)", t)
        )
        is ConnectException -> AuthNResponse.RetryableFailure(
            IOException("Cannot connect to OAuth server", t)
        )
        is IOException -> AuthNResponse.RetryableFailure(t)
        else -> AuthNResponse.Failed(
            IllegalStateException(
                "Unexpected error during token acquisition: ${t.javaClass.simpleName}",
                t,
            )
        )
        }
    }
}

internal data class TokenClientConfig(
    val connectTimeoutMs: Long = 30_000L,
    val readTimeoutMs: Long = 60_000L,
)
