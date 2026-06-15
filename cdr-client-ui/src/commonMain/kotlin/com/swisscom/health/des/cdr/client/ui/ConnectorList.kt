package com.swisscom.health.des.cdr.client.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig as CdrClientConfigDto
import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.common.DomainObjects.ConfigurationItem
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.add_circle_24dp_000000_FILL0_wght400_GRAD0_opsz24
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.delete_24dp_000000_FILL0_wght400_GRAD0_opsz24
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_archive_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_archive_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_base_source_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_base_source_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_base_target_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_base_target_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doc_type_dirs
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_archive_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_archive_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_error_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_error_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_source_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_source_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_split_source_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_split_source_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_split_switch
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_split_switch_general_section
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_split_switch_request_section
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_split_switch_response_section
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_split_switch_subtitle
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_split_target_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_split_target_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_target_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_doctype_target_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_error_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_error_dir_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_id
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_id_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_mode
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_mode_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_connector_settings
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_local_folder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_local_folder_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_document_archive
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_document_archive_subtitle
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.message_loading_initial_config
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * Displays the list of connectors in the CDR client configuration view.
 */
@Composable
internal fun ConnectorList(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    uiState: CdrConfigUiState,
    initialConfigLoaded: Boolean,
    canEdit: Boolean,
) {
    CollapsibleGroup(
        modifier = modifier,
        title = stringResource(Res.string.label_client_connector_settings),
        initiallyExpanded = false,
    ) {
        if (!initialConfigLoaded) {
            Divider(modifier = modifier.padding(bottom = 8.dp))
            Text(text = stringResource(Res.string.message_loading_initial_config))
            Divider(modifier = modifier.padding(bottom = 8.dp))
        } else {
            // local directory (temp download directory)
            AsyncValidatedTextField(
                modifier = modifier.fillMaxWidth(),
                name = ConfigurationItem.LOCAL_DIRECTORY,
                value = uiState.clientServiceConfig.localFolder,
                onValueChange = { if (canEdit) viewModel.setLocalPath(it) },
                label = { Text(text = stringResource(Res.string.label_client_local_folder)) },
                placeHolder = { Text(text = stringResource(Res.string.label_client_local_folder_placeholder)) },
                asyncValidation = suspend {
                    validateNeitherBlankNorRoot(uiState.clientServiceConfig.localFolder) +
                            remoteViewValidations.validateDirectory(
                                uiState.clientServiceConfig,
                                uiState.clientServiceConfig.localFolder,
                                ConfigurationItem.LOCAL_DIRECTORY
                            )
                },
                revalidationKey = uiState.clientServiceConfig,
                enabled = canEdit,
            )

            for (connector in uiState.clientServiceConfig.customer) {
                Row(
                    modifier = modifier,
                ) {
                    Column(
                        modifier = modifier
                            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = OutlinedTextFieldDefaults.shape)
                            .weight(1.0F),
                    ) {
                        ConnectorSettingsGroup(
                            modifier = modifier,
                            connector = connector,
                            remoteViewValidations = remoteViewValidations,
                            uiState = uiState,
                            viewModel = viewModel,
                            canEdit = canEdit,
                        )
                    }

                    // Button to delete the connector on that line
                    IconButton(
                        onClick = { if (canEdit) viewModel.deleteConnector(connector) },
                        modifier = modifier
                            .offset(x = 8.dp)
                            .align(Alignment.CenterVertically)
                            .alpha(if (canEdit) 1.0f else 0.3f),
                        enabled = canEdit,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.delete_24dp_000000_FILL0_wght400_GRAD0_opsz24),
                            modifier = modifier,
                            contentDescription = null,
                        )
                    }
                }
            }

            // Button to add a new connector
            Row(
                modifier = modifier,
            ) {
                Spacer(modifier = Modifier.weight(1.0F))
                IconButton(
                    onClick = { if (canEdit) viewModel.addEmptyConnector() },
                    modifier = modifier.offset(x = 8.dp),
                    enabled = canEdit,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.add_circle_24dp_000000_FILL0_wght400_GRAD0_opsz24),
                        modifier = modifier.alpha(if (canEdit) 1.0f else 0.3f),
                        contentDescription = null,
                    )
                }
            }

        }
    }

}

