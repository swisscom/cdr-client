package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.config.auth.AuthCommand
import com.swisscom.health.des.cdr.client.config.auth.AuthLoopState
import com.swisscom.health.des.cdr.client.config.auth.AuthNResponse
import com.swisscom.health.des.cdr.client.config.auth.AuthNState
import com.swisscom.health.des.cdr.client.config.auth.AuthRetryPolicy
import com.swisscom.health.des.cdr.client.config.auth.AuthStateSnapshot
import com.swisscom.health.des.cdr.client.config.auth.OAuth2AuthNTiming
import com.swisscom.health.des.cdr.client.config.auth.OAuth2TokenClient
import com.swisscom.health.des.cdr.client.config.auth.TokenClientConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.context.annotation.DependsOn
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.net.Proxy
import java.net.URL
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

private const val MAX_TRANSIENT_RETRIES = 30
private const val MAX_TOTAL_RETRY_DURATION_MINUTES = 5
private const val MIN_RETRY_BACKOFF_MILLIS = 100L

private val MAX_TOTAL_RETRY_DURATION = Duration.ofMinutes(MAX_TOTAL_RETRY_DURATION_MINUTES.toLong())
private val MIN_RETRY_BACKOFF = Duration.ofMillis(MIN_RETRY_BACKOFF_MILLIS)

/**
 * Owns the OAuth2 access-token lifecycle for the CDR client.
 *
 * ## Concurrency model: single-writer actor
 *
 * All mutations of the published [AuthStateSnapshot] happen inside exactly one long-lived
 * coroutine ([runAuthActor]). External components never write state; they either
 *  - **read** the immutable snapshot via [state] / [currentStateSnapshot], or
 *  - **submit an intent** (e.g. [forceReauthentication]) that is delivered to the actor
 *    through a [Channel].
 *
 * Because a single coroutine processes commands and refresh ticks sequentially, there can be
 * no duplicate/concurrent token requests and no torn reads of "(token, refresh-job)" state.
 * No `Mutex` is required: mutual exclusion is provided by the actor itself.
 *
 * ## Delegation
 *  - Token acquisition (transport retries): [tokenClient]
 *  - Timing (refresh lead time, expiry parsing): [authTiming]
 *  - Retry/backoff limits: [retryPolicy]
 */
