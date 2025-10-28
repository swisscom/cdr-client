package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter.UpdatableConfigurationItem
import jakarta.annotation.PostConstruct
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

@Service
internal class SchedulingValidationService(
    private val configValidationService: ConfigValidationService,
    private val environment: Environment,
    private val configurationWriter: ConfigurationWriter,
    private val currentConfig: CdrClientConfig,
) {

    val isSchedulingAllowed: Boolean by lazy { isConfigSourceUnambiguous && configValidationService.isConfigValid }
    val isConfigSourceUnambiguous: Boolean by lazy { isConfigFromOneSource() }

    @Suppress("unused")
    @PostConstruct
    private fun isConfigFromOneSource(): Boolean {
        val activeProfiles = environment.activeProfiles.toList()
        return if (!activeProfiles.contains("test")) {
            isWriteableConfigurationUnambiguous()
        } else {
            true
        }
    }

    fun isWriteableConfigurationUnambiguous() =
        configurationWriter.collectUpdatableConfigurationItems(currentConfig, currentConfig)
            .none { it is UpdatableConfigurationItem.AmbiguousWritableSource }
}
