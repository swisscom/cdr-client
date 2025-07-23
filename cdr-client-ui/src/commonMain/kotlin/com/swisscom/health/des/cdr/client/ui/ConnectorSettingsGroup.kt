package com.swisscom.health.des.cdr.client.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_multiple_connector_definitions_found
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_archive_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_archive_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_base_source_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_base_source_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_base_target_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_base_target_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_source_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_source_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_target_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_target_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_download_dirs
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_error_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_error_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_id
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_id_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_mode
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_mode_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_settings
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_upload_dirs
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_local_folder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_local_folder_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_document_archive
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_document_archive_subtitle
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.compose.resources.stringResource
import java.io.File

private val logger = KotlinLogging.logger { }

/**
 * A composable function that displays the connector settings group in the CDR client configuration view.
 */
@Composable
internal fun ConnectorSettingsGroup(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    uiState: CdrConfigUiState,
) {
    CollapsibleGroup(
        modifier = modifier,
        title = Res.string.label_client_connector_settings,
        initiallyExpanded = false,
    ) { containerColor ->

        // Warn if more than one connector configuration is found
        // TODO: Support multiple connectors in the UI
        LaunchedEffect(uiState.clientServiceConfig.customer.size) {
            if (uiState.clientServiceConfig.customer.size > 1) {
                logger.trace {
                    "'${uiState.clientServiceConfig.customer.size}' connectors found: '${uiState.clientServiceConfig.customer.joinToString { it.connectorId }}'"
                }
                viewModel.reportError(
                    messageKey = Res.string.error_multiple_connector_definitions_found,
                    formatArgs = arrayOf(uiState.clientServiceConfig.customer.joinToString { it.connectorId })
                )
            }
        }

        // Connector ID
        var connectorIdValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
        LaunchedEffect(uiState.clientServiceConfig.customer[0].connectorId) {
            connectorIdValidationResult =
                remoteViewValidations.validateNotBlank(
                    uiState.clientServiceConfig.customer[0].connectorId,
                    DomainObjects.ConfigurationItem.CONNECTOR_ID
                )
        }
        ValidatedTextField(
            name = DomainObjects.ConfigurationItem.CONNECTOR_ID,
            modifier = modifier.fillMaxWidth(),
            validatable = { connectorIdValidationResult },
            label = { Text(text = stringResource(Res.string.label_client_connector_id)) },
            value = uiState.clientServiceConfig.customer[0].connectorId,
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_id_placeholder)) },
            onValueChange = { viewModel.setConnectorId(it) },
        )

        // TODO: add validation for overlapping modes per connector once multiple connectors are supported
        // Connector mode
        DropDownList(
            name = DomainObjects.ConfigurationItem.CONNECTOR_MODE,
            modifier = modifier.fillMaxWidth(),
            initiallyExpanded = false,
            options = { DTOs.CdrClientConfig.Mode.entries.filter { it != DTOs.CdrClientConfig.Mode.NONE } },
            label = { Text(text = stringResource(Res.string.label_client_connector_mode)) },
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_mode_placeholder)) },
            value = uiState.clientServiceConfig.customer[0].mode.toString(),
            onValueChange = { viewModel.setConnectorMode(it) }
        )

        // TODO: this is a global setting, not one of a connector --> move to a global place once more than one connector is supported
        // Temporary download directory
        var tmpDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
        LaunchedEffect(uiState.clientServiceConfig.localFolder) {
            tmpDirValidationResult = validateNeitherBlankNorRoot(uiState.clientServiceConfig.localFolder) +
                    remoteViewValidations.validateDirectory(
                        uiState.clientServiceConfig,
                        uiState.clientServiceConfig.localFolder,
                        DomainObjects.ConfigurationItem.LOCAL_DIRECTORY
                    )
        }
        ValidatedTextField(
            name = DomainObjects.ConfigurationItem.LOCAL_DIRECTORY,
            modifier = modifier.fillMaxWidth(),
            validatable = { tmpDirValidationResult },
            label = { Text(text = stringResource(Res.string.label_client_local_folder)) },
            value = uiState.clientServiceConfig.localFolder,
            placeHolder = { Text(text = stringResource(Res.string.label_client_local_folder_placeholder)) },
            onValueChange = { viewModel.setLocalPath(it) },
        )

        // Error directory
        var errorDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
        LaunchedEffect(uiState.clientServiceConfig.customer[0].sourceErrorFolder) {
            errorDirValidationResult = validateNeitherBlankNorRoot(uiState.clientServiceConfig.customer[0].sourceErrorFolder) +
                    remoteViewValidations.validateDirectory(
                        uiState.clientServiceConfig,
                        uiState.clientServiceConfig.customer[0].sourceErrorFolder,
                        DomainObjects.ConfigurationItem.ERROR_DIRECTORY
                    )
        }
        ValidatedTextField(
            name = DomainObjects.ConfigurationItem.ERROR_DIRECTORY,
            modifier = modifier.fillMaxWidth(),
            validatable = { errorDirValidationResult },
            label = { Text(text = stringResource(Res.string.label_client_connector_error_dir)) },
            value = uiState.clientServiceConfig.customer[0].sourceErrorFolder,
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_error_dir_placeholder)) },
            onValueChange = { viewModel.setConnectorErrorDir(it) },
        )

        NamedSectionDivider(text = stringResource(Res.string.label_client_connector_archive_dir))

        OnOffSwitch(
            name = DomainObjects.ConfigurationItem.ARCHIVE_SWITCH,
            modifier = modifier.padding(bottom = 16.dp),
            title = stringResource(Res.string.label_enable_document_archive),
            subtitle = stringResource(Res.string.label_enable_document_archive_subtitle),
            checked = uiState.clientServiceConfig.customer[0].sourceArchiveEnabled,
            onValueChange = { viewModel.setConnectorArchiveEnabled(it) },
        )

        // Archive directory
        var archiveDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
        LaunchedEffect(uiState.clientServiceConfig.customer[0].sourceArchiveFolder) {
            archiveDirValidationResult = validateNeitherBlankNorRoot(uiState.clientServiceConfig.customer[0].sourceArchiveFolder) +
                    remoteViewValidations.validateDirectory(
                        uiState.clientServiceConfig,
                        uiState.clientServiceConfig.customer[0].sourceArchiveFolder,
                        DomainObjects.ConfigurationItem.ARCHIVE_DIRECTORY
                    )
        }
        ValidatedTextField(
            name = DomainObjects.ConfigurationItem.ARCHIVE_DIRECTORY,
            modifier = modifier.fillMaxWidth(),
            validatable = { archiveDirValidationResult },
            label = { Text(text = stringResource(Res.string.label_client_connector_archive_dir)) },
            value = uiState.clientServiceConfig.customer[0].sourceArchiveFolder,
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_archive_dir_placeholder)) },
            onValueChange = { viewModel.setConnectorArchiveDir(it) },
        )

        //
        // BEGIN - Target directories
        //
        NamedSectionDivider(text = stringResource(Res.string.label_client_connector_download_dirs))

        // Base target directory
        var targetDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
        LaunchedEffect(uiState.clientServiceConfig.customer[0].targetFolder) {
            targetDirValidationResult = validateNeitherBlankNorRoot(uiState.clientServiceConfig.customer[0].targetFolder) +
                    remoteViewValidations.validateDirectory(
                        uiState.clientServiceConfig,
                        uiState.clientServiceConfig.customer[0].targetFolder,
                        DomainObjects.ConfigurationItem.TARGET_DIRECTORY
                    )
        }
        ValidatedTextField(
            name = DomainObjects.ConfigurationItem.TARGET_DIRECTORY,
            modifier = modifier.fillMaxWidth(),
            validatable = { targetDirValidationResult },
            label = { Text(text = stringResource(Res.string.label_client_connector_base_target_dir)) },
            value = uiState.clientServiceConfig.customer[0].targetFolder,
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_base_target_dir_placeholder)) },
            onValueChange = { viewModel.setConnectorBaseTargetDir(it) },
        )

        // Document-type-specific target directories
        for (doctype in DTOs.CdrClientConfig.DocumentType.entries.filter { it != DTOs.CdrClientConfig.DocumentType.UNDEFINED }) {
            val docTypeFolder: String? = uiState.clientServiceConfig.customer[0].docTypeFolders[doctype]?.targetFolder
            var docTypeDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
            LaunchedEffect(uiState.clientServiceConfig.customer[0].docTypeFolders) {
                docTypeDirValidationResult =
                    if (docTypeFolder.isNullOrBlank()) {
                        DTOs.ValidationResult.Success
                    } else {
                        // Validate the document type specific target directory
                        validateNeitherBlankNorRoot(docTypeFolder) +
                                remoteViewValidations.validateDirectory(
                                    uiState.clientServiceConfig,
                                    docTypeFolder,
                                    DomainObjects.ConfigurationItem.TARGET_DIRECTORY
                                )
                    }
            }
            ValidatedTextField(
                name = DomainObjects.ConfigurationItem.TARGET_DIRECTORY,
                modifier = modifier.fillMaxWidth(),
                validatable = { docTypeDirValidationResult },
                label = { Text(text = stringResource(Res.string.label_client_connector_doctype_target_dir, doctype.name)) },
                value = docTypeFolder ?: "",
                placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_target_dir_placeholder)) },
                onValueChange = { viewModel.setConnectorDocTypeTargetDir(doctype, it.trim()) },
            )
        }

        //
        // END - Target directories
        //

        //
        // BEGIN - Source directories
        //
        NamedSectionDivider(text = stringResource(Res.string.label_client_connector_upload_dirs))

        // Base source directory
        var sourceDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
        LaunchedEffect(uiState.clientServiceConfig.customer[0].sourceFolder) {
            sourceDirValidationResult = validateNeitherBlankNorRoot(uiState.clientServiceConfig.customer[0].sourceFolder) +
                    remoteViewValidations.validateDirectory(
                        uiState.clientServiceConfig,
                        uiState.clientServiceConfig.customer[0].sourceFolder,
                        DomainObjects.ConfigurationItem.SOURCE_DIRECTORY
                    )
        }
        ValidatedTextField(
            name = DomainObjects.ConfigurationItem.SOURCE_DIRECTORY,
            modifier = modifier.fillMaxWidth(),
            validatable = { sourceDirValidationResult },
            label = { Text(text = stringResource(Res.string.label_client_connector_base_source_dir)) },
            value = uiState.clientServiceConfig.customer[0].sourceFolder,
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_base_source_dir_placeholder)) },
            onValueChange = { viewModel.setConnectorBaseSourceDir(it) },
        )

        // Document-type-specific source directories
        for (doctype in DTOs.CdrClientConfig.DocumentType.entries.filter { it != DTOs.CdrClientConfig.DocumentType.UNDEFINED }) {
            val docTypeFolder: String? = uiState.clientServiceConfig.customer[0].docTypeFolders[doctype]?.sourceFolder
            var docTypeDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
            LaunchedEffect(uiState.clientServiceConfig.customer[0].docTypeFolders) {
                docTypeDirValidationResult =
                    if (docTypeFolder.isNullOrBlank()) {
                        DTOs.ValidationResult.Success
                    } else {
                        // Validate the document type specific source directory
                        validateNeitherBlankNorRoot(docTypeFolder) +
                                remoteViewValidations.validateDirectory(
                                    uiState.clientServiceConfig,
                                    docTypeFolder,
                                    DomainObjects.ConfigurationItem.SOURCE_DIRECTORY
                                )
                    }
            }
            ValidatedTextField(
                name = DomainObjects.ConfigurationItem.SOURCE_DIRECTORY,
                modifier = modifier.fillMaxWidth(),
                validatable = { docTypeDirValidationResult },
                label = { Text(text = stringResource(Res.string.label_client_connector_doctype_source_dir, doctype.name)) },
                value = docTypeFolder ?: "",
                placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_source_dir_placeholder)) },
                onValueChange = { viewModel.setConnectorDocTypeSourceDir(doctype, it.trim()) },
            )
        }

        //
        // END - Source directories
        //
    }
}

/**
 * The remote path checks require the use of `java.nio.Path`; and `Path` translates both "" and "/"
 * into the current working directory (at least on Linux). Which is likely to exist and might be
 * read/writable, and so passes validation. But the user sees a blank field (or "/") in the UI. So,
 * as a workaround, we validate this particular case locally.
 */
private fun validateNeitherBlankNorRoot(path: String?): DTOs.ValidationResult {
    return if (path?.removeSuffix(File.separator).isNullOrBlank()) {
        DTOs.ValidationResult.Failure(
            listOf(
                DTOs.ValidationDetail.ConfigItemDetail(
                    configItem = DomainObjects.ConfigurationItem.LOCAL_DIRECTORY,
                    messageKey = DTOs.ValidationMessageKey.NOT_A_DIRECTORY
                )
            )
        )
    } else {
        DTOs.ValidationResult.Success
    }
}