package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.config.auth.AuthLoopResult
import com.swisscom.health.des.cdr.client.config.auth.AuthLoopState
import com.swisscom.health.des.cdr.client.config.auth.AuthNResponse
import com.swisscom.health.des.cdr.client.config.auth.AuthNState
import com.swisscom.health.des.cdr.client.config.auth.AuthStateSnapshot
import com.swisscom.health.des.cdr.client.config.auth.OAuth2AuthNTiming
import com.swisscom.health.des.cdr.client.config.auth.OAuth2TokenClient
import com.swisscom.health.des.cdr.client.config.auth.toAuthNState
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.time.delay
import org.springframework.context.annotation.DependsOn
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.net.Proxy
import java.net.URL
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
private val logger = KotlinLogging.logger {}

@Service
@DependsOn("systemProxyAuthenticator")
@Suppress("TooManyFunctions")
internal class OAuth2AuthNService(
    private val config: CdrClientConfig,
    retryIoErrors: RetryTemplate,
    proxy: Proxy?,
    clock: Clock = Clock.System,
) {
    private val authStateRef = AtomicReference(AuthStateSnapshot())
    private val authTiming = OAuth2AuthNTiming(config, clock)
    private val tokenClient = OAuth2TokenClient(retryIoErrors, proxy, authTiming)
    private val authManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        startAuthManager()
    }

    @PreDestroy
    fun cleanup() {
        authManagerScope.cancel()
    }

    internal fun startAuthManager() {
        if (!config.fileSynchronizationEnabled.value) {
            updateAuthNResponse(AuthNResponse.NotAuthenticated)
            return
        }

        val job = authManagerScope.launch(start = CoroutineStart.LAZY) {
            supervisorScope {
                runAuthManagerLoop(config.idpCredentials, config.idpEndpoint)
            }
        }

        val didStart = startAuthManagerJob(job)
        if (!didStart) {
            job.cancel(CancellationException("Duplicate auth manager start request"))
        }
    }

    private fun startAuthManagerJob(job: Job): Boolean {
        while (true) {
            val current = authStateRef.get()
            if (current.managerJob?.isActive == true) {
                return false
            }

            val next = current.copy(
                response = AuthNResponse.Authenticating,
                managerJob = job,
            )
            if (authStateRef.compareAndSet(current, next)) {
                job.invokeOnCompletion { cause -> handleAuthManagerCompletion(job, cause) }
                job.start()
                return true
            }
        }
    }

    internal fun currentAuthNStateNonBlocking(): AuthNState = currentReadableResponse().toAuthNState()

    internal fun getAccessToken(): AuthNResponse = currentReadableResponse()

    private suspend fun runAuthManagerLoop(idpCredentials: IdpCredentials, idpEndpoint: URL) {
        var loopState = AuthLoopState()
        while (true) {
            val tokenBeforeAttempt = prepareForNextAttempt(loopState.nextDelay)
            when (val loopResult = toAuthLoopResult(getNewAccessToken(idpCredentials, idpEndpoint), tokenBeforeAttempt, loopState)) {
                is AuthLoopResult.Continue -> loopState = loopResult.state
                AuthLoopResult.Stop -> break
            }
        }
    }

    private suspend fun prepareForNextAttempt(nextDelay: Duration): AuthNResponse.Success? {
        if (nextDelay > Duration.ZERO) {
            delay(nextDelay)
        }

        return validCachedToken().also { tokenBeforeAttempt ->
            if (tokenBeforeAttempt == null) {
                updateAuthNResponse(AuthNResponse.Authenticating)
            }
        }
    }

    private fun toAuthLoopResult(
        tokenResponse: AuthNResponse,
        tokenBeforeAttempt: AuthNResponse.Success?,
        loopState: AuthLoopState
    ): AuthLoopResult =
        when (tokenResponse) {
            is AuthNResponse.Success -> handleSuccessfulAuthResponse(tokenResponse)
            is AuthNResponse.Deny -> handleDeniedAuthResponse(tokenResponse, loopState)
            is AuthNResponse.RetryableFailure -> handleRetryableFailureResponse(tokenBeforeAttempt, loopState)
            is AuthNResponse.Failed -> stopWith(tokenResponse)
            // States here should never be returned by the token client, but we handle them defensively in case of a programming error.
            is AuthNResponse.Authenticating, is AuthNResponse.NotAuthenticated -> stopWithUnexpectedResponse(tokenResponse)
        }

    private fun handleSuccessfulAuthResponse(tokenResponse: AuthNResponse.Success): AuthLoopResult {
        logger.info {
            "OAuth token acquisition succeeded; expiresAtEpochSecond=${tokenResponse.expiresAtEpochSecond}, " +
                "refreshIn=${authTiming.delayUntilRefresh(tokenResponse)}"
        }
        updateAuthNResponse(tokenResponse)
        return AuthLoopResult.Continue(AuthLoopState(nextDelay = authTiming.delayUntilRefresh(tokenResponse)))
    }

    private fun handleDeniedAuthResponse(tokenResponse: AuthNResponse.Deny, loopState: AuthLoopState): AuthLoopResult {
        val nextDenyRetryAttempt = (loopState.denyRetryAttempt ?: 0) + 1
        val denyRetryDelay = authTiming.backoffDelay(
            attempt = nextDenyRetryAttempt,
            initialDelay = config.authRetry.initialDelay,
            multiplier = config.authRetry.backoffMultiplier,
            maxDelay = config.authRetry.maxDelay,
        )
        logger.warn {
            "IdP denied OAuth token acquisition; retrying in $denyRetryDelay " +
                "(attempt=$nextDenyRetryAttempt/${config.maxDenyRetries})"
        }
        updateAuthNResponse(AuthNResponse.Authenticating)
        return if (nextDenyRetryAttempt > config.maxDenyRetries) {
            stopWith(tokenResponse)
        } else {
            AuthLoopResult.Continue(
                loopState.copy(
                    nextDelay = denyRetryDelay,
                    retryableAttempt = 1,
                    denyRetryAttempt = nextDenyRetryAttempt,
                )
            )
        }
    }

    private fun handleRetryableFailureResponse(
        tokenBeforeAttempt: AuthNResponse.Success?,
        loopState: AuthLoopState
    ): AuthLoopResult {
        val retryDelay = authTiming.backoffDelay(
            attempt = loopState.retryableAttempt,
            initialDelay = config.authRetry.initialDelay,
            multiplier = config.authRetry.backoffMultiplier,
            maxDelay = config.authRetry.maxDelay,
        )
        val nextDelay = if (tokenBeforeAttempt == null) {
            logger.warn {
                "Transient OAuth token acquisition failure; retrying in $retryDelay (attempt=${loopState.retryableAttempt})"
            }
            updateAuthNResponse(AuthNResponse.Authenticating)
            retryDelay
        } else {
            val cappedDelay = authTiming.capByRemainingLifetime(retryDelay, tokenBeforeAttempt)
            logger.warn {
                "Transient OAuth token acquisition failure while cached token is still valid; retrying in $cappedDelay"
            }
            cappedDelay
        }
        return AuthLoopResult.Continue(
            loopState.copy(
                nextDelay = nextDelay,
                retryableAttempt = loopState.retryableAttempt + 1,
            )
        )
    }

    private fun stopWith(tokenResponse: AuthNResponse): AuthLoopResult {
        updateAuthNResponse(tokenResponse)
        return AuthLoopResult.Stop
    }

    private fun stopWithUnexpectedResponse(tokenResponse: AuthNResponse): AuthLoopResult =
        stopWith(
            AuthNResponse.Failed(
                IllegalStateException("Unexpected authentication response in auth manager loop: '$tokenResponse'")
            )
        )

    private fun currentReadableResponse(): AuthNResponse {
        val snapshot = authStateRef.get()
        val cachedToken = validCachedToken(snapshot)
        if (cachedToken != null) {
            return cachedToken
        }

        return when (val currentResponse = snapshot.response) {
            is AuthNResponse.Success -> {
                if (snapshot.managerJob?.isActive == true && config.fileSynchronizationEnabled.value) {
                    AuthNResponse.Authenticating
                } else {
                    AuthNResponse.NotAuthenticated
                }
            }

            else -> currentResponse
        }
    }

    private fun validCachedToken(snapshot: AuthStateSnapshot = authStateRef.get()): AuthNResponse.Success? =
        (snapshot.response as? AuthNResponse.Success)?.takeUnless { authTiming.tokenIsExpired(it) }

    private fun updateAuthNResponse(newResponse: AuthNResponse) {
        authStateRef.updateAndGet { current ->
            current.copy(
                response = newResponse,
            )
        }
    }

    private fun handleAuthManagerCompletion(job: Job, cause: Throwable?) {
        val updatedSnapshot = authStateRef.updateAndGet { current ->
            val clearedSnapshot = clearManagerJobIfMatches(current, job)
            if (cause != null) resetToUnauthenticatedIfNeeded(clearedSnapshot) else clearedSnapshot
        }

        if (cause != null) {
            logAuthManagerFailure(cause)
        } else {
            logger.debug { "Authentication manager job completed." }
        }

        if (updatedSnapshot.response is AuthNResponse.NotAuthenticated) {
            logger.debug { "Authentication manager reset to unauthenticated state." }
        }
    }

    private fun clearManagerJobIfMatches(snapshot: AuthStateSnapshot, job: Job): AuthStateSnapshot =
        if (snapshot.managerJob === job) snapshot.copy(managerJob = null) else snapshot

    private fun resetToUnauthenticatedIfNeeded(snapshot: AuthStateSnapshot): AuthStateSnapshot =
        if (snapshot.response is AuthNResponse.Authenticating) {
            snapshot.copy(
                response = AuthNResponse.NotAuthenticated,
            )
        } else {
            snapshot
        }

    private fun logAuthManagerFailure(cause: Throwable) {
        when (cause) {
            is CancellationException -> logger.info { "Authentication manager job was cancelled." }
            else -> logger.warn(cause) { "Authentication manager job failed." }
        }
    }

    internal fun getNewAccessToken(idpCredentials: IdpCredentials, idpEndpoint: URL, shouldRetry: Boolean = true): AuthNResponse =
        tokenClient.getNewAccessToken(idpCredentials, idpEndpoint, shouldRetry)
}