@Service
@DependsOn("systemProxyAuthenticator")
@Suppress("TooManyFunctions")
internal class OAuth2AuthNService(
    private val config: CdrClientConfig,
    retryIoErrors: RetryTemplate,
    proxy: Proxy?,
    clock: Clock = Clock.System,
) {
    private val authTiming = OAuth2AuthNTiming(config, clock)
    private val tokenClient = OAuth2TokenClient(
        retryIoErrors,
        proxy,
        authTiming,
        TokenClientConfig(
            connectTimeoutMs = 30_000L,
            readTimeoutMs = 60_000L,
        )
    )

    private val retryPolicy = AuthRetryPolicy(
        maxDenyRetries = config.maxDenyRetries,
        maxTransientRetries = MAX_TRANSIENT_RETRIES,
        maxTotalRetryDuration = MAX_TOTAL_RETRY_DURATION,
        minRetryBackoff = MIN_RETRY_BACKOFF,
    )

    private val authManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Conflated: a burst of 401/403 triggers collapses into a single pending reauth intent. */
    private val commands = Channel<AuthCommand>(Channel.CONFLATED)

    private val _state = MutableStateFlow(AuthStateSnapshot())

    /** Read-only, hot view of the current authentication state. */
    val state: StateFlow<AuthStateSnapshot> = _state.asStateFlow()

    /** The single actor job; referenced from published snapshots for observability. */
    private var actorJob: Job? = null

    /** Guards actor (re)start so at most one actor coroutine can ever be launched at a time. */
    private val actorLifecycleLock = Any()

    init {
        startAuthManager()
    }

    @PreDestroy
    fun cleanup() {
        // Cancelling the scope cancels the actor coroutine cooperatively; the suspended
        // `commands.receive()` throws CancellationException which the actor handles as a clean stop.
        authManagerScope.cancel()
        commands.close()
    }

    /**
     * Starts the single auth-manager actor (idempotent).
     *
     * When file synchronization is disabled the actor is not started and the service stays
     * in [AuthNResponse.NotAuthenticated]; the client will refuse to emit authenticated calls.
     */
    internal fun startAuthManager() {
        if (!config.fileSynchronizationEnabled.value) {
            _state.value = AuthStateSnapshot(response = AuthNResponse.NotAuthenticated)
            return
        }
        synchronized(actorLifecycleLock) {
            if (actorJob?.isActive == true) {
                logger.debug { "Auth manager actor already running; ignoring start request" }
                return
            }
            val job = authManagerScope.launch { runAuthActor(config.idpCredentials, config.idpEndpoint) }
            actorJob = job
            job.invokeOnCompletion { cause -> handleActorCompletion(cause) }
        }
    }

    /**
     * Submits an intent to refresh the token out of schedule (e.g. after a downstream 401/403).
     *
     * This never blocks and never writes state directly; the actor applies cooldown/budget
     * limits and performs the refresh.
     */
    internal fun forceReauthentication(trigger: String) {
        if (!config.fileSynchronizationEnabled.value) {
            return
        }
        // Revive the actor if it is no longer running (e.g. it was terminated by an Error or an
        // external cancellation) so a downstream 401/403 can resurrect a dead token handler.
        if (actorJob?.isActive != true) {
            logger.warn { "Auth manager actor is not active; restarting it before queuing forced reauthentication (trigger='$trigger')" }
            startAuthManager()
        }
        val result = commands.trySend(AuthCommand.ForceReauth(trigger))
        if (result.isSuccess) {
            logger.debug { "Queued forced reauthentication (trigger='$trigger')" }
        } else {
            logger.warn { "Could not queue forced reauthentication (trigger='$trigger'): ${result.exceptionOrNull()}" }
        }
    }

    internal fun currentAuthNStateNonBlocking(): AuthNState = _state.value.state

    internal fun getAccessToken(): AuthNResponse = _state.value.response

    internal fun currentStateSnapshot(): AuthStateSnapshot = _state.value

    /**
     * Direct, single-shot token acquisition (used by credential validation flows).
     * Does not touch the actor-owned state.
     */
    internal fun getNewAccessToken(idpCredentials: IdpCredentials, idpEndpoint: URL, shouldRetry: Boolean = true): AuthNResponse =
        tokenClient.getNewAccessToken(idpCredentials, idpEndpoint, shouldRetry)

    // ---------------------------------------------------------------------------------------------
    // Actor: the ONLY writer of _state
    // ---------------------------------------------------------------------------------------------

    /**
     * The single-writer actor loop.
     *
     * Each iteration waits for whichever comes first:
     *  - the scheduled refresh delay to elapse (`nextDelay`), or
     *  - a [AuthCommand] (e.g. forced reauthentication),
     *
     * then performs at most one token acquisition and computes the next schedule. A `null`
     * `nextDelay` means "terminal until a command arrives" (e.g. credentials denied), which lets
     * a later forced reauthentication recover the service without a busy loop.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun runAuthActor(idpCredentials: IdpCredentials, idpEndpoint: URL) {
        val actor = ActorState()
        try {
            while (true) {
                runResilientIteration(actor, idpCredentials, idpEndpoint)
            }
        } catch (e: CancellationException) {
            logger.info { "Auth manager actor cancelled" }
            throw e
        } catch (@Suppress("SwallowedException") e: ClosedReceiveChannelException) {
            logger.info { "Auth manager actor stopped: command channel closed" }
        }
    }
    /**
     * Runs a single actor iteration, isolating unexpected failures so a single bad iteration
     * cannot terminate the long-lived actor. Cancellation and channel-closure still propagate
     * (clean shutdown); any other exception degrades the state to a retryable failure, arms the
     * consecutive-failure window, and schedules a short backoff so the loop keeps running.
     */
    private suspend fun runResilientIteration(actor: ActorState, idpCredentials: IdpCredentials, idpEndpoint: URL) {
        try {
            val command = awaitCommandOrTimeout(actor.nextDelay)
            if (command is AuthCommand.ForceReauth && !prepareForcedReauth(command.trigger, actor)) return
            if (exceededFailureWindow(actor)) return
            acquireAndSchedule(actor, idpCredentials, idpEndpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException") e: ClosedReceiveChannelException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            handleIterationFailure(actor, e)
        }
    }

    /** Keeps the actor alive after an unexpected iteration failure. */
    private fun handleIterationFailure(actor: ActorState, e: Exception) {
        logger.error(e) { "Auth manager iteration failed; degrading to retryable failure and backing off" }
        updateTokenState(AuthNResponse.RetryableFailure(IOException("Auth manager iteration failed: ${e.message}", e)))
        if (actor.failureWindowStart == null) {
            actor.failureWindowStart = Instant.now()
        }
        actor.nextDelay = retryPolicy.minRetryBackoff
    }

    /** Mutable state owned exclusively by the single actor coroutine. */
    private class ActorState {
        var loopState: AuthLoopState = AuthLoopState()
        var nextDelay: Duration? = Duration.ZERO
        var failureWindowStart: Instant? = null
        val forced: ForcedReauthBudget = ForcedReauthBudget()
    }

    /** @return `true` if acquisition should proceed, `false` to skip this iteration. */
    private suspend fun prepareForcedReauth(trigger: String, actor: ActorState): Boolean =
        when (applyForcedReauth(trigger, actor.forced)) {
            ForceDecision.PROCEED -> {
                actor.loopState = AuthLoopState()
                true
            }
            ForceDecision.SKIP -> false
            ForceDecision.EXHAUSTED -> {
                actor.nextDelay = null
                false
            }
        }

    /** @return `true` if the consecutive-failure window was exceeded (and state set to failed). */
    private fun exceededFailureWindow(actor: ActorState): Boolean {
        val start = actor.failureWindowStart
        val exceeded = start != null &&
            retryPolicy.hasExceededMaxTotalDuration(Duration.between(start, Instant.now()))
        if (exceeded) {
            logger.warn { "Auth manager exceeded max total retry duration; giving up until next trigger" }
            updateTokenState(AuthNResponse.Failed(IllegalStateException("Auth retry timeout exceeded")))
            actor.nextDelay = null
            actor.failureWindowStart = null
        }
        return exceeded
    }

    private suspend fun acquireAndSchedule(actor: ActorState, idpCredentials: IdpCredentials, idpEndpoint: URL) {
        logger.debug { "Attempting token acquisition (attempt=${actor.loopState.retryableAttempt})" }
        val tokenResponse = tokenClient.getNewAccessToken(idpCredentials, idpEndpoint)
        val step = evaluateAcquisition(tokenResponse, actor.loopState, actor.failureWindowStart)
        actor.loopState = step.loopState
        actor.nextDelay = step.nextDelay
        actor.failureWindowStart = step.failureWindowStart
    }

    private suspend fun awaitCommandOrTimeout(nextDelay: Duration?): AuthCommand? =
        when {
            nextDelay == null -> commands.receive()
            nextDelay <= Duration.ZERO -> commands.tryReceive().getOrNull()
            else -> withTimeoutOrNull(nextDelay.toMillis()) { commands.receive() }
        }

    /** One acquisition outcome mapped to the next schedule. Pure w.r.t. actor-local vars. */
    private data class Step(
        val loopState: AuthLoopState,
        val nextDelay: Duration?,
        val failureWindowStart: Instant?,
    )

    private suspend fun evaluateAcquisition(
        tokenResponse: AuthNResponse,
        loopState: AuthLoopState,
        failureWindowStart: Instant?,
    ): Step =
        when (tokenResponse) {
            is AuthNResponse.Success -> onSuccess(tokenResponse)
            is AuthNResponse.Deny -> onDeny(loopState, failureWindowStart)
            is AuthNResponse.RetryableFailure -> onRetryableFailure(loopState, failureWindowStart)
            is AuthNResponse.Failed -> terminal(tokenResponse)
            is AuthNResponse.Authenticating,
            is AuthNResponse.NotAuthenticated ->
                terminal(AuthNResponse.Failed(IllegalStateException("Unexpected response type in auth loop: $tokenResponse")))
        }

    private suspend fun onSuccess(tokenResponse: AuthNResponse.Success): Step {
        updateTokenState(tokenResponse)
        val nextDelay = authTiming.delayUntilRefresh(tokenResponse)
        logger.info { "Token acquired successfully; next refresh in $nextDelay" }
        return Step(
            loopState = AuthLoopState(nextDelay = nextDelay, retryableAttempt = 1, denyRetryAttempt = null),
            nextDelay = nextDelay,
            failureWindowStart = null,
        )
    }

    private suspend fun onDeny(loopState: AuthLoopState, failureWindowStart: Instant?): Step {
        val nextDenyAttempt = (loopState.denyRetryAttempt ?: 0) + 1
        val delay = retryPolicy.nextDenyRetryDelay(
            currentAttempt = nextDenyAttempt,
            initialDelay = config.authRetry.initialDelay,
            multiplier = config.authRetry.backoffMultiplier,
            maxDelay = config.authRetry.maxDelay,
        )
        return if (delay == null) {
            logger.warn { "Auth denied; max retries (${config.maxDenyRetries}) exhausted" }
            updateTokenState(AuthNResponse.Deny(WrongCredentialsException("IdP denied token acquisition after max retries")))
            Step(loopState, nextDelay = null, failureWindowStart = null)
        } else {
            logger.warn { "Auth denied; retrying in $delay (attempt=$nextDenyAttempt)" }
            updateTokenState(AuthNResponse.Authenticating)
            Step(
                loopState = loopState.copy(nextDelay = delay, retryableAttempt = 1, denyRetryAttempt = nextDenyAttempt),
                nextDelay = delay,
                failureWindowStart = failureWindowStart ?: Instant.now(),
            )
        }
    }

    private suspend fun onRetryableFailure(loopState: AuthLoopState, failureWindowStart: Instant?): Step {
        val delay = retryPolicy.nextTransientRetryDelay(
            currentAttempt = loopState.retryableAttempt,
            initialDelay = config.authRetry.initialDelay,
            multiplier = config.authRetry.backoffMultiplier,
            maxDelay = config.authRetry.maxDelay,
            cachedTokenRemainingLifetime = getTokenRemainingLifetime(),
        )
        return if (delay == null) {
            logger.warn { "Transient failures exhausted max retries; stopping until next trigger" }
            updateTokenState(AuthNResponse.Failed(IllegalStateException("Max transient retry attempts exceeded")))
            Step(loopState, nextDelay = null, failureWindowStart = null)
        } else {
            logger.warn { "Transient token acquisition failure; retrying in $delay" }
            updateTokenState(AuthNResponse.Authenticating)
            Step(
                loopState = loopState.copy(nextDelay = delay, retryableAttempt = loopState.retryableAttempt + 1),
                nextDelay = delay,
                failureWindowStart = failureWindowStart ?: Instant.now(),
            )
        }
    }

    private suspend fun terminal(tokenResponse: AuthNResponse): Step {
        updateTokenState(tokenResponse)
        return Step(AuthLoopState(), nextDelay = null, failureWindowStart = null)
    }

    // ---------------------------------------------------------------------------------------------
    // Forced reauth cooldown/budget (actor-local; no synchronization needed)
    // ---------------------------------------------------------------------------------------------

    private enum class ForceDecision { PROCEED, SKIP, EXHAUSTED }

    private class ForcedReauthBudget {
        var attempt: Int = 0
        var lastAt: Instant? = null
        var exhausted: Boolean = false
    }

    private fun applyForcedReauth(trigger: String, forced: ForcedReauthBudget): ForceDecision {
        val now = Instant.now()
        resetForcedBudgetIfQuiet(forced, now)

        if (forced.exhausted) {
            logger.error { "Forced reauthentication budget exhausted; ignoring trigger='$trigger'" }
            return ForceDecision.SKIP
        }

        val nextAttempt = forced.attempt + 1
        val requiredCooldown = retryPolicy.nextDenyRetryDelay(
            currentAttempt = nextAttempt,
            initialDelay = config.authRetry.initialDelay,
            multiplier = config.authRetry.backoffMultiplier,
            maxDelay = config.authRetry.maxDelay,
        )

        return when {
            requiredCooldown == null -> exhaustForcedBudget(forced, trigger)
            withinForcedCooldown(forced, now, requiredCooldown, nextAttempt) -> ForceDecision.SKIP
            else -> commitForcedReauth(forced, now, trigger, nextAttempt)
        }
    }

    private fun resetForcedBudgetIfQuiet(forced: ForcedReauthBudget, now: Instant) {
        forced.lastAt?.let { last ->
            if (Duration.between(last, now) >= config.authRetry.maxDelay) {
                forced.attempt = 0
                forced.exhausted = false
            }
        }
    }

    private fun exhaustForcedBudget(forced: ForcedReauthBudget, trigger: String): ForceDecision {
        forced.exhausted = true
        logger.error { "Forced reauthentication exceeded max attempts; transitioning to failed (trigger='$trigger')" }
        updateTokenState(AuthNResponse.Failed(IllegalStateException("Forced reauthentication retry budget exhausted")))
        return ForceDecision.EXHAUSTED
    }

    private fun withinForcedCooldown(
        forced: ForcedReauthBudget,
        now: Instant,
        requiredCooldown: Duration,
        nextAttempt: Int,
    ): Boolean {
        val last = forced.lastAt ?: return false
        val elapsed = Duration.between(last, now)
        return (elapsed < requiredCooldown).also { within ->
            if (within) {
                logger.warn {
                    "Skipping forced reauthentication due to cooldown; remaining=${requiredCooldown.minus(elapsed)}, attempt=$nextAttempt"
                }
            }
        }
    }

    private fun commitForcedReauth(forced: ForcedReauthBudget, now: Instant, trigger: String, nextAttempt: Int): ForceDecision {
        forced.attempt = nextAttempt
        forced.lastAt = now
        logger.warn { "Forcing reauthentication (trigger='$trigger', attempt=$nextAttempt)" }
        updateTokenState(AuthNResponse.Authenticating)
        return ForceDecision.PROCEED
    }

    // ---------------------------------------------------------------------------------------------
    // State helpers (only ever called from the actor coroutine)
    // ---------------------------------------------------------------------------------------------

    private fun handleActorCompletion(cause: Throwable?) {
        when {
            cause == null -> logger.debug { "Auth manager actor completed normally" }
            cause is CancellationException -> logger.info { "Auth manager actor was cancelled" }
            else -> {
                logger.error(cause) { "Auth manager actor terminated with error" }
                _state.value = _state.value.copy(response = AuthNResponse.NotAuthenticated)
            }
        }
    }

    private fun updateTokenState(newResponse: AuthNResponse) {
        _state.value = AuthStateSnapshot(response = newResponse, managerJob = actorJob)
    }

    private fun getTokenRemainingLifetime(): Duration? =
        (_state.value.response as? AuthNResponse.Success)
            ?.takeUnless { authTiming.tokenIsExpired(it) }
            ?.let { authTiming.delayUntilRefresh(it) }
}
