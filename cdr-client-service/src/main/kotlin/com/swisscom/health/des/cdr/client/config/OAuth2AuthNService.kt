package com.swisscom.health.des.cdr.client.config

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
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.AUTHENTICATED
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.DENIED
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.RETRYABLE_FAILURE
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.UNAUTHENTICATED
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.URL
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

@Service
internal class OAuth2AuthNService @OptIn(ExperimentalTime::class) constructor(
    private val config: CdrClientConfig,
    private val retryIoErrors: RetryTemplate,
    @Suppress("SpringJavaInjectionPointsAutowiringInspection")
    private val clock: Clock = Clock.System,
) {

    private var accessTokenAuthNResponse: AuthNResponse = AuthNResponse.NotAuthenticated
    private val tokenLock = ReentrantReadWriteLock()

    @Volatile
    private var cachedAuthNState: AuthNState = AuthNState.UNKNOWN

    internal enum class AuthNState {
        AUTHENTICATED,
        UNAUTHENTICATED,
        RETRYABLE_FAILURE,
        FAILED,
        DENIED,
        UNKNOWN;
    }

    internal sealed interface AuthNResponse {
        data class Success(val response: AccessTokenResponse) : AuthNResponse
        data class Deny(val error: WrongCredentialsException) : AuthNResponse
        data class RetryableFailure(val error: IOException) : AuthNResponse
        data class Failed(val error: IllegalStateException) : AuthNResponse
        object NotAuthenticated : AuthNResponse
    }

    internal fun currentAuthNStateNonBlocking(): AuthNState = cachedAuthNState

    @OptIn(ExperimentalTime::class)
    internal fun getAccessToken(): AuthNResponse {
        tokenLock.read {
            val currentTokenResponse = accessTokenAuthNResponse
            if (currentTokenResponse is AuthNResponse.Success) {
                val expiresOn = currentTokenResponse.response.customParameters["expires_on"] as Long?
                if (expiresOn != null && clock.now().epochSeconds <= expiresOn) {
                    return currentTokenResponse
                }
            }
        }

        return tokenLock.write {
            val currentTokenResponse = accessTokenAuthNResponse
            if (currentTokenResponse is AuthNResponse.Success) {
                val expiresOn = currentTokenResponse.response.customParameters["expires_on"] as Long?
                if (expiresOn != null && clock.now().epochSeconds <= expiresOn) {
                    return@write currentTokenResponse
                }
            }

            val newResponse = when (currentTokenResponse) {
                is AuthNResponse.Deny, is AuthNResponse.Failed -> currentTokenResponse
                else -> getNewAccessToken(config.idpCredentials, config.idpEndpoint)
            }

            accessTokenAuthNResponse = newResponse
            cachedAuthNState = when (newResponse) {
                is AuthNResponse.Success -> AUTHENTICATED
                is AuthNResponse.RetryableFailure -> RETRYABLE_FAILURE
                is AuthNResponse.Failed -> AuthNState.FAILED
                is AuthNResponse.Deny -> DENIED
                is AuthNResponse.NotAuthenticated -> UNAUTHENTICATED
            }
            newResponse
        }
    }

    internal fun getNewAccessToken(idpCredentials: IdpCredentials, idpEndpoint: URL, shouldRetry: Boolean = true): AuthNResponse {
        val clientGrant: AuthorizationGrant = ClientCredentialsGrant()
        val clientID = ClientID(idpCredentials.clientId.id)
        val clientSecret = Secret(idpCredentials.clientSecret.value)
        // `ClientSecretBasic` is another option; it works in production, but our mock IdP is not set up to get the client id from the Basic Auth header,
        // instead we use the form parameter `client_id`
        val clientAuth: ClientAuthentication = ClientSecretPost(clientID, clientSecret)
        val scope = Scope(idpCredentials.scope.scope)
        val tokenEndpoint: URI = idpEndpoint.toURI()
        val request = TokenRequest(tokenEndpoint, clientAuth, clientGrant, scope)

        val authNResponse: AuthNResponse =
            runCatching {
                if (shouldRetry) {
                    retryIoErrors.execute<HTTPResponse, Throwable> { _ ->
                        request.toHTTPRequest().send()
                    }.run { TokenResponse.parse(this) }
                } else {
                    request.toHTTPRequest().send().run { TokenResponse.parse(this) }
                }
            }.fold(
                onSuccess = { httpResponse: TokenResponse ->
                    if (httpResponse.indicatesSuccess()) {
                        AuthNResponse.Success(httpResponse.toSuccessResponse())
                    } else {
                        AuthNResponse.Deny(
                            WrongCredentialsException(
                                "Failed to login; client id: '${idpCredentials.clientId}'; IdP endpoint: '$idpEndpoint'; message: '${
                                    httpResponse.toErrorResponse().toJSONObject()
                                }'"
                            )
                        )
                    }
                },
                onFailure = { t ->
                    logger.debug { "Error while trying to get access token from IdP at '$idpEndpoint' for client id '${idpCredentials.clientId}': $t" }
                    when (t) {
                        is IOException -> AuthNResponse.RetryableFailure(t)
                        else -> AuthNResponse.Failed(
                            IllegalStateException(
                                "Failed to login; client id: '${idpCredentials.clientId}'; IdP endpoint: '$idpEndpoint'; root cause: '$t'",
                                t,
                            )
                        )
                    }
                }
            )

        return authNResponse
    }
}
