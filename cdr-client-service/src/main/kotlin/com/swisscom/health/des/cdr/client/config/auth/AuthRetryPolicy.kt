package com.swisscom.health.des.cdr.client.config.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import kotlin.math.min
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

/**
 * Encapsulates retry policy logic for OAuth token acquisition.
 *
 * Enforces hard limits on:
 * - Number of attempts (deny retries, transient retries)
 * - Total retry duration (prevents "stuck authenticating" state)
 * - Minimum backoff intervals (prevents busy loops)
 *
 * This separates retry orchestration from auth loop state management.
 */
internal class AuthRetryPolicy(
    val maxDenyRetries: Int,
    val maxTransientRetries: Int,
    val maxTotalRetryDuration: Duration,
    val minRetryBackoff: Duration,
) {
    /**
     * Evaluates whether to continue retrying after a denied auth response.
     *
     * Returns the next delay and attempt number, or `null` if max retries exceeded.
     */
    fun nextDenyRetryDelay(
        currentAttempt: Int,
        initialDelay: Duration,
        multiplier: Double,
        maxDelay: Duration,
    ): Duration? {
        if (currentAttempt > maxDenyRetries) {
            logger.warn { "Auth deny retries exhausted (max=$maxDenyRetries)" }
            return null
        }
        return computeBackoffDelay(
            attempt = currentAttempt,
            initialDelay = initialDelay,
            multiplier = multiplier,
            maxDelay = maxDelay,
        )
    }

    /**
     * Evaluates whether to continue retrying after a transient failure.
     *
     * If a cached token is still valid, caps the retry delay by token lifetime.
     * Enforces minimum backoff to prevent busy loops.
     *
     * Returns `null` if max attempts exceeded.
     */
    fun nextTransientRetryDelay(
        currentAttempt: Int,
        initialDelay: Duration,
        multiplier: Double,
        maxDelay: Duration,
        cachedTokenRemainingLifetime: Duration? = null,
    ): Duration? {
        if (currentAttempt > maxTransientRetries) {
            logger.warn { "Transient retries exhausted (max=$maxTransientRetries)" }
            return null
        }

        val backoffDelay = computeBackoffDelay(
            attempt = currentAttempt,
            initialDelay = initialDelay,
            multiplier = multiplier,
            maxDelay = maxDelay,
        )

        // If cached token is still valid, don't wait longer than it lives
        val cappedDelay = cachedTokenRemainingLifetime?.let { remaining ->
            Duration.ofMillis(min(backoffDelay.toMillis(), remaining.toMillis()))
        } ?: backoffDelay

        // Enforce minimum backoff to prevent busy loops
        return if (cappedDelay < minRetryBackoff) minRetryBackoff else cappedDelay
    }

    /**
     * Checks whether the total retry duration has exceeded the absolute limit.
     *
     * This prevents scenarios where exponential backoff with high maxDelay
     * allows the service to remain in "Authenticating" state for hours.
     */
    fun hasExceededMaxTotalDuration(totalDurationElapsed: Duration): Boolean =
        totalDurationElapsed >= maxTotalRetryDuration

    private fun computeBackoffDelay(
        attempt: Int,
        initialDelay: Duration,
        multiplier: Double,
        maxDelay: Duration,
    ): Duration =
        when {
            multiplier <= 0.0 -> initialDelay.coerceAtMost(maxDelay)
            else -> {
                val exponent = (attempt - 1).coerceAtLeast(0)
                val delayMillis = safeExponentialMillis(
                    initialDelay.toMillis(),
                    multiplier,
                    exponent
                )
                Duration.ofMillis(delayMillis).coerceAtMost(maxDelay)
            }
        }

    private fun safeExponentialMillis(initialMillis: Long, multiplier: Double, exponent: Int): Long {
        val scaled = initialMillis * multiplier.pow(exponent.toDouble())
        return when {
            scaled.isNaN() || scaled.isInfinite() || scaled >= Long.MAX_VALUE.toDouble() -> initialMillis
            else -> scaled.toLong()
        }
    }
}
