package com.swisscom.health.des.cdr.client.ui

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.DIR_READ_WRITABLE
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.DIR_SINGLE_USE
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.MODE_VALUE
import com.swisscom.health.des.cdr.client.common.DomainObjects.ValidationType.MODE_OVERLAP
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

private typealias ValidationErrorHandler = suspend (Map<String, Any>, DomainObjects.ConfigurationItem) -> DTOs.ValidationResult
private typealias ValidationSuccessHandler<T> = suspend (T, DomainObjects.ConfigurationItem) -> DTOs.ValidationResult

/**
 * Wrapper around the [CdrClientApiClient] that translates the API responses into [DTOs.ValidationResult]s.
 */
internal class CdrConfigViewRemoteValidations(
    private val cdrClientApiClient: CdrClientApiClient,
) {

    internal suspend fun validateConnectorMode(
        connectorId: String,
        config: DTOs.CdrClientConfig,
        fieldName: DomainObjects.ConfigurationItem,
    ): DTOs.ValidationResult =
        cdrClientApiClient.validateConnectorMode(
            validations = listOf(MODE_VALUE, MODE_OVERLAP),
            connectors = config.customer,
        ).handle(
            configurationItem = fieldName,
            onSuccess = { validationResult: DTOs.ValidationResult, _ -> validationResult },
        ).run {
            when (this) {
                is DTOs.ValidationResult.Success -> this
                is DTOs.ValidationResult.Failure -> {
                    // check if any connector validation error is matching the connector we are validating; if yes, return the failure
                    if (validationDetails
                            .any { validationDetail: DTOs.ValidationDetail ->
                                validationDetail is DTOs.ValidationDetail.ConnectorDetail && validationDetail.connectorId == connectorId
                            }
                    )
                        this
                    // ignore connector validation errors that do not affect the connector we are validating
                    else
                        DTOs.ValidationResult.Success
                }
            }
        }

    /**
     * Validates that the given value is not blank.
     *
     * @param value the value to validate
     * @param fieldName the name of the field being validated
     * @return a [DTOs.ValidationResult] indicating success or failure
     * @see DomainObjects.ConfigurationItem
     */
    internal suspend fun validateNotBlank(
        value: String,
        fieldName: DomainObjects.ConfigurationItem,
    ): DTOs.ValidationResult =
        cdrClientApiClient.validateValueIsNotBlank(value = value)
            .handle(
                configurationItem = fieldName,
                onSuccess = foldIntoValidationResult
            )

    /**
     * Validates that the given path is a directory that is readable and writable, and that it is a
     * single-use directory.
     *
     * @param config the CDR client configuration
     * @param path the path to validate
     * @param fieldName the name of the field being validated
     * @return a [DTOs.ValidationResult] indicating [DTOs.ValidationResult.Success] or
     * [DTOs.ValidationResult.Failure]
     * @see DomainObjects.ConfigurationItem
     */
    internal suspend fun validateDirectory(
        config: DTOs.CdrClientConfig,
        path: String?,
        fieldName: DomainObjects.ConfigurationItem
    ): DTOs.ValidationResult =
        cdrClientApiClient.validateDirectory(
            config = config,
            directory = path,
            validations = listOf(DIR_READ_WRITABLE, DIR_SINGLE_USE)
        ).handle(
            configurationItem = fieldName,
            onSuccess = { validationResult: DTOs.ValidationResult, _ -> validationResult }
        ).run {
            when (this) {
                is DTOs.ValidationResult.Success -> this
                is DTOs.ValidationResult.Failure -> {
                    // check if any path validation error is matching the path we are validating; if yes, return the failure
                    if (validationDetails
                            .any { validationDetail: DTOs.ValidationDetail ->
                                validationDetail is DTOs.ValidationDetail.PathDetail && path != null && Path.of(validationDetail.path) == Path.of(path)
                            }
                    )
                        this
                    // ignore path validation errors that do not affect the path we are validating
                    else
                        DTOs.ValidationResult.Success
                }
            }
        }

    private suspend fun <U> CdrClientApiClient.Result<U>.handle(
        configurationItem: DomainObjects.ConfigurationItem,
        onIOError: ValidationErrorHandler = loggingErrorHandler,
        onServiceError: ValidationErrorHandler = loggingErrorHandler,
        onSuccess: ValidationSuccessHandler<U>,
    ): DTOs.ValidationResult =
        when (this) {
            is CdrClientApiClient.Result.Success -> onSuccess(response, configurationItem)
            is CdrClientApiClient.Result.IOError -> onIOError(errors, configurationItem)
            is CdrClientApiClient.Result.ServiceError -> onServiceError(errors, configurationItem)
        }

    private val foldIntoValidationResult: ValidationSuccessHandler<List<DTOs.ValidationMessageKey>> =
        { errorMessageKeys: List<DTOs.ValidationMessageKey>, fieldName: DomainObjects.ConfigurationItem ->
            if (errorMessageKeys.isNotEmpty()) {
                val validationDetails = errorMessageKeys.map { errorKeyString ->
                    DTOs.ValidationDetail.ConfigItemDetail(
                        configItem = fieldName,
                        messageKey = errorKeyString
                    )
                }
                DTOs.ValidationResult.Failure(validationDetails)
            } else {
                DTOs.ValidationResult.Success
            }
        }

    private val loggingErrorHandler: ValidationErrorHandler = { errorMap, fieldName ->
        logger.warn { "Remote validation of configuration item '$fieldName' has failed; details: '$errorMap'" }
        DTOs.ValidationResult.Success // technical problems with validation should not have an impact on the UI state -> report success
    }

}
