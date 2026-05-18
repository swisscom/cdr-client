package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.ERROR_DIR_NAME
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationDetail
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DIRECTORY_NEEDS_ABSOLUTE_PATH
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DIRECTORY_NOT_FOUND
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DUPLICATE_MODE
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DUPLICATE_SOURCE_DIRS
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.ERROR_AS_NON_ERROR_FOLDER_NAME_USED
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.ERROR_DIR_OVERLAPS_NON_ERROR_DIR
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.FILE_BUSY_TEST_TIMEOUT_TOO_LONG
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.ILLEGAL_MODE
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.ILLEGAL_VALUE
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.ILLEGAL_VALUE_COMBINATION
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_SOURCE_DIRS
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_TARGET_DIRS
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.NOT_A_DIRECTORY
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.NOT_READ_WRITABLE
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.NO_CONNECTOR_CONFIGURED
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.TARGET_DIR_OVERLAPS_SOURCE_DIRS
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.VALUE_IS_BLANK
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.VALUE_IS_PLACEHOLDER
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationResult
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.ARCHIVE_DIRECTORY
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.CONNECTOR
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.CONNECTOR_MODE
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.FILE_BUSY_TEST_TIMEOUT
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.toDto
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
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

    val isConfigValid: Boolean by lazy { validateAllConfigurationItems(config.toDto()) is ValidationResult.Success }

    fun validateAllConfigurationItems(config: DTOs.CdrClientConfig): ValidationResult {
        val validations = mutableListOf<ValidationResult>()

        validations.add(validateCdrApiEndpoint(config.cdrApi, config.idpCredentials))
        validations.add(validateDirectoriesAreAbsolute(config))
        validations.add(validateDirectoryIsReadWritable(config.localFolder))
        validations.add(validateDirectoryOverlap(config))
        validations.add(validateModeValue(config.customer))
        validations.add(validateModeOverlap(config.customer))
        validations.add(validateFileBusyTestTimeout(fileBusyTestTimeout = config.fileBusyTestTimeout, fileBusyTestInterval = config.fileBusyTestInterval))
        validations.add(validateConnectorIsPresent(config.customer))
        validations.add(validateCredentialValues(config.idpCredentials))
        validations.add(validateConnectorIdIsPresent(config.customer))
        config.customer.forEach {
            validations.addAll(validateConnectorFolders(it))
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

    fun validateAvailableDiskspace(connectors: List<DTOs.CdrClientConfig.Connector>): ValidationResult {
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

    fun validateDirectoriesAreAbsolute(config: DTOs.CdrClientConfig): ValidationResult {
        val validations = mutableListOf<ValidationResult>()
        config.customer.forEach { connector ->
            validations.add(checkIsAbsolute(connector.sourceFolder))
            validations.add(checkIsAbsolute(connector.targetFolder))
            if (connector.sourceErrorFolder != null) {
                validations.add(checkIsAbsolute(connector.sourceErrorFolder))
            }
            if (connector.sourceArchiveEnabled) {
                if (connector.sourceArchiveFolder == null) {
                    validations.add(
                        ValidationResult.Failure(
                            listOf(
                                ValidationDetail.ConfigItemDetail(
                                    configItem = ARCHIVE_DIRECTORY,
                                    messageKey = NOT_A_DIRECTORY
                                )
                            )
                        )
                    )
                } else {
                    validations.add(checkIsAbsolute(connector.sourceArchiveFolder))
                }
            }
            for (docTypeFolder in connector.docTypeFolders.values) {
                if (docTypeFolder.sourceFolder != null) {
                    validations.add(checkIsAbsolute(docTypeFolder.sourceFolder))
                }
                if (docTypeFolder.targetFolder != null) {
                    validations.add(checkIsAbsolute(docTypeFolder.targetFolder))
                }
            }
        }
        return validations.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, validationResult: ValidationResult ->
                acc + validationResult
            }
        )
    }

    private fun checkIsAbsolute(path: String?): ValidationResult {
        val safePathValidation = safePathValidation(path)
        return if (safePathValidation.isSuccessAndPathNotNull(path)) {
            safePathValidation + if (Path.of(path).isAbsolute) {
                ValidationResult.Success
            } else {
                ValidationResult.Failure(
                    listOf(
                        ValidationDetail.PathDetail(path = path, messageKey = DIRECTORY_NEEDS_ABSOLUTE_PATH)
                    )
                )
            }
        } else {
            safePathValidation
        }
    }

    fun validateConnectorIsPresent(customer: List<DTOs.CdrClientConfig.Connector>?): ValidationResult =
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

    fun validateConnectorIdIsPresent(customer: List<DTOs.CdrClientConfig.Connector>?): ValidationResult =
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

    fun validateModeValue(connectors: List<DTOs.CdrClientConfig.Connector>): ValidationResult =
        connectors.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, connector: DTOs.CdrClientConfig.Connector ->
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

    fun validateModeOverlap(connectors: List<DTOs.CdrClientConfig.Connector>): ValidationResult =
        connectors
            .groupBy { it.connectorId }
            .filter { cd -> cd.value.size > 1 }
            .map { (connectorId: String, connectors: List<DTOs.CdrClientConfig.Connector>) ->
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
     * Result of attempting to convert a configured path string into a Path.
     * Use this from validation code so callers can decide how to present parse failures
     * rather than silently dropping the values.
     */
    private sealed class SafePathOfResult {
        data class Success(val path: Path) : SafePathOfResult()
        data class Failure(val validation: ValidationResult) : SafePathOfResult()
    }

    /**
     * Attempts to create a Path from the provided string. Returns a sealed result containing
     * either the parsed Path or a ValidationResult describing the failure.
     */
    private fun safePathOf(pathString: String?): SafePathOfResult {
        val safePathValidationResult = safePathValidation(pathString)
        return when {
            safePathValidationResult.isSuccessAndPathNotNull(pathString) -> SafePathOfResult.Success(Path.of(pathString))

            else -> SafePathOfResult.Failure(safePathValidationResult)
        }
    }

    private fun safePathValidation(pathString: String?): ValidationResult = when (pathString) {
        null -> ValidationResult.Failure(
            listOf(ValidationDetail.PathDetail(path = "NULL", messageKey = ILLEGAL_VALUE))
        )

        else -> runCatching {
            Path.of(pathString)
            ValidationResult.Success
        }.getOrElse {
            logger.warn { "Invalid path string: [$pathString], error: ${it.message}" }
            ValidationResult.Failure(
                listOf(ValidationDetail.PathDetail(path = pathString, messageKey = ILLEGAL_VALUE))
            )
        }
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
        val safePathValidation = safePathValidation(pathString)
        return if (safePathValidation.isSuccessAndPathNotNull((pathString))) {
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
            safePathValidation
        }
    }

    private fun checkPathExists(path: Path): ValidationResult = runCatching { path.exists() }.fold(
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

    fun validateIsNotBlankOrPlaceholder(value: String?, configItem: DomainObjects.ConfigurationItem): ValidationResult =
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

    fun validateIsNotBlank(value: String?, configItem: DomainObjects.ConfigurationItem): ValidationResult {
        return if (value.isNullOrBlank()) {
            ValidationResult.Failure(listOf(ValidationDetail.ConfigItemDetail(configItem = configItem, messageKey = VALUE_IS_BLANK)))
        } else {
            ValidationResult.Success
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun validateDirectoryOverlap(config: DTOs.CdrClientConfig): ValidationResult {
        val parseFailures = mutableListOf<ValidationResult>()

        fun getAllBaseSourceFolders(): List<Path> = config.customer.mapNotNull { connector ->
            when (val res = safePathOf(connector.sourceFolder)) {
                is SafePathOfResult.Success -> {
                    logger.debug {
                        "connector [${connector.connectorId}-${connector.mode}] base source folder: [${connector.sourceFolder}], " +
                                "base error folder: [${connector.sourceErrorFolder}]"
                    }
                    res.path
                }

                is SafePathOfResult.Failure -> {
                    parseFailures.add(res.validation)
                    null
                }
            }
        }

        fun getAllBaseTargetFolders(): List<Path> = config.customer.mapNotNull { connector ->
            when (val res = safePathOf(connector.targetFolder)) {
                is SafePathOfResult.Success -> {
                    logger.debug { "connector [${connector.connectorId}-${connector.mode}] base target folder: [${connector.targetFolder}]" }
                    res.path
                }

                is SafePathOfResult.Failure -> {
                    parseFailures.add(res.validation)
                    null
                }
            }
        }

        fun effectiveSourceFolder(docTypeFolders: DTOs.CdrClientConfig.Connector.DocTypeFolders, sourceFolder: Path): Path? =
            docTypeFolders.sourceFolder?.let { sourceFolder.resolve(it) }

        fun effectiveTargetFolder(docTypeFolders: DTOs.CdrClientConfig.Connector.DocTypeFolders, targetFolder: Path): Path? =
            docTypeFolders.targetFolder?.let { targetFolder.resolve(it) }

        fun getAllSourceDocTypeFolders(): List<Path> = config.customer
            .map { connector ->
                (connector.sourceFolder to connector.docTypeFolders.values)
                    .also {
                        logger.debug { "connector [${connector.connectorId}-${connector.mode}] doctype directories: [${connector.docTypeFolders}]" }
                    }
            }
            .flatMap { (sourceFolder, docTypeFolders) ->
                docTypeFolders.mapNotNull {
                    when (val res = safePathOf(sourceFolder)) {
                        is SafePathOfResult.Success -> effectiveSourceFolder(docTypeFolders = it, sourceFolder = res.path)
                        is SafePathOfResult.Failure -> {
                            parseFailures.add(res.validation)
                            null
                        }
                    }
                }
            }

        fun getAllTargetDocTypeFolders(): List<Path> = config.customer
            .map { connector -> connector.targetFolder to connector.docTypeFolders.values }
            .flatMap { (targetFolder, docTypeFolders) ->
                docTypeFolders.mapNotNull {
                    when (val res = safePathOf(targetFolder)) {
                        is SafePathOfResult.Success -> effectiveTargetFolder(docTypeFolders = it, targetFolder = res.path)
                        is SafePathOfResult.Failure -> {
                            parseFailures.add(res.validation)
                            null
                        }
                    }
                }
            }

        fun getSourceErrorFolder(): List<Path> = config.customer
            .mapNotNull { connector ->
                val effectiveErrorPaths: Path? = when (connector.sourceErrorFolder) {
                    null -> when (val res = safePathOf(connector.sourceFolder)) {
                        is SafePathOfResult.Success -> res.path.resolve(ERROR_DIR_NAME)
                        is SafePathOfResult.Failure -> {
                            parseFailures.add(res.validation)
                            null
                        }
                    }

                    connector.sourceFolder -> when (val res = safePathOf(connector.sourceFolder)) {
                        is SafePathOfResult.Success -> res.path.resolve(ERROR_DIR_NAME)
                        is SafePathOfResult.Failure -> {
                            parseFailures.add(res.validation)
                            null
                        }
                    }

                    else -> when (val res = safePathOf(connector.sourceErrorFolder!!)) {
                        is SafePathOfResult.Success -> res.path
                        is SafePathOfResult.Failure -> {
                            parseFailures.add(res.validation)
                            null
                        }
                    }
                }

                effectiveErrorPaths?.also {
                    logger.debug {
                        "connector [${connector.connectorId}-${connector.mode}] source error folder: [$it]"
                    }
                }
            }.distinct()

        fun getSourceArchiveFolders(): List<Path> = config.customer
            .mapNotNull { connector ->
                connector.sourceArchiveFolder?.let { archiveFolder ->
                    when (val res = safePathOf(archiveFolder)) {
                        is SafePathOfResult.Success -> {
                            logger.debug {
                                "connector [${connector.connectorId}-${connector.mode}] source archive folder: [${res.path}]"
                            }
                            res.path
                        }

                        is SafePathOfResult.Failure -> {
                            parseFailures.add(res.validation)
                            null
                        }
                    }
                }
            }.distinct()

        val localDirectory: List<Path> = when (val res = safePathOf(config.localFolder)) {
            is SafePathOfResult.Success -> listOf(res.path)
            is SafePathOfResult.Failure -> {
                parseFailures.add(res.validation)
                emptyList()
            }
        }
        val baseSourceFolders: List<Path> = getAllBaseSourceFolders()
        val allSourceTypeFolders: List<Path> = getAllSourceDocTypeFolders()
        val errorFolders: List<Path> = getSourceErrorFolder()
        val archiveFolders: List<Path> = getSourceArchiveFolders()
        val baseTargetFolders: List<Path> = getAllBaseTargetFolders()
        val allTargetTypeFolders: List<Path> = getAllTargetDocTypeFolders()

        // local dir must not overlap with source dirs
        val localDirSourceDirsOverlap: ValidationResult =
            if ((baseSourceFolders + allSourceTypeFolders).containsAll(localDirectory)) {
                ValidationResult.Failure(
                    localDirectory.map { path: Path ->
                        ValidationDetail.PathDetail(
                            path = path.toString(),
                            messageKey = LOCAL_DIR_OVERLAPS_WITH_SOURCE_DIRS
                        )
                    }
                )
            } else {
                ValidationResult.Success
            }

        // local dir must not overlap with target dirs
        val localDirTargetDirsOverlap: ValidationResult =
            if ((baseTargetFolders + allTargetTypeFolders + archiveFolders + errorFolders).containsAll(localDirectory)) {
                ValidationResult.Failure(
                    localDirectory.map { path: Path ->
                        ValidationDetail.PathDetail(
                            path = path.toString(),
                            messageKey = LOCAL_DIR_OVERLAPS_WITH_TARGET_DIRS
                        )
                    }
                )
            } else {
                ValidationResult.Success
            }

        // source dirs must not overlap with each other
        val sourceDirsOverlap: ValidationResult =
            (baseSourceFolders + allSourceTypeFolders)
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
                                        messageKey = DUPLICATE_SOURCE_DIRS,
                                    )

                                }
                        )
                    } else {
                        ValidationResult.Success
                    }
                }

        // target dirs must not overlap with source dirs
        val targetDirsOverlapWithSourceDirs: ValidationResult =
            (baseSourceFolders + allSourceTypeFolders)
                .intersect((baseTargetFolders + allTargetTypeFolders + archiveFolders).toSet())
                .let { overlappingDirs ->
                    if (overlappingDirs.isNotEmpty()) {
                        ValidationResult.Failure(
                            validationDetails =
                                overlappingDirs.map { path: Path ->
                                    ValidationDetail.PathDetail(
                                        path = path.toString(),
                                        messageKey = TARGET_DIR_OVERLAPS_SOURCE_DIRS,
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
            (localDirectory + baseSourceFolders + allSourceTypeFolders + baseTargetFolders + allTargetTypeFolders + archiveFolders)
                .filter { it.fileName?.toString() == ERROR_DIR_NAME }
                .let { overlappingErrorFolderDirs ->
                    if (overlappingErrorFolderDirs.isNotEmpty()) {
                        ValidationResult.Failure(
                            validationDetails =
                                overlappingErrorFolderDirs.map { path: Path ->
                                    ValidationDetail.PathDetail(
                                        path = path.toString(),
                                        messageKey = ERROR_AS_NON_ERROR_FOLDER_NAME_USED,
                                    )
                                }
                        )
                    } else {
                        ValidationResult.Success
                    }
                }

        val errorFolderOverlap: ValidationResult =
            errorFolders
                // should the baseSourceFolders also be used? the getEffectiveErrorFolder checks this and would append 'error' if that is the case
                .intersect((localDirectory + allSourceTypeFolders + baseTargetFolders + allTargetTypeFolders + archiveFolders).toSet())
                .let { overlappingDirs ->
                    if (overlappingDirs.isNotEmpty()) {
                        ValidationResult.Failure(
                            validationDetails =
                                overlappingDirs.map { path: Path ->
                                    ValidationDetail.PathDetail(
                                        path = path.toString(),
                                        messageKey = ERROR_DIR_OVERLAPS_NON_ERROR_DIR,
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

        val parseFailureCombined = parseFailures.fold(ValidationResult.Success as ValidationResult) { acc, v -> acc + v }
        return overlapResult + parseFailureCombined
    }

    fun validateCredentialValues(credentials: DTOs.CdrClientConfig.IdpCredentials): ValidationResult {
        val validations = mutableListOf<ValidationResult>()
        validateIsNotBlankOrPlaceholder(
            value = credentials.clientId,
            configItem = DomainObjects.ConfigurationItem.IDP_CLIENT_ID
        ).let { validations.add(it) }
        validateIsNotBlankOrPlaceholder(
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

    private fun validateConnectorFolders(connector: DTOs.CdrClientConfig.Connector): List<ValidationResult> {
        val baseValidations = listOf(
            validateDirectoryIsReadWritable(connector.sourceFolder),
            validateDirectoryIsReadWritable(connector.targetFolder)
        )

        val archiveValidations = if (connector.sourceArchiveEnabled) {
            val placeholderValidation = validateIsNotBlankOrPlaceholder(
                connector.sourceArchiveFolder.toString(),
                ARCHIVE_DIRECTORY
            )

            if (placeholderValidation is ValidationResult.Success) {
                listOf(placeholderValidation, validateDirectoryIsReadWritable(connector.sourceArchiveFolder!!))
            } else {
                listOf(placeholderValidation)
            }
        } else {
            emptyList()
        }

        val errorValidation = listOf(
            validateNotRequiredFolder(connector.sourceErrorFolder, DomainObjects.ConfigurationItem.ERROR_DIRECTORY)
        )

        val docTypeValidations = connector.docTypeFolders.flatMap { (_, docTypeFolder) ->
            listOf(
                validateNotRequiredFolder(docTypeFolder.sourceFolder, DomainObjects.ConfigurationItem.DOC_TYPE_SOURCE_DIRECTORY),
                validateNotRequiredFolder(docTypeFolder.targetFolder, DomainObjects.ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY)
            )
        }

        return baseValidations + archiveValidations + errorValidation + docTypeValidations
    }

    private fun validateNotRequiredFolder(folder: String?, configurationItem: DomainObjects.ConfigurationItem): ValidationResult =
        when (validateIsNotBlank(value = folder, configItem = configurationItem)) {
            is ValidationResult.Success -> validateDirectoryIsReadWritable(folder!!)
            is ValidationResult.Failure -> ValidationResult.Success
        }

    companion object {
        private const val PLACEHOLDER_VALUE = "value-required"
        private const val WINDOWS_DRIVE_ROOT_LENGTH = 3
    }

}

@OptIn(ExperimentalContracts::class)
private fun ValidationResult.isSuccessAndPathNotNull(path: Any?): Boolean {
    contract { returns(true) implies (path != null) }
    return this is ValidationResult.Success
}
