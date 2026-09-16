package com.swisscom.health.des.cdr.client.config.auth

import org.springframework.http.HttpStatus

internal data class AuthHttpFailure(
    val status: HttpStatus,
    val message: String,
    val body: String,
)

/**
 * Central mapping of auth state to outbound HTTP behavior when requests cannot be authenticated.
 */
internal class AuthFailureMapper {
    fun map(response: AuthNResponse): AuthHttpFailure? =
        when (response) {
            is AuthNResponse.Success -> null
            is AuthNResponse.Authenticating -> AuthHttpFailure(
                status = HttpStatus.SERVICE_UNAVAILABLE,
                message = "Authentication in progress",
                body = "Authentication in progress.",
            )
            is AuthNResponse.NotAuthenticated,
            is AuthNResponse.RetryableFailure,
            is AuthNResponse.Failed -> AuthHttpFailure(
                status = HttpStatus.SERVICE_UNAVAILABLE,
                message = "Authentication unavailable",
                body = "Authentication is unavailable.",
            )
            is AuthNResponse.Deny -> AuthHttpFailure(
                status = HttpStatus.UNAUTHORIZED,
                message = "Authentication denied",
                body = "Authentication credentials were rejected.",
            )
        }
}
