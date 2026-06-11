package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.ARCHIVE_DIR_NAME
import com.swisscom.health.des.cdr.client.common.Constants.ERROR_DIR_NAME
import com.swisscom.health.des.cdr.client.common.DTOs
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
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.effectiveArchiveFolders
import com.swisscom.health.des.cdr.client.config.effectiveErrorFolders
import com.swisscom.health.des.cdr.client.config.effectiveSourceFolders
import com.swisscom.health.des.cdr.client.config.effectiveTargetFolders
import com.swisscom.health.des.cdr.client.config.toCdrClientConfig
import com.swisscom.health.des.cdr.client.config.toDto
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
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
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig as CdrClientConfigDto
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig.Connector as ConnectorDto

private val logger = KotlinLogging.logger {}

@Service
@Suppress("TooManyFunctions", "LargeClass")
internal class ConfigValidationService(
    private val config: CdrClientConfig,
) {

    val isConfigValid: Boolean by lazy { validateAllConfigurationItems(config) is ValidationResult.Success }

    @Suppress("UnusedPrivateMember")
    @PostConstruct
    private fun createErrorAndArchiveFolders() = createErrorAndArchiveFolders(config)

    /**
     * Actually, we should only attempt to create error and archive folders that are configured
     * relative to (a document type specific) source directory, but to keep it simple we attempt
     * to create no matter what.
     *
     * If we fail to do so we just log that fact and [validateConnectorFolders] will report the
     * missing directories and prevent document synchronization from starting until the problem
     * is resolved.
     *
     * TODO: Do we need to make sure the created directories are not world readable?
     *   Using Posix permissions this is straight forward on Linux/macOS; not so much
     *   on Windows.
     */
    fun createErrorAndArchiveFolders(config: CdrClientConfig) {
        fun createFolderIfMissing(path: Path, type: String) {
            runCatching {
                if (!path.exists()) {
                    logger.debug { "$type folder [$path] does not exist, attempting to create it" }
                    Files.createDirectories(path)
                    logger.debug { "$type folder [$path] created successfully" }
                } else {
                    logger.debug { "$type folder [$path] already exists, no need to create it" }
                }
            }.onFailure { e ->
                logger.warn { "failed to create $type folder: [${path}], error: [$e]" }
            }
        }

        config.customer.flatMap { connector -> connector.effectiveErrorFolders.values.flatten() }.distinct().forEach { errorFolder ->
            createFolderIfMissing(errorFolder, "error")
        }

        config.customer.flatMap { connector -> connector.effectiveArchiveFolders.values.flatten() }.distinct().forEach { archiveFolder ->
            createFolderIfMissing(archiveFolder, "archive")
        }
    }

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
    fun validateCdrApiEndpoint(cdrApiEndpoint: DomainObjects.ApiEndpoint, idpCredentials: CdrClientConfigDto.IdpCredentials): ValidationResult {
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

    fun validateConnectorIsPresent(customer: List<ConnectorDto>?): ValidationResult =
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

    fun validateConnectorIdIsPresent(customer: List<ConnectorDto>?): ValidationResult =
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

    fun validateModeValue(connectors: List<ConnectorDto>): ValidationResult =
        connectors.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, connector: ConnectorDto ->
                when (connector.mode) {
                    CdrClientConfigDto.Mode.TEST, CdrClientConfigDto.Mode.PRODUCTION -> acc
                    CdrClientConfigDto.Mode.NONE -> {
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

    fun validateModeOverlap(connectors: List<ConnectorDto>): ValidationResult =
        connectors
            .groupBy { it.connectorId }
            .filter { cd -> cd.value.size > 1 }
            .map { (connectorId: String, connectors: List<ConnectorDto>) ->
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
                            logger.info { "Path access attempt failed: [${path}], error: ${accessException::class.simpleName}: ${accessException.message}" }
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
    fun validateDirectoryOverlap(config: CdrClientConfigDto): ValidationResult {
        val pathStringValidation = config.customer.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, connector: ConnectorDto ->
                acc +
                        validatePathString(connector.sourceFolder) +
                        validatePathString(connector.targetFolder) +
                        validatePathString(connector.sourceErrorFolder ?: "") +
                        validatePathString(connector.sourceArchiveFolder ?: "") +
                        connector.docTypeFolders.values.fold(
                            initial = ValidationResult.Success,
                            operation = { docTypeAcc: ValidationResult, docTypeFolder: ConnectorDto.DocTypeFolders ->
                                docTypeAcc +
                                        validatePathString(docTypeFolder.sourceFolder ?: "") +
                                        validatePathString(docTypeFolder.sourceFolderResp ?: "") +
                                        validatePathString(docTypeFolder.sourceFolderReq ?: "") +
                                        validatePathString(docTypeFolder.errorFolder ?: "") +
                                        validatePathString(docTypeFolder.archiveFolder ?: "") +
                                        validatePathString(docTypeFolder.targetFolder ?: "") +
                                        validatePathString(docTypeFolder.targetFolderResp ?: "") +
                                        validatePathString(docTypeFolder.targetFolderReq ?: "")
                            }
                        )
            }
        ) +
                validatePathString(config.localFolder)

        return if (pathStringValidation == ValidationResult.Failure) {
            pathStringValidation
        } else {
            val connectors = config.customer.map { it.toCdrClientConfig() }

            val sourceFolders: List<Path> = connectors.flatMap { connector ->
                connector.effectiveSourceFolders.values.flatten().distinct()
            }

            val targetFolders: List<Path> = connectors.flatMap { connector ->
                connector.effectiveTargetFolders.values.flatten().distinct()
            }

            val errorFolders: List<Path> = connectors.flatMap { connector ->
                connector.effectiveErrorFolders.values.flatten().distinct()
            }

            val archiveFolders: List<Path> = connectors.flatMap { connector ->
                connector.effectiveArchiveFolders.values.flatten().distinct()
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

            // no non archive dir end in the default archive folder name
            val archiveFolderNameOverlap: ValidationResult =
                (localFolder + sourceFolders + targetFolders + errorFolders)
                    .filter { it.fileName?.toString() == ARCHIVE_DIR_NAME }
                    .let { overlappingArchiveFolderDirs ->
                        if (overlappingArchiveFolderDirs.isNotEmpty()) {
                            ValidationResult.Failure(
                                validationDetails =
                                    overlappingArchiveFolderDirs.map { path: Path ->
                                        ValidationDetail.PathDetail(
                                            path = path.toString(),
                                            messageKey = DTOs.ValidationMessageKey.ARCHIVE_AS_NON_ARCHIVE_FOLDER_NAME_USED,
                                        )
                                    }
                            )
                        } else {
                            ValidationResult.Success
                        }
                    }

            val archiveFolderOverlap: ValidationResult =
                archiveFolders
                    .intersect((localFolder + sourceFolders + targetFolders + errorFolders).toSet())
                    .let { overlappingDirs ->
                        if (overlappingDirs.isNotEmpty()) {
                            ValidationResult.Failure(
                                validationDetails =
                                    overlappingDirs.map { path: Path ->
                                        ValidationDetail.PathDetail(
                                            path = path.toString(),
                                            messageKey = DTOs.ValidationMessageKey.ARCHIVE_DIR_OVERLAPS_NON_ARCHIVE_DIR,
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
                    errorFolderOverlap +
                    archiveFolderNameOverlap +
                    archiveFolderOverlap

            overlapResult
        }
    }

    fun validateCredentialValues(credentials: CdrClientConfigDto.IdpCredentials): ValidationResult {
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

    fun validateProxyUrl(proxyUrl: String): ValidationResult {
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

    @Suppress("CyclomaticComplexMethod")
    fun validateConnectorFolders(connector: ConnectorDto): ValidationResult {
        val baseValidation = validateReadWritableAbsoluteDirectory(connector.sourceFolder) +
                validateReadWritableAbsoluteDirectory(connector.targetFolder)

        // if archiving is enabled and
        //   - the archive directory is an absolute path -> verify the directory is read/writable
        //   - the archive directory is a relative path -> return `success` as the relative path will be resolved against the source dir,
        //     which we already checked above
        // else we do not care and return `success`
        val archiveValidation: ValidationResult =
            if (connector.sourceArchiveEnabled) {
                validateReadWritableIfAbsoluteDirectory(connector.sourceArchiveFolder)
            } else {
                ValidationResult.Success
            }

        // if the error directory is an absolute path -> verify the directory is read/writable
        // else -> return `success` as the relative path will be resolved against the source dir, which we already checked above
        val errorValidation = validateReadWritableIfAbsoluteDirectory(connector.sourceErrorFolder)

        // if request-response-split is enabled, then all document type specific directories become mandatory,
        // and they all have to be absolute directories
        val docTypeValidation = connector.docTypeFolders.map { (_, docTypeFolder) ->
            if (docTypeFolder.requestResponseSplit) {
                validateReadWritableAbsoluteDirectory(docTypeFolder.sourceFolderResp) +
                        validateReadWritableAbsoluteDirectory(docTypeFolder.sourceFolderReq) +
                        validateReadWritableAbsoluteDirectory(docTypeFolder.targetFolderResp) +
                        validateReadWritableAbsoluteDirectory(docTypeFolder.targetFolderReq) +
                        validateReadWritableAbsoluteDirectory(docTypeFolder.errorFolder) +
                        validateReadWritableAbsoluteDirectory(docTypeFolder.archiveFolder)
            } else {
                validateReadWritableIfAbsoluteDirectory(docTypeFolder.sourceFolder) +
                        validateReadWritableIfAbsoluteDirectory(docTypeFolder.targetFolder) +
                        validateReadWritableIfAbsoluteDirectory(docTypeFolder.archiveFolder) +
                        validateReadWritableIfAbsoluteDirectory(docTypeFolder.errorFolder)
            }
        }.fold(initial = ValidationResult.Success) { acc: ValidationResult, result: ValidationResult -> acc + result }

        val allErrorFoldersExist = connector.toCdrClientConfig().effectiveErrorFolders.values
            .flatten()
            .distinct()
            .fold(initial = ValidationResult.Success) { acc: ValidationResult, errorFolder: Path -> acc + checkPathExists(errorFolder) }

        val allArchiveFoldersExist = connector.toCdrClientConfig().effectiveArchiveFolders.values
            .flatten()
            .distinct()
            .fold(initial = ValidationResult.Success) { acc: ValidationResult, archiveFolder: Path -> acc + checkPathExists(archiveFolder) }

        return baseValidation + archiveValidation + errorValidation + docTypeValidation + allErrorFoldersExist + allArchiveFoldersExist
    }

    /**
     * Returns [ValidationResult.Success] if the provided path is
     * * not null
     * * absolute
     * * a directory
     *
     * Otherwise returns [ValidationResult.Failure]
     *
     * @param path Path string to test
     * @return [ValidationResult.Success] if the path is absolute and points to a directory
     */
    @Suppress("NestedBlockDepth")
    fun validateIsAbsoluteDirectory(path: String?): ValidationResult {
        fun validationError(msgKey: DTOs.ValidationMessageKey) = ValidationResult.Failure(
            listOf(
                ValidationDetail.PathDetail(path = path ?: "", messageKey = msgKey)
            )
        )

        return when (path) {
            null -> validationError(NOT_A_DIRECTORY)
            else ->
                when (val pathStringValidation = validatePathString(path)) {
                    is ValidationResult.Success -> {
                        with(Path.of(path)) {
                            when (isAbsolute) {
                                false -> validationError(DIRECTORY_NEEDS_ABSOLUTE_PATH)
                                true -> when (isDirectory()) {
                                    false -> validationError(NOT_A_DIRECTORY)
                                    true -> ValidationResult.Success
                                }
                            }
                        }
                    }

                    is ValidationResult.Failure -> pathStringValidation
                }
        }
    }

    /**
     * Returns [ValidationResult.Success] if the provided path is
     * * not null
     * * absolute
     * * a directory
     * * read-writable
     *
     * Otherwise returns [ValidationResult.Failure]
     *
     * @param path Path string to test
     * @return [ValidationResult.Success] if the path is absolute, points to a directory, which is read-writable
     * @see validateIsAbsoluteDirectory
     * @see validateDirectoryIsReadWritable
     * @see validateReadWritableIfAbsoluteDirectory
     */
    fun validateReadWritableAbsoluteDirectory(path: String?): ValidationResult =
        when (val result = validateIsAbsoluteDirectory(path)) {
            is ValidationResult.Failure -> result
            is ValidationResult.Success -> validateDirectoryIsReadWritable(path!!)
        }

    /**
     * Returns [ValidationResult.Success] if the provided path is either
     * * null
     *
     * or
     * * not null
     * * absolute
     * * a directory
     * * read-writable
     *
     * Otherwise returns [ValidationResult.Failure]
     *
     * @param path Path string to test
     * @return [ValidationResult.Success] if the path is absolute, points to a directory, which is read-writable
     * @see validateIsAbsoluteDirectory
     * @see validateDirectoryIsReadWritable
     * @see validateReadWritableAbsoluteDirectory
     */
    fun validateReadWritableIfAbsoluteDirectory(path: String?): ValidationResult =
        when (validateIsAbsoluteDirectory(path)) {
            is ValidationResult.Failure -> ValidationResult.Success // Not a path at all or a relative path -> treat as success
            is ValidationResult.Success -> validateDirectoryIsReadWritable(path!!)
        }

    companion object {
        private const val PLACEHOLDER_VALUE = "value-required"
        private const val WINDOWS_DRIVE_ROOT_LENGTH = 3
    }

}
