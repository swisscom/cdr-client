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
import kotlinx.coroutines.time.delay
import org.springframework.context.annotation.DependsOn
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.net.Proxy
import java.net.URL
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
            runAuthManagerLoop(config.idpCredentials, config.idpEndpoint)
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

    /**
     * Main authentication manager loop.
     *
     * **Story:**
     * 1. Prepare: Sleep until next retry attempt (initial delay is 0)
     * 2. Acquire: Call token client to get new token (blocking, includes retries + backoff)
     * 3. Evaluate: Process response and decide to continue with new delay or stop
     *    - Success: Store token, compute refresh time, continue
     *    - Deny: Update state to Authenticating, retry with backoff or stop if max retries reached
     *    - RetryableFailure: Check FRESH cached token (after acquisition), cap delay if valid, retry with backoff
     *    - Failed/Unexpected: Stop with failure state
     *
     * **Key design point:** Cached token is checked AFTER token acquisition returns, not before.
     * This prevents using stale token state in retry logic if the token client call takes longer
     * than the remaining token lifetime.
     *
     * Retry ownership contract:
     * - This loop owns inter-attempt scheduling, auth-state transitions, and deny/retry stop conditions.
     * - [OAuth2TokenClient] owns transport-level retries within each individual acquisition attempt.
     */
    private suspend fun runAuthManagerLoop(idpCredentials: IdpCredentials, idpEndpoint: URL) {
        var loopState = AuthLoopState()
        while (true) {
            // Step 1: Prepare for next attempt (sleep if this is a retry)
            delay(loopState.nextDelay)

            // Step 2: Acquire new token (blocking call with retry/backoff)
            logger.debug { "Attempting OAuth token acquisition (attempt=${loopState.retryableAttempt})" }
            val tokenResponse = getNewAccessToken(idpCredentials, idpEndpoint)

            // Step 3: Evaluate result using FRESH cached token state (not captured before Step 2)
            val cachedTokenAfterAcquisition = validCachedToken()
            when (val loopResult = toAuthLoopResult(tokenResponse, cachedTokenAfterAcquisition, loopState)) {
                is AuthLoopResult.Continue -> loopState = loopResult.state
                AuthLoopResult.Stop -> break
            }
        }
    }

    /**
     * Evaluates the token acquisition result and determines whether to continue or stop the loop.
     *
     * **Parameter contract:**
     * - `cachedTokenAfterAcquisition`: Fresh token state captured AFTER token client call returns.
     *   This is not stale and reflects the actual current token availability.
     */
    private fun toAuthLoopResult(
        tokenResponse: AuthNResponse,
        cachedTokenAfterAcquisition: AuthNResponse.Success?,
        loopState: AuthLoopState
    ): AuthLoopResult =
        when (tokenResponse) {
            is AuthNResponse.Success -> handleSuccessfulAuthResponse(tokenResponse)
            is AuthNResponse.Deny -> handleDeniedAuthResponse(tokenResponse, loopState)
            is AuthNResponse.RetryableFailure -> handleRetryableFailureResponse(cachedTokenAfterAcquisition, loopState)
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
            logger.warn { "IdP deny retries exhausted; stopping auth manager" }
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

    /**
     * Handles transient token acquisition failures.
     *
     * If a cached token is still valid (fresh check AFTER acquisition), we cap the retry delay
     * by the token's remaining lifetime. This prevents waiting longer than the token lives.
     * If no cached token is valid, we use the full computed backoff delay.
     *
     * **Important:** `cachedTokenAfterAcquisition` is checked AFTER the token client call returns,
     * not before. This ensures we're not using stale token state to compute delays.
     */
    private fun handleRetryableFailureResponse(
        cachedTokenAfterAcquisition: AuthNResponse.Success?,
        loopState: AuthLoopState
    ): AuthLoopResult {
        val retryDelay = authTiming.backoffDelay(
            attempt = loopState.retryableAttempt,
            initialDelay = config.authRetry.initialDelay,
            multiplier = config.authRetry.backoffMultiplier,
            maxDelay = config.authRetry.maxDelay,
        )
        val nextDelay = if (cachedTokenAfterAcquisition == null) {
            logger.warn {
                "Transient OAuth token acquisition failure with no cached token; retrying in $retryDelay (attempt=${loopState.retryableAttempt})"
            }
            updateAuthNResponse(AuthNResponse.Authenticating)
            retryDelay
        } else {
            val cappedDelay = authTiming.capByRemainingLifetime(retryDelay, cachedTokenAfterAcquisition)
            val remainingLifetime = authTiming.delayUntilRefresh(cachedTokenAfterAcquisition)
            logger.warn {
                "Transient OAuth token acquisition failure while cached token is still valid; " +
                        "retrying in $cappedDelay (remaining lifetime=$remainingLifetime)"
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
        return projectForExpiry(snapshot)
    }

    /**
     * Projects an expired [AuthNResponse.Success] to the appropriate effective state.
     * When a token has expired:
     * - if the manager loop is still active (refresh in flight), return [AuthNResponse.Authenticating]
     * - otherwise, return [AuthNResponse.NotAuthenticated]
     * All other responses are returned as-is.
     *
     * This is the only read-time state derivation: expiry is time-based and not explicitly
     * written by the auth loop when the clock boundary is crossed.
     */
    private fun projectForExpiry(snapshot: AuthStateSnapshot): AuthNResponse {
        val currentResponse = snapshot.response
        return when (currentResponse) {
            is AuthNResponse.Success -> {
                // Token expired; check if loop is still active (refresh in flight)
                if (snapshot.managerJob?.isActive == true) {
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

    /**
     * Direct token acquisition entry point.
     *
     * This method does not mutate the auth-manager state machine by itself. It delegates to
     * [OAuth2TokenClient] and is used both by the background auth loop (`shouldRetry=true`) and
     * by single-shot credential validation probes (`shouldRetry=false`).
     */
    internal fun getNewAccessToken(idpCredentials: IdpCredentials, idpEndpoint: URL, shouldRetry: Boolean = true): AuthNResponse =
        tokenClient.getNewAccessToken(idpCredentials, idpEndpoint, shouldRetry)
}

