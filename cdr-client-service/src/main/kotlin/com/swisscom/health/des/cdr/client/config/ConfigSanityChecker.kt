package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter
import jakarta.annotation.PostConstruct
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
internal class ConfigSanityChecker(
    private val configurationWriter: ConfigurationWriter,
    private val environment: Environment
) {
    @PostConstruct
    fun checkConfig() {
        val activeProfiles = environment.activeProfiles.toList()
        if (!activeProfiles.contains("test")) {
            when (configurationWriter.isWritableConfigurationItem("dummy")) {
                ConfigurationWriter.ConfigLookupResult.MultipleOrigins -> System.setProperty("client.file-synchronization-enabled", "false")
                else -> {}
            }
        }
    }
}
