package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DIRECTORY_NOT_FOUND
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DUPLICATE_MODE
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.DUPLICATE_SOURCE_DIRS
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.FILE_BUSY_TEST_TIMEOUT_TOO_LONG
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey.ILLEGAL_MODE
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
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.CONNECTOR
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.CONNECTOR_MODE
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem.FILE_BUSY_TEST_TIMEOUT
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.toDto
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isWritable
import kotlin.io.path.notExists

private val logger = KotlinLogging.logger {}

@Service
@Suppress("TooManyFunctions")
internal class ConfigValidationService(
    private val config: CdrClientConfig,
    private val configurationWriter: ConfigurationWriter,
    private val environment: Environment
) {

    val isSchedulingAllowed: Boolean by lazy { isConfigSourceUnambiguous && isConfigValid }
    val isConfigSourceUnambiguous: Boolean by lazy { isConfigFromOneSource() }

    val isConfigValid: Boolean by lazy { validateAllConfigurationItems(config.toDto()) is ValidationResult.Success }

    @PostConstruct
    fun isConfigFromOneSource(): Boolean {
        val activeProfiles = environment.activeProfiles.toList()
        return if (!activeProfiles.contains("test")) {
            configurationWriter.isWriteableConfigurationUnambiguous()
        } else {
            true
        }
    }

    fun validateAllConfigurationItems(config: DTOs.CdrClientConfig): ValidationResult {
        val validations = mutableListOf<ValidationResult>()

        validations.add(validateDirectoryIsReadWritable(Path.of(config.localFolder)))
        validations.add(validateDirectoryOverlap(config))
        validations.add(validateModeValue(config.customer))
        validations.add(validateModeOverlap(config.customer))
        validations.add(validateFileBusyTestTimeout(fileBusyTestTimeout = config.fileBusyTestTimeout, fileBusyTestInterval = config.fileBusyTestInterval))
        validations.add(validateConnectorIsPresent(config.customer))
        validations.add(validateCredentialValues(config.idpCredentials))

        return validations.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, validationResult: ValidationResult ->
                acc + validationResult
            }
        )
    }

    fun validateAvailableDiskspace(connectors: List<DTOs.CdrClientConfig.Connector>): ValidationResult {
        logger.trace { "Validating available disk space for connectors: $connectors" }
        TODO()
    }

    fun validateConnectorIsPresent(customer: List<DTOs.CdrClientConfig.Connector>?): ValidationResult =
        if (customer.isNullOrEmpty()) {
            ValidationResult.Failure(
                listOf(
                    DTOs.ValidationDetail.ConfigItemDetail(
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
                    DTOs.ValidationDetail.ConfigItemDetail(
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
                                DTOs.ValidationDetail.ConnectorDetail(
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
                            DTOs.ValidationDetail.ConnectorDetail(
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

    fun validateDirectoryIsReadWritable(path: Path?): ValidationResult =
        if (path == null || path.notExists()) {
            ValidationResult.Failure(
                listOf(DTOs.ValidationDetail.PathDetail(path = path.toString(), messageKey = DIRECTORY_NOT_FOUND))
            )
        } else if (!path.isDirectory()) {
            ValidationResult.Failure(
                listOf(DTOs.ValidationDetail.PathDetail(path = path.toString(), messageKey = NOT_A_DIRECTORY))
            )
        } else if (!path.isReadable() || !path.isWritable()) {
            ValidationResult.Failure(
                listOf(DTOs.ValidationDetail.PathDetail(path = path.toString(), messageKey = NOT_READ_WRITABLE))
            )
        } else {
            ValidationResult.Success
        }

    fun validateIsNotBlankOrPlaceholder(value: String?, configItem: DomainObjects.ConfigurationItem): ValidationResult =
        if (value.isNullOrBlank()) {
            ValidationResult.Failure(
                listOf(DTOs.ValidationDetail.ConfigItemDetail(configItem = configItem, messageKey = VALUE_IS_BLANK))
            )
        } else if (value == PLACEHOLDER_VALUE) {
            ValidationResult.Failure(
                listOf(DTOs.ValidationDetail.ConfigItemDetail(configItem = configItem, messageKey = VALUE_IS_PLACEHOLDER))
            )
        } else {
            ValidationResult.Success
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun validateDirectoryOverlap(config: DTOs.CdrClientConfig): ValidationResult {
        fun getAllBaseSourceFolders(): List<Path> = config.customer.map { connector ->
            Path.of(connector.sourceFolder)
                .also {
                    logger.debug { "connector [${connector.connectorId}-${connector.mode}] base source folder: [${connector.sourceFolder}]" }
                }
        }

        fun getAllBaseTargetFolders(): List<Path> = config.customer.map { connector ->
            Path.of(connector.targetFolder)
                .also {
                    logger.debug { "connector [${connector.connectorId}-${connector.mode}] base target folder: [${connector.targetFolder}]" }
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
                    effectiveSourceFolder(sourceFolder = Path.of(sourceFolder), docTypeFolders = it)
                }
            }

        fun getAllTargetDocTypeFolders(): List<Path> = config.customer
            .map { connector -> connector.targetFolder to connector.docTypeFolders.values }
            .flatMap { (targetFolder, docTypeFolders) ->
                docTypeFolders.mapNotNull {
                    effectiveTargetFolder(targetFolder = Path.of(targetFolder), docTypeFolders = it)
                }
            }

        fun getSourceArchiveAndErrorFolders(): List<Path> = config.customer
            .flatMap { connector ->
                listOf(connector.sourceArchiveFolder, connector.sourceErrorFolder)
                    .also {
                        logger.debug {
                            "connector [${connector.connectorId}-${connector.mode}] source archive folder: [${connector.sourceArchiveFolder}], " +
                                    "source error folder: [${connector.sourceErrorFolder}]"
                        }
                    }
            }
            .map { Path.of(it ?: EMPTY_STRING) }

        val localDirectory: List<Path> = listOf(Path.of(config.localFolder))
        val baseSourceFolders: List<Path> = getAllBaseSourceFolders()
        val allSourceTypeFolders: List<Path> = getAllSourceDocTypeFolders()
        val archiveAndErrorFolders: List<Path> = getSourceArchiveAndErrorFolders()
        val baseTargetFolders: List<Path> = getAllBaseTargetFolders()
        val allTargetTypeFolders: List<Path> = getAllTargetDocTypeFolders()

        // local dir must not overlap with source dirs
        val localDirSourceDirsOverlap: ValidationResult =
            if ((baseSourceFolders + allSourceTypeFolders).containsAll(localDirectory)) {
                ValidationResult.Failure(
                    localDirectory.map { path: Path ->
                        DTOs.ValidationDetail.PathDetail(
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
            if ((baseTargetFolders + allTargetTypeFolders + archiveAndErrorFolders).containsAll(localDirectory)) {
                ValidationResult.Failure(
                    localDirectory.map { path: Path ->
                        DTOs.ValidationDetail.PathDetail(
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
                                    DTOs.ValidationDetail.PathDetail(
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
                .intersect(baseTargetFolders + allTargetTypeFolders + archiveAndErrorFolders)
                .let { overlappingDirs ->
                    if (overlappingDirs.isNotEmpty()) {
                        ValidationResult.Failure(
                            validationDetails =
                                overlappingDirs.map { path: Path ->
                                    DTOs.ValidationDetail.PathDetail(
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

        return localDirSourceDirsOverlap + localDirTargetDirsOverlap + sourceDirsOverlap + targetDirsOverlapWithSourceDirs
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
        validateIsNotBlankOrPlaceholder(
            value = credentials.tenantId,
            configItem = DomainObjects.ConfigurationItem.IDP_TENANT_ID
        )

        return validations.fold(
            initial = ValidationResult.Success,
            operation = { acc: ValidationResult, validationResult: ValidationResult ->
                acc + validationResult
            }
        )
    }

    companion object {
        private const val PLACEHOLDER_VALUE = "value-required"
    }

}
