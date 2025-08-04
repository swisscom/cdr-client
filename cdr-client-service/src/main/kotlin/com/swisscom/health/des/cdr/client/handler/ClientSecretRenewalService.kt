package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.ClientSecret
import com.swisscom.health.des.cdr.client.config.LastCredentialRenewalTime
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The service renews the client secret via the CDR API. If the secret was successfully renewed,
 * it is stored in the client's configuration using the [ConfigurationWriter].
 *
 *
 * @see [ConfigurationWriter]
 * @see [com.swisscom.health.des.cdr.client.scheduling.ClientSecretRenewalScheduler]
 */
@Service
internal class ClientSecretRenewalService(
    private val config: CdrClientConfig,
    private val configurationWriter: ConfigurationWriter,
    private val cdrApiClient: CdrApiClient,
    private val tracer: Tracer
) {
    sealed interface RenewClientSecretResult {
        object Success : RenewClientSecretResult
        object Failure : RenewClientSecretResult
    }

    fun renewClientSecret(): RenewClientSecretResult =
        runCatching {
            val secretIsWritable = ensureConfigItemIsWritable(CLIENT_SECRET_PROPERTY_PATH)
            val timestampIsWritable = ensureConfigItemIsWritable(CLIENT_SECRET_LAST_UPDATE_PROPERTY_PATH)

            if (secretIsWritable && timestampIsWritable) {
                writeNewSecret(getNewSecret())
            } else {
                RenewClientSecretResult.Failure
            }
        }.fold(
            onSuccess = { it },
            onFailure = { error: Throwable ->
                RenewClientSecretResult.Failure.also {
                    logger.error(error) { "Exception while trying to renew secret: $error" }
                }
            }
        )

    private fun ensureConfigItemIsWritable(property: String): Boolean =
        when (configurationWriter.isWritableConfigurationItem(property)) {
            is ConfigurationWriter.ConfigLookupResult.Writable -> true

            is ConfigurationWriter.ConfigLookupResult.NotFound -> false.also {
                logger.info { "No updatable configuration item found with property path '$property'" }
            }

            is ConfigurationWriter.ConfigLookupResult.NotWritable -> false.also {
                logger.info { "Configuration item with property path '$property' is not sourced from a writable resource.'" }
            }

            is ConfigurationWriter.ConfigLookupResult.Ambiguous -> false.also {
                logger.info { "Configuration item with property path '$property' has multiple origins.'" }
            }
        }

    private fun writeNewSecret(secret: String): RenewClientSecretResult {
        val newIdpCredentials = config.idpCredentials.copy(
            clientSecret = ClientSecret(secret),
            lastCredentialRenewalTime = LastCredentialRenewalTime(Instant.now()),
        )
        val newCdrConfig = config.copy(
            idpCredentials = newIdpCredentials
        )

        return configurationWriter.updateClientServiceConfiguration(newCdrConfig).let { updateResult ->
            when (updateResult) {
                is ConfigurationWriter.UpdateResult.Failure -> RenewClientSecretResult.Failure.also {
                    logger.error {
                        "Failed to update client service configuration with new client secret: '${updateResult.errors}'\n" +
                                "Your previous secret has already been expired. You must create a new secret on the CDR Website and then set the " +
                                "retrieved secret value via the 'CDR Client Admin' app."
                    }
                }

                is ConfigurationWriter.UpdateResult.Success -> RenewClientSecretResult.Success
            }
        }
    }

    private fun getNewSecret(): String =
        cdrApiClient.renewClientCredential(
            traceId = tracer.currentSpan()?.context()?.traceId() ?: EMPTY_STRING
        ).run {
            when (this) {
                is CdrApiClient.RenewClientSecretResult.Success -> this.clientSecret
                is CdrApiClient.RenewClientSecretResult.RenewHttpErrorResponse -> error("http error; status code: $code")
                is CdrApiClient.RenewClientSecretResult.RenewError -> throw this.cause
            }
        }

    companion object {
        const val CLIENT_PROPERTY = "client"
        const val IDP_CREDENTIALS_PROPERTY = "idp-credentials"
        const val CLIENT_SECRET_PROPERTY = "client-secret"
        const val CLIENT_SECRET_LAST_UPDATE_PROPERTY = "last-credential-renewal-time"
        const val CLIENT_SECRET_PROPERTY_PATH = "$CLIENT_PROPERTY.$IDP_CREDENTIALS_PROPERTY.$CLIENT_SECRET_PROPERTY"
        const val CLIENT_SECRET_LAST_UPDATE_PROPERTY_PATH = "$CLIENT_PROPERTY.$IDP_CREDENTIALS_PROPERTY.$CLIENT_SECRET_LAST_UPDATE_PROPERTY"
    }

}
