package com.swisscom.health.des.cdr.client.scheduling

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.ClientSecretRenewalService
import com.swisscom.health.des.cdr.client.handler.withSpan
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.context.refresh.ContextRefresher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
@ConditionalOnProperty(prefix = "client.idp-credentials", name = ["renew-credential-at-startup"], havingValue = "true")
class ClientSecretRenewalScheduler(
    private val clientSecretRenewalService: ClientSecretRenewalService,
    @Qualifier("configDataContextRefresher")
    private val contextRefresher: ContextRefresher,
    private val config: CdrClientConfig,
    private val tracer: Tracer,
) {

    @PostConstruct
    fun reportIn() = logger.info { "Automatic client secret renewal is '${if (config.idpCredentials.renewCredentialAtStartup) "on" else "off"}'" }

    @Scheduled(fixedDelayString = "365d", initialDelayString = "1s")
    fun renewClientSecret(): Unit = tracer.withSpan("Renew Client Secret") {
        logger.info { "Renewing client secret..." }
        val result = clientSecretRenewalService.renewClientSecret()
        when (result) {
            is ClientSecretRenewalService.RenewClientSecretResult.Success -> {
                logger.debug { "Resource containing secret: '${result.secretSource}'" }
                logger.debug { "Refreshing Spring context due to configuration update" }
                contextRefresher.refresh().also { refreshedKeys ->
                    logger.debug { "Refreshed keys: $refreshedKeys" }
                }
                logger.info { "Renewing client secret done." }
            }

            is ClientSecretRenewalService.RenewClientSecretResult.RenewError -> {
                logger.error { "Failed to renew client secret: '${result.cause.message}'" }
            }
        }
    }

}
