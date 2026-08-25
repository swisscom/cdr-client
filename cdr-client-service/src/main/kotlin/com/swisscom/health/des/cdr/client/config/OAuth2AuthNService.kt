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
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.REAUTHENTICATING
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.RETRYABLE_FAILURE
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.UNAUTHENTICATED
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import org.springframework.context.annotation.DependsOn
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.Proxy
import java.time.Duration
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.concurrent.write
import kotlin.math.pow
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

@Service
@DependsOn("systemProxyAuthenticator")
@Suppress("TooManyFunctions")
internal class OAuth2AuthNService (
    private val config: CdrClientConfig,
    private val retryIoErrors: RetryTemplate,
    private val proxy: Proxy?,
    private val applicationScope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {

    private var accessTokenAuthNResponse: AuthNResponse = AuthNResponse.NotAuthenticated
    private val tokenLock = ReentrantReadWriteLock()
    private var reauthRetryJob: Job? = null

    @Volatile
    private var cachedAuthNState: AuthNState = AuthNState.UNKNOWN

    internal enum class AuthNState {
        AUTHENTICATED,
        UNAUTHENTICATED,
        REAUTHENTICATING,
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
        object Reauthenticating : AuthNResponse
        object NotAuthenticated : AuthNResponse
    }

    internal fun currentAuthNStateNonBlocking(): AuthNState = cachedAuthNState

    internal fun getAccessToken(): AuthNResponse {
        return tokenLock.write {
            validCachedToken()?.let { return@write it }
            resolveTokenResponse().also { updateAuthNResponse(it) }
        }
    }

    private fun validCachedToken(): AuthNResponse.Success? =
        (accessTokenAuthNResponse as? AuthNResponse.Success)?.takeUnless { tokenIsExpired(it) }

    private fun tokenIsExpired(tokenResponse: AuthNResponse.Success): Boolean {
        val expiresOn = tokenResponse.response.customParameters["expires_on"] as Long?
        return expiresOn == null || clock.now().epochSeconds > expiresOn
    }

    private fun resolveTokenResponse(): AuthNResponse =
        when (val currentTokenResponse = accessTokenAuthNResponse) {
            is AuthNResponse.Reauthenticating -> {
                if (reauthRetryJob?.isActive == true) {
                    AuthNResponse.Reauthenticating
                } else {
                    logger.warn { "Detected stale reauthentication state without an active retry job; attempting token acquisition again." }
                    startReauthRetryIfNeeded(
                        getNewAccessToken(config.idpCredentials, config.idpEndpoint),
                        config.idpCredentials,
                        config.idpEndpoint
                    )
                }
            }

            is AuthNResponse.Deny, is AuthNResponse.Failed -> currentTokenResponse
            is AuthNResponse.Success, is AuthNResponse.NotAuthenticated, is AuthNResponse.RetryableFailure -> {
                startReauthRetryIfNeeded(
                    getNewAccessToken(config.idpCredentials, config.idpEndpoint),
                    config.idpCredentials,
                    config.idpEndpoint
                )
            }
        }

    private fun startReauthRetryIfNeeded(
        tokenResponse: AuthNResponse,
        idpCredentials: IdpCredentials,
        idpEndpoint: URL
    ): AuthNResponse {
        if (tokenResponse is AuthNResponse.Deny && config.denyRetryAttempts > 0) {
            launchReauthRetry(idpCredentials, idpEndpoint)
            return AuthNResponse.Reauthenticating
        }
        return tokenResponse
    }

    private fun launchReauthRetry(idpCredentials: IdpCredentials, idpEndpoint: URL) {
        if (reauthRetryJob?.isActive == true) return

        val retryAttempts = config.denyRetryAttempts
        logger.info { "Starting background authentication retry flow after denied response (attempts='$retryAttempts')." }

        val job = applicationScope.launch { performReauthRetry(idpCredentials, idpEndpoint, retryAttempts) }

        reauthRetryJob = job
        job.invokeOnCompletion { cause -> handleRetryJobCompletion(job, cause) }
    }

    private suspend fun performReauthRetry(idpCredentials: IdpCredentials, idpEndpoint: URL, retryAttempts: Int) {
        var latestResponse: AuthNResponse = AuthNResponse.NotAuthenticated
        var terminalResponse: AuthNResponse? = null
        var attempt = 1

        while (attempt <= retryAttempts && terminalResponse == null) {
            delay(reauthRetryDelay(attempt))
            logger.debug { "Executing authentication retry attempt '$attempt' of '$retryAttempts'." }
            latestResponse = getNewAccessToken(idpCredentials, idpEndpoint)

            when (latestResponse) {
                is AuthNResponse.Success -> {
                    logger.info { "Authentication retry succeeded on attempt '$attempt'." }
                    terminalResponse = latestResponse
                }

                is AuthNResponse.Deny -> {
                    logger.debug { "Authentication retry attempt '$attempt' still denied." }
                }

                is AuthNResponse.RetryableFailure, is AuthNResponse.Failed -> {
                    logger.warn { "Authentication retry ended with non-deny failure on attempt '$attempt': '$latestResponse'" }
                    terminalResponse = latestResponse
                }

                is AuthNResponse.Reauthenticating, is AuthNResponse.NotAuthenticated -> {
                    terminalResponse = AuthNResponse.Failed(
                        IllegalStateException("Unexpected authentication response during deny retry: '$latestResponse'")
                    )
                }
            }
            attempt++
        }

        val finalResponse = terminalResponse ?: when (latestResponse) {
            is AuthNResponse.Deny -> {
                logger.info { "Authentication retry attempts exhausted; keeping denied authentication state." }
                latestResponse
            }

            else -> {
                logger.warn { "Authentication retry attempts exhausted with unexpected response: '$latestResponse'" }
                AuthNResponse.Failed(
                    IllegalStateException("Expected denied authentication response after retry exhaustion but got '$latestResponse'")
                )
            }
        }
        updateAuthNResponse(finalResponse)
    }

    private fun handleRetryJobCompletion(job: Job, cause: Throwable?) {
        tokenLock.write {
            if (reauthRetryJob === job) {
                reauthRetryJob = null
            }
            if (cause != null) {
                when (cause) {
                    is CancellationException -> logger.info { "Authentication retry job was cancelled." }
                    else -> logger.warn(cause) { "Authentication retry job failed." }
                }
                if (accessTokenAuthNResponse is AuthNResponse.Reauthenticating) {
                    accessTokenAuthNResponse = AuthNResponse.NotAuthenticated
                    cachedAuthNState = UNAUTHENTICATED
                }
            } else {
                logger.debug { "Authentication retry job completed." }
            }
        }
    }

    private fun reauthRetryDelay(attempt: Int): Duration {
        val multiplier = config.denyRetryBackoffMultiplier
        if (multiplier <= 0.0) return config.denyRetryInitialDelay
        val exponent = attempt - 1
        val factor = multiplier.pow(exponent.toDouble())
        val nextDelayMillis = (config.denyRetryInitialDelay.toMillis() * factor).toLong()
        return Duration.ofMillis(nextDelayMillis)
    }

    private fun updateAuthNResponse(newResponse: AuthNResponse) {
        tokenLock.write {
            accessTokenAuthNResponse = newResponse
            cachedAuthNState = newResponse.toAuthNState()
        }
    }

    private fun AuthNResponse.toAuthNState(): AuthNState =
        when (this) {
            is AuthNResponse.Success -> AUTHENTICATED
            is AuthNResponse.RetryableFailure -> RETRYABLE_FAILURE
            is AuthNResponse.Failed -> AuthNState.FAILED
            is AuthNResponse.Deny -> DENIED
            is AuthNResponse.Reauthenticating -> REAUTHENTICATING
            is AuthNResponse.NotAuthenticated -> UNAUTHENTICATED
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
                val httpRequest = request.toHTTPRequest()

                proxy?.let { p ->
                    httpRequest.proxy = p
                    logger.debug { "OAuth2 token request will use proxy: '$p'" }
                }

                if (shouldRetry) {
                    retryIoErrors.execute<HTTPResponse, Throwable> { _ ->
                        httpRequest.send()
                    }.run { TokenResponse.parse(this) }
                } else {
                    httpRequest.send().run { TokenResponse.parse(this) }
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
