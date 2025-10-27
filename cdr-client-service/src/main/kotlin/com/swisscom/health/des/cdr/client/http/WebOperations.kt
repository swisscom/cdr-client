package com.swisscom.health.des.cdr.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.swisscom.health.des.cdr.client.common.Constants.SHUTDOWN_DELAY
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.CREDENTIAL_VALIDATION_FAILED
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationResult
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.DIR_READ_WRITABLE
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.DIR_SINGLE_USE
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.MODE_OVERLAP
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.MODE_VALUE
import com.swisscom.health.des.cdr.client.common.getRootestCause
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService
import com.swisscom.health.des.cdr.client.config.toCdrClientConfig
import com.swisscom.health.des.cdr.client.config.toDto
import com.swisscom.health.des.cdr.client.handler.ConfigValidationService
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter
import com.swisscom.health.des.cdr.client.handler.ShutdownService
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_AUTHENTICATED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_COMMUNICATION_ERROR
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_DENIED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_UNAUTHENTICATED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_UNKNOWN_ERROR
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_BROKEN
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_ERROR
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_OK
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_DISABLED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_ENABLED
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.SystemHealth
import org.springframework.http.ResponseEntity
import org.springframework.retry.RetryContext
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.nio.file.Path
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * This is not a public API. So RPC style http endpoints will suffice for now. The client of this
 * HTTP API is the cdr-client-ui module.
 */
