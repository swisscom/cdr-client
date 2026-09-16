package com.swisscom.health.des.cdr.client.config.auth

import com.nimbusds.oauth2.sdk.AccessTokenResponse
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import java.time.Duration
import kotlin.time.Clock

internal class OAuth2AuthNTiming(
    private val config: CdrClientConfig,
    private val clock: Clock = Clock.System,
) {
    /**
     * Whether the given token must be considered expired *for use*.
     *
     * Applies [CdrClientConfig.authRefreshBeforeExpiry] as a clock-skew tolerance so a token is treated as expired
     * this long before its nominal expiry. Combined with proactive refresh (which is scheduled at the same
     * boundary) this guarantees a client whose clock lags the IdP never serves a token past its real lifetime.
     */
    fun tokenIsExpired(tokenResponse: AuthNResponse.Success): Boolean =
        clock.now().epochSeconds >= tokenResponse.expiresAtEpochSecond - clockSkewToleranceSeconds()

    private fun clockSkewToleranceSeconds(): Long =
        config.authRefreshBeforeExpiry.seconds.coerceAtLeast(0)

    fun delayUntilRefresh(tokenResponse: AuthNResponse.Success): Duration {
        val refreshEpochSecond = tokenResponse.expiresAtEpochSecond - config.authRefreshBeforeExpiry.seconds
        val delaySeconds = refreshEpochSecond - clock.now().epochSeconds
        return if (delaySeconds <= 0) Duration.ZERO else Duration.ofSeconds(delaySeconds)
    }

    fun resolveTokenExpiryEpochSecond(successResponse: AccessTokenResponse): Long? {
        val lifetimeSeconds = successResponse.tokens.accessToken.lifetime
        return parseEpochSecond(successResponse.customParameters["expires_on"])
            ?: parsePositiveLifetimeSeconds(successResponse.customParameters["ext_expires_in"])
                ?.let { clock.now().epochSeconds + it }
            ?: lifetimeSeconds.takeIf { it > 0 }?.let { clock.now().epochSeconds + it }
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
