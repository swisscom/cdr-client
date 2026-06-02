package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.ERROR_DIR_NAME
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig.Connector
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationDetail
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DIRECTORY_NEEDS_ABSOLUTE_PATH
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DIRECTORY_NOT_FOUND
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DUPLICATE_MODE
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.FILE_BUSY_TEST_TIMEOUT_TOO_LONG
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.ILLEGAL_MODE
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.ILLEGAL_VALUE
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.ILLEGAL_VALUE_COMBINATION
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.NOT_A_DIRECTORY
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.NOT_READ_WRITABLE
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.NO_CONNECTOR_CONFIGURED
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.VALUE_IS_BLANK
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.VALUE_IS_PLACEHOLDER
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationResult
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.CONNECTOR
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.CONNECTOR_MODE
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.FILE_BUSY_TEST_TIMEOUT
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.toCdrClientConfig
import com.swisscom.health.des.cdr.client.config.toDto
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.isWritable

private val logger = KotlinLogging.logger {}

@Service
@Suppress("TooManyFunctions", "LargeClass")
internal class ConfigValidationService(
    private val config: CdrClientConfig,
) {

    val isConfigValid: Boolean by lazy { validateAllConfigurationItems(config) is ValidationResult.Success }

    /**
     * Validates the complete rule set.
     */
    fun validateAllConfigurationItems(config: CdrClientConfig): ValidationResult {
        val validations = mutableListOf<ValidationResult>()

        with(config.toDto()) {
            validations.add(validateCdrApiEndpoint(cdrApi, idpCredentials))
            validations.add(validateDirectoryIsReadWritable(localFolder))
            validations.add(validateDirectoryOverlap(this))
            validations.add(validateModeValue(customer))
            validations.add(validateModeOverlap(customer))
            validations.add(validateFileBusyTestTimeout(fileBusyTestTimeout = fileBusyTestTimeout, fileBusyTestInterval = fileBusyTestInterval))
            validations.add(validateConnectorIsPresent(customer))
            validations.add(validateCredentialValues(idpCredentials))
            validations.add(validateConnectorIdIsPresent(customer))
            customer.forEach {
                validations.add(validateConnectorFolders(it))
            }
        }

        return validations.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, validationResult: ValidationResult ->
                acc + validationResult
            }
        ).also {
            if (it is ValidationResult.Failure) {
                logger.warn {
                    """
                    |
                    |#############################################################################################
                    |#############################################################################################
                    |No file upload/download will be possible due to configuration validation failure.
                    |Details: ${it.validationDetails}.
                    |#############################################################################################
                    |#############################################################################################
                    |""".trimMargin()
                }
            }
        }
    }

    fun validateAvailableDiskspace(connectors: List<Connector>): ValidationResult {
        logger.trace { "Validating available disk space for connectors: $connectors" }
        TODO()
    }

    @Suppress("CyclomaticComplexMethod")
    fun validateCdrApiEndpoint(cdrApiEndpoint: DomainObjects.ApiEndpoint, idpCredentials: DTOs.CdrClientConfig.IdpCredentials): ValidationResult {
        fun failure(messageKey: DTOs.ValidationMessageKey) = ValidationResult.Failure(
            listOf(
                ValidationDetail.ConfigItemDetail(
                    configItem = DomainObjects.ConfigurationItem.CDR_API_HOST,
                    messageKey = messageKey
                )
            )
        )

        val illegalValueFailure: ValidationResult.Failure by lazy(LazyThreadSafetyMode.NONE) {
            failure(ILLEGAL_VALUE)
        }

        val illegalValueCombinationFailure: ValidationResult.Failure by lazy(LazyThreadSafetyMode.NONE) {
            failure(ILLEGAL_VALUE_COMBINATION)
        }


        return when (cdrApiEndpoint) {
            DomainObjects.ApiEndpoint.PRODUCTION, DomainObjects.ApiEndpoint.PRODUCTION_INTERNAL -> {
                if (idpCredentials.tenantId != DomainObjects.TenantId.PRODUCTION || idpCredentials.scope != DomainObjects.OAuthScope.PRODUCTION) {
                    illegalValueCombinationFailure
                } else {
                    ValidationResult.Success
                }
            }

            DomainObjects.ApiEndpoint.STAGING, DomainObjects.ApiEndpoint.STAGING_INTERNAL -> {
                if (idpCredentials.tenantId != DomainObjects.TenantId.STAGING || idpCredentials.scope != DomainObjects.OAuthScope.STAGING) {
                    illegalValueCombinationFailure
                } else {
                    ValidationResult.Success
                }
            }

            DomainObjects.ApiEndpoint.INTEGRATION_INTERNAL -> {
                if (idpCredentials.tenantId != DomainObjects.TenantId.INTEGRATION || idpCredentials.scope != DomainObjects.OAuthScope.INTEGRATION) {
                    illegalValueCombinationFailure
                } else {
                    ValidationResult.Success
                }
            }

            DomainObjects.ApiEndpoint.LOCALHOST -> {
                if (idpCredentials.tenantId != DomainObjects.TenantId.LOCALHOST || idpCredentials.scope != DomainObjects.OAuthScope.LOCALHOST) {
                    illegalValueCombinationFailure
                } else {
                    ValidationResult.Success
                }
            }

            DomainObjects.ApiEndpoint.UNKNOWN -> illegalValueFailure
        }
    }

    fun validateConnectorIsPresent(customer: List<Connector>?): ValidationResult =
        if (customer.isNullOrEmpty()) {
            ValidationResult.Failure(
                listOf(
                    ValidationDetail.ConfigItemDetail(
                        configItem = CONNECTOR,
                        messageKey = NO_CONNECTOR_CONFIGURED
                    )
                )
            )
        } else {
            ValidationResult.Success
        }

    fun validateConnectorIdIsPresent(customer: List<Connector>?): ValidationResult =
        if (customer != null && customer.any { it.connectorId.isBlank() }) {
            ValidationResult.Failure(
                listOf(
                    ValidationDetail.ConfigItemDetail(
                        configItem = CONNECTOR,
                        messageKey = NO_CONNECTOR_CONFIGURED
                    )
                )
            )
        } else {
            ValidationResult.Success
        }

    fun validateFileBusyTestTimeout(fileBusyTestTimeout: Duration, fileBusyTestInterval: Duration): ValidationResult =
        if (fileBusyTestTimeout <= fileBusyTestInterval) {
            ValidationResult.Failure(
                listOf(
                    ValidationDetail.ConfigItemDetail(
                        configItem = FILE_BUSY_TEST_TIMEOUT,
                        messageKey = FILE_BUSY_TEST_TIMEOUT_TOO_LONG
                    )
                )
            )
        } else {
            ValidationResult.Success
        }

    fun validateModeValue(connectors: List<Connector>): ValidationResult =
        connectors.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, connector: Connector ->
                when (connector.mode) {
                    DTOs.CdrClientConfig.Mode.TEST, DTOs.CdrClientConfig.Mode.PRODUCTION -> acc
                    DTOs.CdrClientConfig.Mode.NONE -> {
                        acc + ValidationResult.Failure(
                            listOf(
                                ValidationDetail.ConnectorDetail(
                                    connectorId = connector.connectorId,
                                    configItem = CONNECTOR_MODE,
                                    messageKey = ILLEGAL_MODE
                                )
                            )
                        )
                    }
                }
            }
        )

    fun validateModeOverlap(connectors: List<Connector>): ValidationResult =
        connectors
            .groupBy { it.connectorId }
            .filter { cd -> cd.value.size > 1 }
            .map { (connectorId: String, connectors: List<Connector>) ->
                if (connectors.groupingBy { connector -> connector.mode }.eachCount().any { it.value > 1 }) {
                    ValidationResult.Failure(
                        listOf(
                            ValidationDetail.ConnectorDetail(
                                connectorId = connectorId,
                                configItem = CONNECTOR_MODE,
                                messageKey = DUPLICATE_MODE
                            )
                        )
                    )
                } else {
                    ValidationResult.Success
                }
            }.fold(
                initial = ValidationResult.Success,
                operation = { acc: ValidationResult, validationResult: ValidationResult ->
                    acc + validationResult
                }
            )

    /**
     * Verifies that the string can be converted into a [java.nio.file.Path].
     *
     * The test is necessary because [java.nio.file.Path.of] can fail in unexpected ways (on Windows), resulting in a runtime exception being thrown.
     *
     * Returns [ValidationResult.Success] if conversion succeeds. Otherwise, a [ValidationResult.Failure] is returned.
     *
     * @param pathString the path string candidate
     * @return [ValidationResult.Success] if the string can be converted into a [java.nio.file.Path] instance, [ValidationResult.Failure] otherwise
     */
    fun validatePathString(pathString: String): ValidationResult =
        runCatching {
            Path.of(pathString) // throws RTEs
            ValidationResult.Success
        }.getOrElse {
            logger.debug { "Invalid path string: [$pathString], error: ${it.message}" }
            ValidationResult.Failure(listOf(ValidationDetail.PathDetail(path = pathString, messageKey = ILLEGAL_VALUE)))
        }

    /**
     * Validates a directory path provided as a String.
     * Does NOT trim whitespace - validates the path exactly as provided.
     * This is used for UI validation where trailing/leading spaces should result in validation errors.
     */
    fun validateDirectoryIsReadWritable(pathString: String): ValidationResult {
        fun pathIsReadAndWritable(path: Path): ValidationResult =
            if (!path.isReadable() || !path.isWritable()) {
                logger.debug { "Path is not readable or writable: [${path}], readable: [${Files.isReadable(path)}], writable: [${Files.isWritable(path)}]" }
                ValidationResult.Failure(
                    listOf(ValidationDetail.PathDetail(path = path.toString(), messageKey = NOT_READ_WRITABLE))
                )
            } else {
                ValidationResult.Success
            }

        fun pathIsDirectory(path: Path): ValidationResult = if (!path.isDirectory()) {
            logger.debug { "Path exists but is not a directory: [${path}], isRegularFile: [${path.isRegularFile()}]" }
            ValidationResult.Failure(
                listOf(ValidationDetail.PathDetail(path = path.toString(), messageKey = NOT_A_DIRECTORY))
            )
        } else {
            ValidationResult.Success
        }

        logger.debug { "Validating directory path: [$pathString]" }
        val pathValidationResult = validatePathString(pathString)
        return if (pathValidationResult == ValidationResult.Success) {
            val path = Path.of(pathString)
            logger.debug { "Path can be handled by the system: [${path}], absolute: [${path.toAbsolutePath()}], root: [${path.root}]" }

            val pathExistsValidation = checkPathExists(path)
            // Try to get more information about why the path might not be accessible
            if (pathExistsValidation == ValidationResult.Success) {
                pathExistsValidation + pathIsDirectory(path) + pathIsReadAndWritable(path)
            } else {
                handleNonExistingPath(path)
                pathExistsValidation
            }
        } else {
            pathValidationResult
        }
    }

    private fun checkPathExists(path: Path): ValidationResult =
        runCatching {
            path.exists()
        }.fold(
            onSuccess = { exists ->
                if (!exists) {
                    // Files.exists() returned false - attempt access to get more context
                    runCatching { path.getLastModifiedTime() }.fold(
                        onSuccess = {
                            logger.warn { "Needs further investigation: Files.exists() returned false but getLastModifiedTime succeeded for: [${path}]" }
                            ValidationResult.Success
                        },
                        onFailure = { accessException ->
                            logger.warn { "Path access attempt failed: [${path}], error: ${accessException::class.simpleName}: ${accessException.message}" }
                            ValidationResult.Failure(
                                listOf(ValidationDetail.PathDetail(path = path.toString(), messageKey = DIRECTORY_NOT_FOUND))
                            )
                        }
                    )
                } else {
                    ValidationResult.Success
                }
            },
            onFailure = { e ->
                logger.warn { "Exception checking if path exists: [${path}], error: ${e::class.simpleName}: ${e.message}" }
                ValidationResult.Failure(listOf(ValidationDetail.PathDetail(path = path.toString(), messageKey = DIRECTORY_NOT_FOUND)))
            }
        )

    private fun handleNonExistingPath(path: Path) {
        // Provide additional context for different path types
        val pathStr = path.toString()
        val isUncPath = pathStr.startsWith("""\\""") || pathStr.startsWith("//")
        // just a wild guess on Windows systems so that we could provide some hints in the error message
        val isMappedDrive = !isUncPath && path.root?.toString()?.let { root ->
            val driveLetter = root[0].uppercaseChar()
            root.length == WINDOWS_DRIVE_ROOT_LENGTH && root[1] == ':' && driveLetter in 'A'..'Z' && driveLetter != 'C'
        } ?: false

        when {
            isUncPath -> {
                logger.warn {
                    "Network path is not accessible: [${path}]. " +
                            "This is typically caused by: " +
                            "1) Missing or incorrect authentication credentials for the network share, " +
                            "2) The service/process does not have network access or proper permissions, " +
                            "3) The share does not exist or is not mounted, or " +
                            "4) Firewall or network connectivity issues. " +
                            "For Windows UNC paths (\\\\server\\share), verify credentials with 'cmdkey /list' (for the user running the service). " +
                            "For Unix/Linux NFS/SMB mounts, check if the share is mounted and accessible."
                }
            }

            isMappedDrive -> {
                logger.warn {
                    "Mapped network drive is not accessible: [${path}]. " +
                            "Mapped drives are user-specific and session-specific. " +
                            "The drive may not be available to this process because: " +
                            "1) It was mapped by a different user, " +
                            "2) It was mapped in a different session, or " +
                            "3) The mapping was not made persistent. " +
                            "Consider using a direct network path instead (Windows: \\\\server\\share, Unix: /mnt/share or NFS mount)."
                }
            }
        }

        logger.debug { "Path does not exist: [${path}]" }
    }

    fun validateIsNeitherBlankNorPlaceholder(value: String?, configItem: DomainObjects.ConfigurationItem): ValidationResult =
        when (val valueIsNotBlank = validateIsNotBlank(value, configItem)) {
            is ValidationResult.Success -> if (value == PLACEHOLDER_VALUE) {
                ValidationResult.Failure(
                    listOf(ValidationDetail.ConfigItemDetail(configItem = configItem, messageKey = VALUE_IS_PLACEHOLDER))
                )
            } else {
                ValidationResult.Success
            }

            is ValidationResult.Failure -> valueIsNotBlank
        }

    fun validateIsNotBlank(value: String?, configItem: DomainObjects.ConfigurationItem): ValidationResult =
        if (value.isNullOrBlank()) {
            ValidationResult.Failure(listOf(ValidationDetail.ConfigItemDetail(configItem = configItem, messageKey = VALUE_IS_BLANK)))
        } else {
            ValidationResult.Success
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun validateDirectoryOverlap(config: DTOs.CdrClientConfig): ValidationResult {
        val pathStringValidation = config.customer.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, connector: Connector ->
                acc +
                        validatePathString(connector.sourceFolder) +
                        validatePathString(connector.targetFolder) +
                        validatePathString(connector.sourceErrorFolder ?: "") +
                        validatePathString(connector.sourceArchiveFolder ?: "") +
                        connector.docTypeFolders.values.fold(
                            initial = ValidationResult.Success,
                            operation = { docTypeAcc: ValidationResult, docTypeFolder: Connector.DocTypeFolders ->
                                docTypeAcc +
                                        validatePathString(docTypeFolder.sourceFolder ?: "") +
                                        validatePathString(docTypeFolder.errorFolder ?: "") +
                                        validatePathString(docTypeFolder.archiveFolder ?: "") +
                                        validatePathString(docTypeFolder.targetFolder ?: "")
                            }
                        )
            }
        ) +
                validatePathString(config.localFolder)

        return if (pathStringValidation == ValidationResult.Failure) {
            pathStringValidation
        } else {
            val connectors = config.customer.map { connector ->
                com.swisscom.health.des.cdr.client.config.Connector.EMPTY.copy(
                    sourceFolder = Path.of(connector.sourceFolder),
                    targetFolder = Path.of(connector.targetFolder),
                    sourceErrorFolder = connector.sourceErrorFolder?.let { Path.of(it) },
                    sourceArchiveFolder = connector.sourceArchiveFolder?.let { Path.of(it) },
                    sourceArchiveEnabled = connector.sourceArchiveEnabled,
                    docTypeFolders = connector.docTypeFolders.toCdrClientConfig()
                )
            }

            val sourceFolders: List<Path> = connectors.flatMap { connector ->
                connector.getEffectiveSourceFolders().values.distinct()
            }

            val targetFolders: List<Path> = connectors.flatMap { connector ->
                connector.getEffectiveTargetFolders().values.distinct()
            }

            val errorFolders: List<Path> = connectors.flatMap { connector ->
                connector.getEffectiveErrorFolders().values.distinct()
            }

            val archiveFolders: List<Path> = connectors.flatMap { connector ->
                connector.getEffectiveArchiveFolders().values.filterNotNull().distinct()
            }

            val localFolder: List<Path> = listOf(Path.of(config.localFolder))

            // local dir must not overlap with source dirs
            val localDirSourceDirsOverlap: ValidationResult =
                if (sourceFolders.containsAll(localFolder)) {
                    ValidationResult.Failure(
                        localFolder.map { path: Path ->
                            ValidationDetail.PathDetail(
                                path = path.toString(),
                                messageKey = DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_SOURCE_DIRS
                            )
                        }
                    )
                } else {
                    ValidationResult.Success
                }

            // local dir must not overlap with target dirs
            val localDirTargetDirsOverlap: ValidationResult =
                if ((targetFolders + archiveFolders + errorFolders).containsAll(localFolder)) {
                    ValidationResult.Failure(
                        localFolder.map { path: Path ->
                            ValidationDetail.PathDetail(
                                path = path.toString(),
                                messageKey = DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_TARGET_DIRS
                            )
                        }
                    )
                } else {
                    ValidationResult.Success
                }

            // source dirs must not overlap with each other
            val sourceDirsOverlap: ValidationResult =
                (sourceFolders)
                    .groupingBy { it }
                    .eachCount()
                    .filter { it.value > 1 }
                    .let { duplicateSources ->
                        if (duplicateSources.isNotEmpty()) {
                            ValidationResult.Failure(
                                validationDetails =
                                    duplicateSources.keys.map { path: Path ->
                                        ValidationDetail.PathDetail(
                                            path = path.toString(),
                                            messageKey = DTOs.ValidationMessageKey.DUPLICATE_SOURCE_DIRS,
                                        )

                                    }
                            )
                        } else {
                            ValidationResult.Success
                        }
                    }

            // target dirs must not overlap with source dirs
            val targetDirsOverlapWithSourceDirs: ValidationResult =
                (sourceFolders)
                    // TODO: check why errorFolders are not part of the target dirs? Bug?
                    .intersect((targetFolders + archiveFolders).toSet())
                    .let { overlappingDirs ->
                        if (overlappingDirs.isNotEmpty()) {
                            ValidationResult.Failure(
                                validationDetails =
                                    overlappingDirs.map { path: Path ->
                                        ValidationDetail.PathDetail(
                                            path = path.toString(),
                                            messageKey = DTOs.ValidationMessageKey.TARGET_DIR_OVERLAPS_SOURCE_DIRS,
                                        )
                                    }
                            )
                        } else {
                            ValidationResult.Success
                        }
                    }
            // target dirs may overlap with each other

            // no non error dir should end in the default error folder name
            val errorFolderNameOverlap: ValidationResult =
                (localFolder + sourceFolders + targetFolders + archiveFolders)
                    .filter { it.fileName?.toString() == ERROR_DIR_NAME }
                    .let { overlappingErrorFolderDirs ->
                        if (overlappingErrorFolderDirs.isNotEmpty()) {
                            ValidationResult.Failure(
                                validationDetails =
                                    overlappingErrorFolderDirs.map { path: Path ->
                                        ValidationDetail.PathDetail(
                                            path = path.toString(),
                                            messageKey = DTOs.ValidationMessageKey.ERROR_AS_NON_ERROR_FOLDER_NAME_USED,
                                        )
                                    }
                            )
                        } else {
                            ValidationResult.Success
                        }
                    }

            val errorFolderOverlap: ValidationResult =
                errorFolders
                    .intersect((localFolder + sourceFolders + targetFolders + archiveFolders).toSet())
                    .let { overlappingDirs ->
                        if (overlappingDirs.isNotEmpty()) {
                            ValidationResult.Failure(
                                validationDetails =
                                    overlappingDirs.map { path: Path ->
                                        ValidationDetail.PathDetail(
                                            path = path.toString(),
                                            messageKey = DTOs.ValidationMessageKey.ERROR_DIR_OVERLAPS_NON_ERROR_DIR,
                                        )
                                    }
                            )
                        } else {
                            ValidationResult.Success
                        }
                    }

            val overlapResult = localDirSourceDirsOverlap +
                    localDirTargetDirsOverlap +
                    sourceDirsOverlap +
                    targetDirsOverlapWithSourceDirs +
                    errorFolderNameOverlap +
                    errorFolderOverlap

            overlapResult
        }
    }

    fun validateCredentialValues(credentials: DTOs.CdrClientConfig.IdpCredentials): ValidationResult {
        val validations = mutableListOf<ValidationResult>()
        validateIsNeitherBlankNorPlaceholder(
            value = credentials.clientId,
            configItem = DomainObjects.ConfigurationItem.IDP_CLIENT_ID
        ).let { validations.add(it) }
        validateIsNeitherBlankNorPlaceholder(
            value = credentials.clientSecret,
            configItem = DomainObjects.ConfigurationItem.IDP_CLIENT_PASSWORD
        ).let { validations.add(it) }

        return validations.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, validationResult: ValidationResult ->
                acc + validationResult
            }
        )
    }

    fun validateProxySetting(proxyUrl: String): ValidationResult {
        return if (proxyUrl.isNotBlank()) {
            if (!proxyUrl.startsWith("http://") && !proxyUrl.startsWith("https://")) {
                ValidationResult.Failure(
                    listOf(
                        ValidationDetail.ConfigItemDetail(
                            configItem = DomainObjects.ConfigurationItem.PROXY_URL,
                            messageKey = DTOs.ValidationMessageKey.PROXY_URL_MUST_START_WITH_HTTP_OR_HTTPS
                        )
                    )
                )
            } else {
                ValidationResult.Success
            }
        } else {
            ValidationResult.Success
        }
    }

    fun validateConnectorFolders(connector: Connector): ValidationResult {
        val baseValidation = validateIsAbsoluteDirectory(connector.sourceFolder) +
                validateIsAbsoluteDirectory(connector.targetFolder) +
                validateDirectoryIsReadWritable(connector.sourceFolder) +
                validateDirectoryIsReadWritable(connector.targetFolder)

        // if archiving is enabled and
        //   - the archive directory is an absolute path -> verify the directory is read/writable
        //   - the archive directory is an absolute path -> return `success` as the relative path will be resolved against the source dir,
        //     which we already checked above
        // else we do not care and return `success`
        val archiveValidation: ValidationResult =
            if (connector.sourceArchiveEnabled) {
                validateReadWritableIfAbsolutePath(connector.sourceArchiveFolder)
            } else {
                ValidationResult.Success
            }

        // if the error directory is an absolute path -> verify the directory is read/writable
        // else -> return `success` as the relative path will be resolved against the source dir, which we already checked above
        val errorValidation = validateReadWritableIfAbsolutePath(connector.sourceErrorFolder)


        val docTypeValidation = connector.docTypeFolders.map { (_, docTypeFolder) ->
            validateReadWritableIfAbsolutePath(docTypeFolder.sourceFolder) +
                    validateReadWritableIfAbsolutePath(docTypeFolder.targetFolder) +
                    validateReadWritableIfAbsolutePath(docTypeFolder.archiveFolder) +
                    validateReadWritableIfAbsolutePath(docTypeFolder.errorFolder)
        }.fold(initial = ValidationResult.Success) { acc: ValidationResult, result: ValidationResult -> acc + result }


        return baseValidation + archiveValidation + errorValidation + docTypeValidation
    }

    @Suppress("NestedBlockDepth")
    fun validateIsAbsoluteDirectory(path: String): ValidationResult =
        validatePathString(path).let { pathStringValidation ->
            when (pathStringValidation) {
                ValidationResult.Success -> path.let { path: String ->
                    if (Path.of(path).isAbsolute) {
                        ValidationResult.Success
                    } else {
                        ValidationResult.Failure(
                            listOf(
                                ValidationDetail.PathDetail(path = path, messageKey = DIRECTORY_NEEDS_ABSOLUTE_PATH)
                            )
                        )
                    }
                }

                else -> pathStringValidation
            }
        }

    private fun validateReadWritableIfAbsolutePath(path: String?): ValidationResult = path?.let { path: String ->
        if (validateIsAbsoluteDirectory(path) == ValidationResult.Success) {
            validateDirectoryIsReadWritable(path)
        } else {
            ValidationResult.Success
        }
    } ?: ValidationResult.Success

    companion object {
        private const val PLACEHOLDER_VALUE = "value-required"
        private const val WINDOWS_DRIVE_ROOT_LENGTH = 3
    }

}
