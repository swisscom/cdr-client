package com.swisscom.health.des.cdr.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.swisscom.health.des.cdr.client.common.Constants.SHUTDOWN_DELAY
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.DIR_READ_WRITABLE
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.DIR_SINGLE_USE
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.toCdrClientConfig
import com.swisscom.health.des.cdr.client.config.toDto
import com.swisscom.health.des.cdr.client.handler.ConfigValidationService
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter
import com.swisscom.health.des.cdr.client.handler.ShutdownService
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_DISABLED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_ENABLED
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.SystemHealth
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * This is not a public API. So RPC style http endpoints will suffice for now. The client of this
 * HTTP API is the cdr-client-ui module.
 */
@RestController
internal class WebOperations(
    private val healthEndpoint: HealthEndpoint,
    private val objectMapper: ObjectMapper,
    private val shutdownService: ShutdownService,
    private val configWriter: ConfigurationWriter,
    private val config: CdrClientConfig,
    private val configValidationService: ConfigValidationService,
) {

    internal class ServerError(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    internal class BadRequest(
        message: String,
        cause: Throwable? = null,
        val props: Map<String, Any>? = null
    ) : Exception(message, cause)

    @ExceptionHandler
    internal fun handleError(error: ServerError): ProblemDetail {
        logger.error(error.cause) { "server error: $error" }
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR).apply {
            detail = error.message
        }
    }

    @ExceptionHandler
    internal fun handleError(error: BadRequest): ProblemDetail {
        logger.debug { "bad request: $error" }
        return ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            detail = error.message
            properties = error.props
        }
    }

    /*
     * BEGIN - (Configuration) Validation Endpoints
     *
     * All validation endpoints return a list of message keys if any validation has failed. The configuration item that has been validated
     * is known to the caller, and the caller can thus assemble the full [DTOs.ValidationDetail] by combining its own data with the message keys.
     */

    /**
     * Validates if the value of the `value` query parameter is not blank.
     *
     * @return a list of [DTOs.ValidationMessageKey] if the value is null or blank, or an empty list
     * if the value contains any text characters
     */
    @GetMapping("api/validate-not-blank")
    internal suspend fun validateIsNotBlank(
        @RequestParam(name = "value") value: String?,
    ): ResponseEntity<List<DTOs.ValidationMessageKey>> = runCatching {
        logger.trace { "validating string value: '$value'" }
        val validationResult: DTOs.ValidationResult = configValidationService.validateIsNotBlank(value, DomainObjects.ConfigurationItem.UNKNOWN)
        ResponseEntity
            .ok(
                if (validationResult is DTOs.ValidationResult.Failure)
                    validationResult.validationDetails.map { it.messageKey }
                else
                    emptyList()
            )
    }.getOrElse { error: Throwable ->
        throw ServerError("Failed to validate value '$value': '$error'", error)
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
    ): ResponseEntity<DTOs.ValidationResult> = runCatching {
        logger.debug { "validating dir: '$directory', validations: '$validations'" }
        var validationResult: DTOs.ValidationResult = DTOs.ValidationResult.Success
        for (validation in validations) {
            validationResult +=
                when (validation) {
                    DIR_READ_WRITABLE -> {
                        configValidationService.validateDirectoryIsReadWritable(directory)
                    }

                    DIR_SINGLE_USE -> {
                        configValidationService.validateDirectoryOverlap(config)
                    }
                }
        }
        ResponseEntity
            .ok(
                validationResult
            )
    }.getOrElse { error: Throwable ->
        throw ServerError("Failed to validate directory: '$directory'", error)
    }

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
     * or a [BadRequest] if the configuration is invalid
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
                    throw BadRequest(
                        message = "Invalid configuration",
                        props = result.errors
                    )
                }
            }
        }
    }.getOrElse { error: Throwable ->
        when (error) {
            is BadRequest -> throw error
            else -> throw ServerError("Failed to update service configuration: $error", error)
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
    @GetMapping("api/status")
    internal suspend fun status(): ResponseEntity<DTOs.StatusResponse> = runCatching {
        val healthStatus: SystemHealth = healthEndpoint.health() as SystemHealth
        logger.debug { "Health endpoint response: '${objectMapper.writeValueAsString(healthStatus)}'" }


        val status = if (configValidationService.isConfigValid) {
            when (healthStatus.components[FILE_SYNCHRONIZATION_INDICATOR_NAME]?.status?.code) {
                FILE_SYNCHRONIZATION_STATUS_ENABLED -> DTOs.StatusResponse.StatusCode.SYNCHRONIZING
                FILE_SYNCHRONIZATION_STATUS_DISABLED -> DTOs.StatusResponse.StatusCode.DISABLED
                else -> DTOs.StatusResponse.StatusCode.UNKNOWN
                // TODO: add checks for error scenarios: configuration errors, CDR API not reachable, IdP not reachable, etc.
                //   all other error scenarios would probably prevent the client service from starting altogether;
                //   remember result of last attempt to reach remote endpoints instead of pinging them?
            }
        } else {
            DTOs.StatusResponse.StatusCode.ERROR
        }

        return ResponseEntity.ok(
            DTOs.StatusResponse(
                statusCode = status,
            )
        )
    }.getOrElse { error: Throwable ->
        throw ServerError("Failed to retrieve service status: $error", error)
    }

    /**
     * This endpoint essentially does the same thing as the `shutdown` actuator, only it derives
     * an exit code from the reason provided in the query parameter.
     *
     * @param reason the reason for the shutdown, which is used to determine the exit code
     * @return a [DTOs.ShutdownResponse] containing the scheduled shutdown time, the trigger, and
     * the exit code
     * @throws [BadRequest] if the reason is invalid or blank
     */
    @GetMapping("/api/shutdown")
    internal suspend fun shutdown(
        @RequestParam(name = "reason") reason: String?
    ): ResponseEntity<DTOs.ShutdownResponse> = runCatching {
        val shutdownTrigger = if (reason.isNullOrBlank()) ShutdownService.ShutdownTrigger.UNKNOWN else ShutdownService.ShutdownTrigger.fromReason(reason)

        val response: ResponseEntity<DTOs.ShutdownResponse> =
            when (shutdownTrigger) {
                ShutdownService.ShutdownTrigger.UNKNOWN -> {
                    throw BadRequest(
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
            is BadRequest -> throw error
            else -> throw ServerError("Failed to schedule shutdown: $error", error)
        }
    }

}
