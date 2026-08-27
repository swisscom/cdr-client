package com.swisscom.health.des.cdr.client.config.auth

import com.nimbusds.oauth2.sdk.AccessTokenResponse
import com.swisscom.health.des.cdr.client.config.WrongCredentialsException
import kotlinx.coroutines.Job
import java.io.IOException
import java.time.Duration

internal enum class AuthNState {
    AUTHENTICATED,
    UNAUTHENTICATED,
    REAUTHENTICATING,
    RETRYABLE_FAILURE,
    FAILED,
    DENIED,
    UNKNOWN;
}

internal data class AuthStateSnapshot(
    val response: AuthNResponse = AuthNResponse.NotAuthenticated,
    val managerJob: Job? = null,
) {
    val state: AuthNState get() = response.toAuthNState()
}

internal sealed interface AuthNResponse {
    data class Success(
        val response: AccessTokenResponse,
        /**
         * Unix epoch second at which the access token expires, or `null` when expiry metadata is
         * unavailable (e.g. in tests that do not care about caching behaviour).
         * Production code is expected to always provide a non-null value; a `null` is treated as "already expired".
         */
        val expiresAtEpochSecond: Long? = null,
    ) : AuthNResponse

    data class Deny(val error: WrongCredentialsException) : AuthNResponse
    data class RetryableFailure(val error: IOException) : AuthNResponse
    data class Failed(val error: IllegalStateException) : AuthNResponse
    data object Reauthenticating : AuthNResponse
    data object NotAuthenticated : AuthNResponse
}

internal data class AuthLoopState(
    val nextDelay: Duration = Duration.ZERO,
    val retryableAttempt: Int = 1,
    val denyRetryAttempt: Int? = null,
)

internal sealed interface AuthLoopResult {
    data class Continue(val state: AuthLoopState) : AuthLoopResult
    data object Stop : AuthLoopResult
}

internal fun AuthNResponse.toAuthNState(): AuthNState =
    when (this) {
        is AuthNResponse.Success -> AuthNState.AUTHENTICATED
        is AuthNResponse.RetryableFailure -> AuthNState.RETRYABLE_FAILURE
        is AuthNResponse.Failed -> AuthNState.FAILED
        is AuthNResponse.Deny -> AuthNState.DENIED
        is AuthNResponse.Reauthenticating -> AuthNState.REAUTHENTICATING
        is AuthNResponse.NotAuthenticated -> AuthNState.UNAUTHENTICATED
    }
