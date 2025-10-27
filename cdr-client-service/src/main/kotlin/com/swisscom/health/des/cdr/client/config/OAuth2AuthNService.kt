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
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.URL
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Service
internal class OAuth2AuthNService @OptIn(ExperimentalTime::class) constructor(
    private val config: CdrClientConfig,
    private val retryIoErrors: RetryTemplate,
    private val clock: Clock = Clock.System,
) {

    private var accessTokenAuthNResponse: AuthNResponse = AuthNResponse.NotAuthenticated

    internal enum class AuthNState {
        AUTHENTICATED,
        UNAUTHENTICATED,
        RETRYABLE_FAILURE,
        FAILED,
        DENIED;
    }

    internal sealed interface AuthNResponse {
        data class Success(val response: AccessTokenResponse) : AuthNResponse
        data class Deny(val error: WrongCredentialsException) : AuthNResponse
        data class RetryableFailure(val error: IOException) : AuthNResponse
        data class Failed(val error: IllegalStateException) : AuthNResponse
        object NotAuthenticated : AuthNResponse
    }

    @Synchronized
    internal fun currentAuthNState(): AuthNState =
        when (accessTokenAuthNResponse) {
            is AuthNResponse.Success -> AUTHENTICATED
            is AuthNResponse.RetryableFailure -> RETRYABLE_FAILURE
            is AuthNResponse.Failed -> AuthNState.FAILED
            is AuthNResponse.Deny -> DENIED
            is AuthNResponse.NotAuthenticated -> UNAUTHENTICATED
        }

    @OptIn(ExperimentalTime::class)
    // could be improved with more fine-grained locking to avoid blocking all threads while a valid token is available and no renewal is required;
    // but we have only five threads for uploads, one for downloads (all of which are mostly waiting for IO), and one for the service status check,
    // so lock contention should be minimal
    @Synchronized
    internal fun getAccessToken(): AuthNResponse =
        when (val currentTokenResponse = accessTokenAuthNResponse) {
            is AuthNResponse.NotAuthenticated, is AuthNResponse.RetryableFailure -> {
                getNewAccessToken(config.idpCredentials, config.idpEndpoint)
            }

            is AuthNResponse.Deny, is AuthNResponse.Failed -> {
                currentTokenResponse
            }

            is AuthNResponse.Success -> {
                val expiresOn = currentTokenResponse.response.customParameters["expires_on"] as Long?
                if (expiresOn == null || clock.now().epochSeconds > expiresOn) {
                    getNewAccessToken(config.idpCredentials, config.idpEndpoint)
                } else {
                    currentTokenResponse
                }
            }
        }.also {
            accessTokenAuthNResponse = it
        }

    internal fun getNewAccessToken(idpCredentials: IdpCredentials, idpEndpoint: URL): AuthNResponse {
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
                retryIoErrors.execute<HTTPResponse, Throwable> { _ ->
                    request.toHTTPRequest().send()
                }.run { TokenResponse.parse(this) }
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