/**
 * Displays the settings for a single connector in the CDR client configuration view.
 */
@Composable
private fun ConnectorSettingsGroup(
    modifier: Modifier,
    connector: CdrClientConfigDto.Connector,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    uiState: CdrConfigUiState,
    viewModel: CdrConfigViewModel,
    canEdit: Boolean,
) {
    CollapsibleGroup(
        modifier = modifier,
        title = "${connector.connectorId} - ${connector.mode}",
        initiallyExpanded = false,
    ) { _ ->

        fun mustBeAbsoluteAndReadWritable(directory: String, configItem: ConfigurationItem): suspend () -> DTOs.ValidationResult = suspend {
            validateNeitherBlankNorRoot(directory) +
                    remoteViewValidations.validateDirectory(
                        uiState.clientServiceConfig,
                        directory,
                        configItem
                    )
        }

        fun mustBeBlankOrAbsoluteAndReadWritable(directory: String, configItem: ConfigurationItem): suspend () -> DTOs.ValidationResult = suspend {
            if (directory.isBlank()) {
                DTOs.ValidationResult.Success
            } else {
                mustBeAbsoluteAndReadWritable(directory, configItem).invoke()
            }
        }

        Divider(modifier = modifier.padding(bottom = 8.dp))

        ValidatedConnectorId(
            modifier = modifier.fillMaxWidth(),
            remoteViewValidations = remoteViewValidations,
            viewModel = viewModel,
            connector = connector,
            canEdit = canEdit,
        )

        ValidatedMode(
            modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
            remoteViewValidations = remoteViewValidations,
            viewModel = viewModel,
            clientConfig = uiState.clientServiceConfig,
            connector = connector,
            canEdit = canEdit,
        )

        // base target directory
        AsyncValidatedTextField(
            modifier = modifier.fillMaxWidth(),
            name = ConfigurationItem.TARGET_DIRECTORY,
            value = connector.targetFolder,
            onValueChange = { if (canEdit) viewModel.setConnectorBaseTargetDir(it, connector) },
            label = { Text(text = stringResource(Res.string.label_client_connector_base_target_dir)) },
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_base_target_dir_placeholder)) },
            asyncValidation = mustBeAbsoluteAndReadWritable(connector.targetFolder, ConfigurationItem.TARGET_DIRECTORY),
            revalidationKey = uiState.clientServiceConfig,
            enabled = canEdit,
        )

        // base source directory
        AsyncValidatedTextField(
            modifier = modifier.fillMaxWidth(),
            name = ConfigurationItem.SOURCE_DIRECTORY,
            value = connector.sourceFolder,
            onValueChange = { if (canEdit) viewModel.setConnectorBaseSourceDir(it, connector) },
            label = { Text(text = stringResource(Res.string.label_client_connector_base_source_dir)) },
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_base_source_dir_placeholder)) },
            asyncValidation = mustBeAbsoluteAndReadWritable(connector.sourceFolder, ConfigurationItem.SOURCE_DIRECTORY),
            revalidationKey = uiState.clientServiceConfig,
            enabled = canEdit,
        )

        // base error directory
        AsyncValidatedTextField(
            modifier = modifier.fillMaxWidth(),
            name = ConfigurationItem.ERROR_DIRECTORY,
            value = connector.sourceErrorFolder ?: "",
            onValueChange = { if (canEdit) viewModel.setConnectorErrorDir(it, connector) },
            label = { Text(text = stringResource(Res.string.label_client_connector_error_dir)) },
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_error_dir_placeholder)) },
            asyncValidation = mustBeBlankOrAbsoluteAndReadWritable(connector.sourceErrorFolder ?: "", ConfigurationItem.ERROR_DIRECTORY),
            revalidationKey = uiState.clientServiceConfig,
            enabled = canEdit,
        )

        OnOffSwitch(
            name = ConfigurationItem.ARCHIVE_SWITCH,
            modifier = modifier.padding(bottom = 16.dp),
            title = stringResource(Res.string.label_enable_document_archive),
            subtitle = stringResource(Res.string.label_enable_document_archive_subtitle),
            checked = connector.sourceArchiveEnabled,
            onValueChange = { if (canEdit) viewModel.setConnectorArchiveEnabled(it, connector) },
            enabled = canEdit,
        )

        // base archive directory
        AsyncValidatedTextField(
            modifier = modifier.fillMaxWidth(),
            name = ConfigurationItem.ARCHIVE_DIRECTORY,
            value = connector.sourceArchiveFolder ?: "",
            onValueChange = { if (canEdit) viewModel.setConnectorArchiveDir(it, connector) },
            label = { Text(text = stringResource(Res.string.label_client_connector_archive_dir)) },
            placeHolder = { Text(text = stringResource(Res.string.label_client_connector_archive_dir_placeholder)) },
            asyncValidation =
                suspend {
                    if (!connector.sourceArchiveEnabled) DTOs.ValidationResult.Success
                    else mustBeBlankOrAbsoluteAndReadWritable(connector.sourceArchiveFolder ?: "", ConfigurationItem.ARCHIVE_DIRECTORY).invoke()
                },
            revalidationKey = uiState.clientServiceConfig,
            enabled = canEdit && connector.sourceArchiveEnabled,
        )

        //
        // BEGIN - Document Type Specific Directories
        //

        // Document-type-specific directories (collapsed by default)
        CollapsibleGroup(
            modifier = modifier,
            title = stringResource(Res.string.label_client_connector_doc_type_dirs),
            initiallyExpanded = false,
        ) { _ ->
            DocumentType.entries.filter { it != DocumentType.UNKNOWN }.forEach { docType ->
                NamedSectionDivider(text = docType.name)

                OnOffSwitch(
                    name = ConfigurationItem.UNKNOWN,
                    modifier = modifier.padding(bottom = 16.dp),
                    title = stringResource(Res.string.label_client_connector_doctype_split_switch),
                    subtitle = stringResource(Res.string.label_client_connector_doctype_split_switch_subtitle),
                    checked = connector.docTypeFolders[docType]?.requestResponseSplit ?: false,
                    onValueChange = { doSplit: Boolean -> if (canEdit) viewModel.setConnectorDocTypeRequestResponseSplit(docType, doSplit, connector) },
                    enabled = canEdit,
                )

                if (connector.docTypeFolders[docType]?.requestResponseSplit == true) {
                    NamedSectionDivider(
                        text = stringResource(Res.string.label_client_connector_doctype_split_switch_request_section),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    // request target directory
                    val requestTargetDir = connector.docTypeFolders[docType]?.targetFolderReq ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeRequestTargetDir(docType, it.trim(), connector) },
                        asyncValidation = mustBeAbsoluteAndReadWritable(requestTargetDir, ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY),
                        name = ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY,
                        value = requestTargetDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_split_target_dir, docType.name, "request")) },
                        placeHolder = {
                            Text(
                                text = stringResource(
                                    Res.string.label_client_connector_doctype_split_target_dir_placeholder,
                                    docType.name,
                                    "request"
                                )
                            )
                        },
                    )
                    // request source directory
                    val requestSourceDir = connector.docTypeFolders[docType]?.sourceFolderReq ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeRequestSourceDir(docType, it.trim(), connector) },
                        asyncValidation = mustBeAbsoluteAndReadWritable(requestSourceDir, ConfigurationItem.DOC_TYPE_SOURCE_DIRECTORY),
                        name = ConfigurationItem.DOC_TYPE_SOURCE_DIRECTORY,
                        value = requestSourceDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_split_source_dir, docType.name, "request")) },
                        placeHolder = {
                            Text(
                                text = stringResource(
                                    Res.string.label_client_connector_doctype_split_source_dir_placeholder,
                                    docType.name,
                                    "request"
                                )
                            )
                        },
                    )

                    NamedSectionDivider(
                        text = stringResource(Res.string.label_client_connector_doctype_split_switch_response_section),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    // response target directory
                    val responseTargetDir = connector.docTypeFolders[docType]?.targetFolderResp ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeResponseTargetDir(docType, it.trim(), connector) },
                        asyncValidation = mustBeAbsoluteAndReadWritable(responseTargetDir, ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY),
                        name = ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY,
                        value = responseTargetDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_split_target_dir, docType.name, "response")) },
                        placeHolder = {
                            Text(
                                text = stringResource(
                                    Res.string.label_client_connector_doctype_split_target_dir_placeholder,
                                    docType.name,
                                    "response"
                                )
                            )
                        },
                    )

                    // response source directory
                    val responseSourceDir = connector.docTypeFolders[docType]?.sourceFolderResp ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeResponseSourceDir(docType, it.trim(), connector) },
                        asyncValidation = mustBeAbsoluteAndReadWritable(responseSourceDir, ConfigurationItem.DOC_TYPE_SOURCE_DIRECTORY),
                        name = ConfigurationItem.DOC_TYPE_SOURCE_DIRECTORY,
                        value = responseSourceDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_split_source_dir, docType.name, "response")) },
                        placeHolder = {
                            Text(
                                text = stringResource(
                                    Res.string.label_client_connector_doctype_split_source_dir_placeholder,
                                    docType.name,
                                    "response"
                                )
                            )
                        },
                    )

                    NamedSectionDivider(
                        text = stringResource(Res.string.label_client_connector_doctype_split_switch_general_section),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    // error directory
                    val errorDir = connector.docTypeFolders[docType]?.errorFolder ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeErrorDir(docType, it.trim(), connector) },
                        asyncValidation = mustBeAbsoluteAndReadWritable(errorDir, ConfigurationItem.DOC_TYPE_ERROR_DIRECTORY),
                        name = ConfigurationItem.DOC_TYPE_ERROR_DIRECTORY,
                        value = errorDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_error_dir, docType.name)) },
                        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_error_dir_placeholder, docType)) },
                    )

                    // archive directory (only enabled if document archive is enabled at connector level)
                    val archiveDir = connector.docTypeFolders[docType]?.archiveFolder ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit && connector.sourceArchiveEnabled,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeArchiveDir(docType, it.trim(), connector) },
                        asyncValidation = suspend {
                            if (!connector.sourceArchiveEnabled) DTOs.ValidationResult.Success
                            else mustBeAbsoluteAndReadWritable(archiveDir, ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY).invoke()
                        },
                        name = ConfigurationItem.DOC_TYPE_ARCHIVE_DIRECTORY,
                        value = archiveDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_archive_dir, docType.name)) },
                        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_archive_dir_placeholder, docType.name)) },
                    )
                } else {
                    // target directory
                    val targetDir = connector.docTypeFolders[docType]?.targetFolder ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeTargetDir(docType, it.trim(), connector) },
                        asyncValidation = mustBeBlankOrAbsoluteAndReadWritable(targetDir, ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY),
                        name = ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY,
                        value = targetDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_target_dir, docType.name)) },
                        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_target_dir_placeholder, docType.name)) },
                    )

                    // source directory
                    val sourceDir = connector.docTypeFolders[docType]?.sourceFolder ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeSourceDir(docType, it.trim(), connector) },
                        asyncValidation = mustBeBlankOrAbsoluteAndReadWritable(sourceDir, ConfigurationItem.DOC_TYPE_SOURCE_DIRECTORY),
                        name = ConfigurationItem.DOC_TYPE_SOURCE_DIRECTORY,
                        value = sourceDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_source_dir, docType.name)) },
                        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_source_dir_placeholder, docType.name)) },
                    )

                    // error directory
                    val errorDir = connector.docTypeFolders[docType]?.errorFolder ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeErrorDir(docType, it.trim(), connector) },
                        asyncValidation = mustBeBlankOrAbsoluteAndReadWritable(errorDir, ConfigurationItem.DOC_TYPE_ERROR_DIRECTORY),
                        name = ConfigurationItem.DOC_TYPE_ERROR_DIRECTORY,
                        value = errorDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_error_dir, docType.name)) },
                        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_error_dir_placeholder, docType)) },
                    )

                    // archive directory (only enabled if document archive is enabled at connector level)
                    val archiveDir = connector.docTypeFolders[docType]?.archiveFolder ?: ""
                    AsyncValidatedTextField(
                        modifier = modifier.fillMaxWidth(),
                        revalidationKey = uiState.clientServiceConfig,
                        enabled = canEdit && connector.sourceArchiveEnabled,
                        onValueChange = { if (canEdit) viewModel.setConnectorDocTypeArchiveDir(docType, it.trim(), connector) },
                        asyncValidation = suspend {
                            if (!connector.sourceArchiveEnabled) DTOs.ValidationResult.Success
                            else mustBeBlankOrAbsoluteAndReadWritable(archiveDir, ConfigurationItem.DOC_TYPE_ARCHIVE_DIRECTORY).invoke()
                        },
                        name = ConfigurationItem.DOC_TYPE_ARCHIVE_DIRECTORY,
                        value = archiveDir,
                        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_archive_dir, docType.name)) },
                        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_archive_dir_placeholder, docType.name)) },
                    )
                }
            }
        }

        //
        // END - Document Type Specific Directories
        //
    }
}

