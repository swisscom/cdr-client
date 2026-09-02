package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter.UpdatableConfigurationItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
internal class SchedulingValidationService(
    private val configValidationService: ConfigValidationService,
    private val environment: Environment,
    private val configurationWriter: ConfigurationWriter,
    private val currentConfig: CdrClientConfig,
) {

    val isSchedulingAllowed: Boolean by lazy { isConfigSourceUnambiguous && configValidationService.isConfigValid }
    val isConfigSourceUnambiguous: Boolean by lazy { isConfigFromOneSource() }

    @Suppress("UnusedPrivateMember")
    @EventListener(ApplicationReadyEvent::class)
    private fun logFileSyncDisabledAfterStartup() {
        if (!currentConfig.fileSynchronizationEnabled.value) {
            logger.warn { "File synchronization is disabled by configuration." }
        }
    }

    private fun isConfigFromOneSource(): Boolean {
        val activeProfiles = environment.activeProfiles.toList()
        return activeProfiles.contains("test") || isWriteableConfigurationUnambiguous()
    }

    fun isWriteableConfigurationUnambiguous() =
        configurationWriter.collectUpdatableConfigurationItems(currentConfig, currentConfig)
            .none { it is UpdatableConfigurationItem.AmbiguousWritableSource }
}
