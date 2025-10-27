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
    cdrApiClient: CdrApiClient,
) {

    val isSchedulingAllowed: Boolean by lazy { isConfigSourceUnambiguous && configValidationService.isConfigValid }
    val isConfigSourceUnambiguous: Boolean by lazy { isConfigFromOneSource() }
    @Volatile
    // TODO: will also return false if the Microsoft endpoint is temporarily unreachable and therefore a wrong error message is displayed to the user
    var areCredentialsValid: Boolean = false

    init {
        cdrApiClient.onCredentialValidation = { valid -> areCredentialsValid = valid }
    }

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
