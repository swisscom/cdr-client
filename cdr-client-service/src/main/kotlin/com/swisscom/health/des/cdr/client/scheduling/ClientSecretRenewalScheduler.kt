package com.swisscom.health.des.cdr.client.scheduling

import com.swisscom.health.des.cdr.client.handler.ClientSecretRenewalService
import com.swisscom.health.des.cdr.client.handler.ShutdownService
import com.swisscom.health.des.cdr.client.handler.withSpan
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
@ConditionalOnProperty(prefix = "client.idp-credentials", name = ["renew-credential"], havingValue = "true")
internal class ClientSecretRenewalScheduler(
    private val clientSecretRenewalService: ClientSecretRenewalService,
    private val shutdownService: ShutdownService,
    private val tracer: Tracer,
) {

    @PostConstruct
    fun reportIn() = logger.info { "Automatic client secret renewal is active!" }

    @Scheduled(
        fixedDelayString = "#{ @'client-com.swisscom.health.des.cdr.client.config.CdrClientConfig'.getIdpCredentials().getMaxCredentialAge().toMillis() }",
        initialDelayString= "#{ @'client-com.swisscom.health.des.cdr.client.config.CdrClientConfig'.getIdpCredentials().getMillisUntilNextCredentialRenewal() }"
    )
    fun renewClientSecret(): Unit = tracer.withSpan("Renew Client Secret") {
        logger.info { "Renewing client secret..." }
        val result = clientSecretRenewalService.renewClientSecret()
        when (result) {
            is ClientSecretRenewalService.RenewClientSecretResult.Success -> {
                logger.debug { "Refreshing Spring context due to configuration update" }
                logger.info { "Renewing client secret done." }
                shutdownService.scheduleShutdown(ShutdownService.ShutdownTrigger.CONFIG_CHANGE)
            }

            is ClientSecretRenewalService.RenewClientSecretResult.Failure -> {
                logger.error { "Failed to renew client secret" }
            }
        }
    }

}