@Composable
private fun ValidatedConnectorId(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    connector: CdrClientConfigDto.Connector,
    canEdit: Boolean,
) {
    val connectorIdValidation: suspend () -> DTOs.ValidationResult = suspend {
        remoteViewValidations.validateNotBlank(
            connector.connectorId,
            ConfigurationItem.CONNECTOR_ID
        )
    }
    AsyncValidatedTextField(
        name = ConfigurationItem.CONNECTOR_ID,
        modifier = modifier,
        asyncValidation = connectorIdValidation,
        label = { Text(text = stringResource(Res.string.label_client_connector_id)) },
        value = connector.connectorId,
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_id_placeholder)) },
        onValueChange = { if (canEdit) viewModel.setConnectorId(it, connector) },
        enabled = canEdit,
        revalidationKey = connector.connectorId,
    )
}

@Composable
private fun ValidatedMode(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    clientConfig: CdrClientConfigDto,
    connector: CdrClientConfigDto.Connector,
    canEdit: Boolean,
) {
    var connectorModeValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(connector.connectorId, connector.mode) {
        connectorModeValidationResult =
            remoteViewValidations.validateConnectorMode(
                connectorId = connector.connectorId,
                config = clientConfig,
                fieldName = ConfigurationItem.CONNECTOR_MODE
            )
    }
    DropDownList(
        name = ConfigurationItem.CONNECTOR_MODE,
        modifier = modifier,
        validatable = { connectorModeValidationResult },
        initiallyExpanded = false,
        options = { CdrClientConfigDto.Mode.entries.filter { it != CdrClientConfigDto.Mode.NONE } },
        label = { Text(text = stringResource(Res.string.label_client_connector_mode)) },
        value = connector.mode.toString(),
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_mode_placeholder)) },
        onValueChange = { if (canEdit) viewModel.setConnectorMode(it, connector) },
        enabled = canEdit,
    )
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
                    configItem = ConfigurationItem.LOCAL_DIRECTORY,
                    messageKey = DTOs.ValidationMessageKey.NOT_A_DIRECTORY
                )
            )
        )
    } else {
        DTOs.ValidationResult.Success
    }
}
