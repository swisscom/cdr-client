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
import java.net.Proxy
import java.net.URI
import java.net.URL

private val logger = KotlinLogging.logger {}

internal class OAuth2TokenClient(
    private val retryIoErrors: RetryTemplate,
    private val proxy: Proxy?,
    private val authTiming: OAuth2AuthNTiming,
) {
    fun getNewAccessToken(idpCredentials: IdpCredentials, idpEndpoint: URL, shouldRetry: Boolean = true): AuthNResponse {
        logger.info { "Starting OAuth token acquisition for client (retryEnabled=$shouldRetry)" }

        val clientSecret = Secret(idpCredentials.clientSecret.value)
        return try {
            val request = buildTokenRequest(idpCredentials, idpEndpoint, clientSecret)
            val response = runCatching { sendTokenRequest(request, shouldRetry) }
                .fold(
                    onSuccess = { httpResponse -> toAuthNResponse(httpResponse) },
                    onFailure = { t -> toFailureResponse(t, idpCredentials) }
                )

            if (response is AuthNResponse.Failed) {
                logger.error { "OAuth token acquisition failed permanently for client; error=${response.error.message}" }
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

    private fun sendTokenRequest(request: TokenRequest, shouldRetry: Boolean): TokenResponse {
        val httpRequest = request.toHTTPRequest()
        proxy?.let { p ->
            httpRequest.proxy = p
            logger.debug { "OAuth2 token request will use proxy: '$p'" }
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
            AuthNResponse.Deny(
                WrongCredentialsException(
                    "Failed to login; message: '${
                        httpResponse.toErrorResponse().toJSONObject()
                    }'"
                )
            )
        }

    private fun toSuccessfulAuthResponse(successResponse: AccessTokenResponse): AuthNResponse =
        authTiming.resolveTokenExpiryEpochSecond(successResponse)?.let { expiresAtEpochSecond ->
            AuthNResponse.Success(
                response = successResponse,
                expiresAtEpochSecond = expiresAtEpochSecond,
            )
        } ?: AuthNResponse.Failed(
            IllegalStateException(
                "Failed to login; missing token expiry metadata"
            )
        )

    private fun toFailureResponse(t: Throwable, idpCredentials: IdpCredentials): AuthNResponse {
        logger.debug { "Error while trying to get access token from IdP for client id '${idpCredentials.clientId}': $t" }
        return when (t) {
            is IOException -> AuthNResponse.RetryableFailure(t)
            else -> AuthNResponse.Failed(
                IllegalStateException(
                    "Failed to login; root cause: '$t'",
                    t,
                )
            )
        }
    }
}
