package com.swisscom.health.des.cdr.client.http

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class HealthIndicators(
    private val config: CdrClientConfig
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

    companion object {
        const val FILE_SYNCHRONIZATION_INDICATOR_NAME = "fileSynchronization"
        const val FILE_SYNCHRONIZATION_STATUS_ENABLED = "ENABLED"
        const val FILE_SYNCHRONIZATION_STATUS_DISABLED = "DISABLED"
    }
}
