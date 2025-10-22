package com.swisscom.health.des.cdr.client.http

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.AUTHENTICATED
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.DENIED
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.FAILED
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.RETRYABLE_FAILURE
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService.AuthNState.UNAUTHENTICATED
import com.swisscom.health.des.cdr.client.handler.ConfigValidationService
import com.swisscom.health.des.cdr.client.handler.SchedulingValidationService
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class HealthIndicators(
    private val config: CdrClientConfig,
    private val configValidationService: ConfigValidationService,
    private val schedulingValidationService: SchedulingValidationService,
    private val authNService: OAuth2AuthNService,
) {

    /**
     * A custom health indicator that checks if file synchronization is enabled or disabled.
     */
    @Bean
    fun fileSynchronizationHealthIndicator(): HealthIndicator =
        HealthIndicator {
            when (config.fileSynchronizationEnabled.value) {
                true -> Health.status(FILE_SYNCHRONIZATION_STATUS_ENABLED)
                false -> Health.status(FILE_SYNCHRONIZATION_STATUS_DISABLED)
            }.build()
        }

    @Bean
    fun configHealthIndicator(): HealthIndicator =
        HealthIndicator {
            when {
                !schedulingValidationService.isConfigSourceUnambiguous -> Health.status(CONFIG_BROKEN)
                    .withDetail("configStatus", "ambiguous config source")

                !configValidationService.isConfigValid -> Health.status(CONFIG_ERROR)
                    .withDetail("configStatus", "invalid config")

                else -> Health.status(CONFIG_OK).withDetail("configStatus", "valid config")
            }.build()
        }

    @Bean
    fun authNHealthIndicator(): HealthIndicator =
        HealthIndicator {
            when (authNService.currentAuthNState()) {
                UNAUTHENTICATED -> Health.status(AUTHN_UNAUTHENTICATED).withDetail("authNState", "no login attempted").build()
                AUTHENTICATED -> Health.status(AUTHN_AUTHENTICATED).withDetail("authNState", "JWT obtained").build()
                DENIED -> Health.status(AUTHN_DENIED).withDetail("authNState", "wrong credentials or IdP coordinates").build()
                RETRYABLE_FAILURE -> Health.status(AUTHN_FAILED_RETRY).withDetail("authNState", "io error (recoverable)").build()
                FAILED -> Health.status(AUTHN_FAILED_PERMANENT).withDetail("authNState", "unrecoverable error").build()
            }
        }

    companion object {
        const val FILE_SYNCHRONIZATION_INDICATOR_NAME = "fileSynchronization"
        const val FILE_SYNCHRONIZATION_STATUS_ENABLED = "ENABLED"
        const val FILE_SYNCHRONIZATION_STATUS_DISABLED = "DISABLED"
        const val CONFIG_INDICATOR_NAME = "config"
        const val CONFIG_BROKEN = "BROKEN"
        const val CONFIG_ERROR = "ERROR"
        const val CONFIG_OK = "OK"
        const val AUTHN_INDICATOR_NAME = "authN"
        const val AUTHN_AUTHENTICATED = "AUTHENTICATED"
        const val AUTHN_UNAUTHENTICATED = "UNAUTHENTICATED"
        const val AUTHN_DENIED = "DENIED"
        const val AUTHN_FAILED_RETRY = "FAILED_RETRY"
        const val AUTHN_FAILED_PERMANENT = "FAILED_PERMANENT"
    }
}
