package com.swisscom.health.des.cdr.client.config.auth

import com.nimbusds.oauth2.sdk.AccessTokenResponse
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import java.time.Duration
import kotlin.math.pow
import kotlin.time.Clock

internal class OAuth2AuthNTiming(
    private val config: CdrClientConfig,
    private val clock: Clock = Clock.System,
) {
    fun tokenIsExpired(tokenResponse: AuthNResponse.Success): Boolean =
        clock.now().epochSeconds > tokenResponse.expiresAtEpochSecond

    fun delayUntilRefresh(tokenResponse: AuthNResponse.Success): Duration {
        val refreshEpochSecond = tokenResponse.expiresAtEpochSecond - config.authRefreshBeforeExpiry.seconds
        val delaySeconds = refreshEpochSecond - clock.now().epochSeconds
        return if (delaySeconds <= 0) Duration.ZERO else Duration.ofSeconds(delaySeconds)
    }

    fun capByRemainingLifetime(retryDelay: Duration, tokenResponse: AuthNResponse.Success): Duration {
        val remainingSeconds = tokenResponse.expiresAtEpochSecond - clock.now().epochSeconds
        return if (remainingSeconds <= 0) Duration.ZERO else minOf(retryDelay, Duration.ofSeconds(remainingSeconds))
    }

    fun backoffDelay(
        attempt: Int,
        initialDelay: Duration,
        multiplier: Double,
        maxDelay: Duration,
    ): Duration {
        if (multiplier <= 0.0) {
            return initialDelay.coerceAtMost(maxDelay)
        }
        val exponent = attempt - 1
        val delayMillis = safeExponentialMillis(initialDelay.toMillis(), multiplier, exponent)
        return Duration.ofMillis(delayMillis).coerceAtMost(maxDelay)
    }

    fun resolveTokenExpiryEpochSecond(successResponse: AccessTokenResponse): Long? {
        val lifetimeSeconds = successResponse.tokens.accessToken.lifetime
        return parseEpochSecond(successResponse.customParameters["expires_on"])
            ?: parsePositiveLifetimeSeconds(successResponse.customParameters["ext_expires_in"])
                ?.let { clock.now().epochSeconds + it }
            ?: lifetimeSeconds.takeIf { it > 0 }?.let { clock.now().epochSeconds + it }
    }

    /**
     * Computes `initialMillis * multiplier^exponent` safely, falling back to `initialMillis` if the
     * result is [Double.NaN], infinite, or would overflow a [Long].
     */
    private fun safeExponentialMillis(initialMillis: Long, multiplier: Double, exponent: Int): Long {
        val scaled = initialMillis * multiplier.pow(exponent.toDouble())
        return when {
            scaled.isNaN() || scaled.isInfinite() || scaled >= Long.MAX_VALUE.toDouble() -> initialMillis
            else -> scaled.toLong()
        }
    }

    private fun parseEpochSecond(value: Any?): Long? =
        when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

    private fun parsePositiveLifetimeSeconds(value: Any?): Long? =
        parseEpochSecond(value)?.takeIf { it > 0 }
}
