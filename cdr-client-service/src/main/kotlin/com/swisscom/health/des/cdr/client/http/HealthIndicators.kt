package com.swisscom.health.des.cdr.client.http

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.ConfigValidationService
import com.swisscom.health.des.cdr.client.handler.SchedulingValidationService
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class HealthIndicators(
    private val config: CdrClientConfig,
    private val configValidationService: ConfigValidationService,
    private val schedulingValidationService: SchedulingValidationService
) {

    /**
     * A custom health indicator that checks if file synchronization is enabled or disabled.
     */
    @Bean
    fun fileSynchronizationHealthIndicator(): HealthIndicator =
        HealthIndicator {
            when (config.fileSynchronizationEnabled.value) {
                true -> Health.Builder(Status(FILE_SYNCHRONIZATION_STATUS_ENABLED)).withDetail("fileSynchronizationEnabled", true)
                false -> Health.Builder(Status(FILE_SYNCHRONIZATION_STATUS_DISABLED)).withDetail("fileSynchronizationEnabled", false)
            }.build()
        }

    @Bean
    fun configHealthIndicator(): HealthIndicator =
        HealthIndicator {
            when {
                !schedulingValidationService.isConfigSourceUnambiguous -> Health.Builder(Status(CONFIG_BROKEN))
                    .withDetail("configStatus", "ambiguous config source")

                !configValidationService.isConfigValid -> Health.Builder(Status(CONFIG_ERROR))
                    .withDetail("configStatus", "invalid config")

                else -> Health.Builder(Status(CONFIG_OK)).withDetail("configStatus", "ok")
            }.build()
        }

    companion object {
        const val FILE_SYNCHRONIZATION_INDICATOR_NAME = "fileSynchronization"
        const val FILE_SYNCHRONIZATION_STATUS_ENABLED = "ENABLED"
        const val FILE_SYNCHRONIZATION_STATUS_DISABLED = "DISABLED"
        const val CONFIG_INDICATOR_NAME = "config"
        const val CONFIG_BROKEN = "BROKEN"
        const val CONFIG_ERROR = "ERROR"
        const val CONFIG_OK = "OK"
    }
}