@RestController
@Suppress("LongParameterList")
internal class WebOperations(
    private val healthEndpoint: HealthEndpoint,
    private val objectMapper: ObjectMapper,
    private val shutdownService: ShutdownService,
    private val configWriter: ConfigurationWriter,
    private val config: CdrClientConfig,
    private val configValidationService: ConfigValidationService,
    @param:Qualifier("retryIoAndServerErrors")
    private val retryIOExceptionsAndServerErrors: RetryTemplate,
    private val authService: OAuth2AuthNService,
) {
    /*
     * BEGIN - (Configuration) Validation Endpoints
     *
     * All validation endpoints return a list of message keys if any validation has failed. The configuration item that has been validated
     * is known to the caller, and the caller can thus assemble the full [DTOs.ValidationDetail] by combining its own data with the message keys.
     */

    @PutMapping("api/validate-connector-mode")
    internal suspend fun validateConnectorMode(
        @RequestBody connectors: List<DTOs.CdrClientConfig.Connector>,
        @RequestParam(name = "validation") validations: List<DomainObjects.ValidationType>,
    ): ResponseEntity<ValidationResult> = runCatching {
        logger.debug { "validating mode for connectors: '$connectors'" }
        var validationResult: ValidationResult = ValidationResult.Success
        for (validation in validations) {
            validationResult +=
                when (validation) {
                    MODE_VALUE -> {
                        configValidationService.validateModeValue(connectors)
                    }

                    MODE_OVERLAP -> {
                        configValidationService.validateModeOverlap(connectors)
                    }

                    else -> throw WebOperationsAdvice.BadRequest("Unsupported validation type: '$validation'")
                }
        }
        ResponseEntity
            .ok(
                validationResult
            )
    }.getOrElse { error: Throwable ->
        when (error) {
            is WebOperationsAdvice.ServerError, is WebOperationsAdvice.BadRequest -> throw error
            else -> throw WebOperationsAdvice.ServerError("Failed to validate mode for connectors: '$connectors'", error)
        }
    }

    /**
     * Validates if the value of the `value` query parameter is not blank.
     *
     * @return a list of [DTOs.ValidationMessageKey] if the value is null or blank, or an empty list
     * if the value contains any text characters
     */
    @GetMapping("api/validate-not-blank-and-not-placeholder")
    internal suspend fun validateIsNotBlankAndNotPlaceholder(
        @RequestParam(name = "value") value: String?,
    ): ResponseEntity<List<DTOs.ValidationMessageKey>> = runCatching {
        logger.trace { "validating string value: '$value'" }
        val validationResult: ValidationResult = configValidationService.validateIsNotBlankOrPlaceholder(value, DomainObjects.ConfigurationItem.UNKNOWN)
        ResponseEntity
            .ok(
                if (validationResult is ValidationResult.Failure)
                    validationResult.validationDetails.map { it.messageKey }
                else
                    emptyList()
            )
    }.getOrElse { error: Throwable ->
        when (error) {
            is WebOperationsAdvice.ServerError, is WebOperationsAdvice.BadRequest -> throw error
            else -> throw WebOperationsAdvice.ServerError("Failed to validate value '$value': '$error'", error)
        }
    }

    /**
     * Validates if the given directory is readable and writable, and/or if it is a single-use directory,
     * depending on the types of validation requested.
     *
     * All requested validations are performed in the order they are specified in the `validations`
     * query parameter and their results are combined.
     *
     * @param config the CDR Client configuration to use for validating single use of directories
     * @param directory the directory to validate
     * @param validations the list of validation types to perform on the directory
     * @return a [DTOs.ValidationResult] indicating the result of the validation
     */
    @PutMapping("api/validate-directory")
    internal suspend fun validateDirectory(
        @RequestBody config: DTOs.CdrClientConfig,
        @RequestParam(name = "dir") directory: Path,
        @RequestParam(name = "validation") validations: List<DomainObjects.ValidationType>,
    ): ResponseEntity<ValidationResult> = runCatching {
        logger.debug { "validating dir: '$directory', validations: '$validations'" }
        var validationResult: ValidationResult = ValidationResult.Success
        for (validation in validations) {
            validationResult +=
                when (validation) {
                    DIR_READ_WRITABLE -> {
                        configValidationService.validateDirectoryIsReadWritable(directory)
                    }

                    DIR_SINGLE_USE -> {
                        configValidationService.validateDirectoryOverlap(config)
                    }

                    else -> throw WebOperationsAdvice.BadRequest("Unsupported validation type: '$validation'")
                }
        }
        ResponseEntity
            .ok(
                validationResult
            )
    }.getOrElse { error: Throwable ->
        when (error) {
            is WebOperationsAdvice.ServerError, is WebOperationsAdvice.BadRequest -> throw error
            else -> throw WebOperationsAdvice.ServerError("Failed to validate directory: '$directory'", error)
        }
    }

    @PutMapping("api/validate-credentials")
    internal suspend fun validateCredentials(
        @RequestBody idpCredentials: DTOs.CdrClientConfig.IdpCredentials,
    ): ResponseEntity<ValidationResult> = runCatching {
        retryIOExceptionsAndServerErrors.execute<OAuth2AuthNService.AuthNResponse, Exception> { retry: RetryContext ->
            val idpEndpoint = config.idpEndpoint.toString()
            val correctedIdpEndpoint = if (config.idpCredentials.tenantId.id == idpCredentials.tenantId)
                idpEndpoint
            else // only replace the first path segment after the host with the tenant id from the provided credentials
                idpEndpoint.replace(Regex("(http[s]?://[^/]+/)[^/]+"), "$1${idpCredentials.tenantId}")

            if (retry.retryCount > 0) {
                logger.debug {
                    "Operation targeting '$correctedIdpEndpoint' has failed; exception: " +
                            "'${retry.lastThrowable?.let { getRootestCause(it)::class.java }}'; message: '${retry.lastThrowable?.message}'"
                }
                logger.info {
                    "Retry attempt '#${retry.retryCount}' of 'validate credentials' operation targeting " +
                            "'$correctedIdpEndpoint'"
                }
            }
            logger.trace { "validating credentials" }

            authService.getNewAccessToken(idpCredentials.toCdrClientConfig(), URI(correctedIdpEndpoint).toURL())
        }
    }.fold(
        onSuccess = { authNResponse: OAuth2AuthNService.AuthNResponse ->
            var validationResult: ValidationResult = ValidationResult.Success
            when (authNResponse) {
                is OAuth2AuthNService.AuthNResponse.Success -> {

                }

                else -> {
                    validationResult += credentialValidationFailure
                }
            }
            ResponseEntity.ok(validationResult)
        },
        onFailure = { e ->
            logger.error { "Failed to validate credentials: $e" }
            ResponseEntity.ok(credentialValidationFailure)
        }
    )

    private val credentialValidationFailure = ValidationResult.Failure(
        listOf(
            DTOs.ValidationDetail.ConfigItemDetail(
                configItem = DomainObjects.ConfigurationItem.UNKNOWN,
                messageKey = CREDENTIAL_VALIDATION_FAILED
            )
        )
    )

    //
    // END - (Configuration) Validation Endpoints
    //

    /**
     * This endpoint returns the current CDR Client service configuration as a [DTOs.CdrClientConfig] object.
     */
    @GetMapping("api/service-configuration")
    suspend fun getServiceConfiguration(): ResponseEntity<DTOs.CdrClientConfig> =
        ResponseEntity
            .ok(
                config.toDto()
            )

    /**
     * This endpoint updates the current CDR Client service configuration with the provided
     * [DTOs.CdrClientConfig] object.
     *
     * @param config the new configuration to apply
     * @return the configuration as it was received in the request body if the update was successful,
     * or a [WebOperationsAdvice.BadRequest] if the configuration is invalid
     */
    @PutMapping("api/service-configuration")
    internal suspend fun updateServiceConfiguration(
        @RequestBody config: DTOs.CdrClientConfig
    ): ResponseEntity<DTOs.CdrClientConfig> = runCatching {
        logger.trace { "received DTOs.CdrClientConfig: '$config'" }
        configWriter.updateClientServiceConfiguration(config.toCdrClientConfig()).let { result ->
            when (result) {
                is ConfigurationWriter.UpdateResult.Success -> {
                    ResponseEntity
                        .ok(config)
                }

                is ConfigurationWriter.UpdateResult.Failure -> {
                    throw WebOperationsAdvice.BadRequest(
                        message = "Invalid configuration",
                        props = result.errors
                    )
                }
            }
        }
    }.getOrElse { error: Throwable ->
        when (error) {
            is WebOperationsAdvice.ServerError, is WebOperationsAdvice.BadRequest -> throw error
            else -> throw WebOperationsAdvice.ServerError("Failed to update service configuration: $error", error)
        }
    }

    /**
     * This endpoint returns the status of the client service. It uses the health endpoint and
     * the [health indicators][HealthIndicators] registered in the application context. The health
     * status of the different indicators is translated into an application-specific
     * [status code][DTOs.StatusResponse.StatusCode].
     *
     * @return a [DTOs.StatusResponse] containing the status code of the client service
     * @see [HealthIndicators]
     */
    @Suppress("CyclomaticComplexMethod")
    @GetMapping("api/status")
    internal suspend fun status(): ResponseEntity<DTOs.StatusResponse> = runCatching {
        val healthStatus: SystemHealth = healthEndpoint.health() as SystemHealth
        logger.debug { "Health endpoint response: '${objectMapper.writeValueAsString(healthStatus)}'" }

        val configStatus: String? = healthStatus.components[CONFIG_INDICATOR_NAME]?.status?.code
        val syncStatus: String? = healthStatus.components[FILE_SYNCHRONIZATION_INDICATOR_NAME]?.status?.code
        val authNStatus: String? = healthStatus.components[AUTHN_INDICATOR_NAME]?.status?.code

        val status =
            if (!configStatus.isNullOrBlank() && configStatus != CONFIG_OK) {
                when (configStatus) {
                    CONFIG_BROKEN -> DTOs.StatusResponse.StatusCode.BROKEN
                    CONFIG_ERROR -> DTOs.StatusResponse.StatusCode.ERROR
                    else -> DTOs.StatusResponse.StatusCode.UNKNOWN
                }
            } else if (!authNStatus.isNullOrBlank() && !(authNStatus == AUTHN_AUTHENTICATED || authNStatus == AUTHN_UNAUTHENTICATED)) {
                when (authNStatus) {
                    AUTHN_DENIED -> DTOs.StatusResponse.StatusCode.AUTHN_DENIED
                    AUTHN_COMMUNICATION_ERROR -> DTOs.StatusResponse.StatusCode.AUTHN_COMMUNICATION_ERROR
                    AUTHN_UNKNOWN_ERROR -> DTOs.StatusResponse.StatusCode.AUTHN_UNKNOWN_ERROR
                    else -> DTOs.StatusResponse.StatusCode.UNKNOWN
                }
            } else if (!syncStatus.isNullOrBlank()) {
                when (syncStatus) {
                    FILE_SYNCHRONIZATION_STATUS_ENABLED -> DTOs.StatusResponse.StatusCode.SYNCHRONIZING
                    FILE_SYNCHRONIZATION_STATUS_DISABLED -> DTOs.StatusResponse.StatusCode.DISABLED
                    else -> DTOs.StatusResponse.StatusCode.UNKNOWN
                }
            } else {
                DTOs.StatusResponse.StatusCode.UNKNOWN
            }

        return ResponseEntity.ok(
            DTOs.StatusResponse(
                statusCode = status,
            )
        )
    }.getOrElse { error: Throwable ->
        when (error) {
            is WebOperationsAdvice.ServerError, is WebOperationsAdvice.BadRequest -> throw error
            else -> throw WebOperationsAdvice.ServerError("Failed to retrieve service status: $error", error)
        }
    }

    /**
     * This endpoint essentially does the same thing as the `shutdown` actuator, only it derives
     * an exit code from the reason provided in the query parameter.
     *
     * @param reason the reason for the shutdown, which is used to determine the exit code
     * @return a [DTOs.ShutdownResponse] containing the scheduled shutdown time, the trigger, and
     * the exit code
     * @throws [WebOperationsAdvice.BadRequest] if the reason is invalid or blank
     */
    @GetMapping("/api/shutdown")
    internal suspend fun shutdown(
        @RequestParam(name = "reason") reason: String?
    ): ResponseEntity<DTOs.ShutdownResponse> = runCatching {
        val shutdownTrigger = if (reason.isNullOrBlank()) ShutdownService.ShutdownTrigger.UNKNOWN else ShutdownService.ShutdownTrigger.fromReason(reason)

        val response: ResponseEntity<DTOs.ShutdownResponse> =
            when (shutdownTrigger) {
                ShutdownService.ShutdownTrigger.UNKNOWN -> {
                    throw WebOperationsAdvice.BadRequest(
                        message = "Invalid shutdown reason: '$reason'",
                    )
                }

                else -> {
                    ResponseEntity
                        .ok(
                            DTOs.ShutdownResponse(
                                shutdownScheduledFor = Instant.now().plus(SHUTDOWN_DELAY),
                                trigger = shutdownTrigger.name,
                                exitCode = shutdownTrigger.exitCode
                            )
                        )
                        .also {
                            shutdownService.scheduleShutdown(shutdownTrigger)
                        }
                }
            }

        return response
    }.getOrElse { error: Throwable ->
        when (error) {
            is WebOperationsAdvice.ServerError, is WebOperationsAdvice.BadRequest -> throw error
            else -> throw WebOperationsAdvice.ServerError("Failed to schedule shutdown: $error", error)
        }
    }

}
