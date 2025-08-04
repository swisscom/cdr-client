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
import androidx.compose.ui.unit.dp
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.add_circle_24dp_000000_FILL0_wght400_GRAD0_opsz24
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.delete_24dp_000000_FILL0_wght400_GRAD0_opsz24
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
) {
    CollapsibleGroup(
        modifier = modifier,
        title = stringResource(Res.string.label_client_connector_settings),
        initiallyExpanded = false,
    ) {

        ValidatedTempDownloadDir(
            modifier = modifier.fillMaxWidth(),
            remoteViewValidations = remoteViewValidations,
            viewModel = viewModel,
            clientConfig = uiState.clientServiceConfig,
        )

        for (connector in uiState.clientServiceConfig.customer) {
            Row(
                modifier = modifier,
            ) {
                Column(
                    modifier = modifier.border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = OutlinedTextFieldDefaults.shape).weight(1.0F),
                ) {
                    ConnectorSettingsGroup(
                        modifier = modifier,
                        connector = connector,
                        remoteViewValidations = remoteViewValidations,
                        uiState = uiState,
                        viewModel = viewModel,
                    )
                }

                // Button to delete the connector on that line
                IconButton(
                    onClick = { viewModel.deleteConnector(connector) },
                    modifier = modifier.offset(x = 8.dp).align(Alignment.CenterVertically)
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
                onClick = { viewModel.addEmptyConnector() },
                modifier = modifier.offset(x = 8.dp)
            ) {
                Icon(
                    painter = painterResource(Res.drawable.add_circle_24dp_000000_FILL0_wght400_GRAD0_opsz24),
                    modifier = modifier,
                    contentDescription = null,
                )
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
    connector: DTOs.CdrClientConfig.Connector,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    uiState: CdrConfigUiState,
    viewModel: CdrConfigViewModel,
) {
    CollapsibleGroup(
        modifier = modifier,
        title = "${connector.connectorId} - ${connector.mode}",
        initiallyExpanded = false,
    ) { _ ->

        Divider(modifier = modifier.padding(bottom = 8.dp))

        ValidatedConnectorId(
            modifier = modifier.fillMaxWidth(),
            remoteViewValidations = remoteViewValidations,
            viewModel = viewModel,
            connector = connector,
        )

        ValidatedMode(
            modifier = modifier.fillMaxWidth(),
            remoteViewValidations = remoteViewValidations,
            viewModel = viewModel,
            clientConfig = uiState.clientServiceConfig,
            connector = connector,
        )

        ValidatedErrorDir(
            modifier = modifier.fillMaxWidth(),
            remoteViewValidations = remoteViewValidations,
            viewModel = viewModel,
            clientConfig = uiState.clientServiceConfig,
            connector = connector,
        )

        NamedSectionDivider(text = stringResource(Res.string.label_client_connector_archive_dir))

        // Enable/Disable document archive
        OnOffSwitch(
            name = DomainObjects.ConfigurationItem.ARCHIVE_SWITCH,
            modifier = modifier.padding(bottom = 16.dp),
            title = stringResource(Res.string.label_enable_document_archive),
            subtitle = stringResource(Res.string.label_enable_document_archive_subtitle),
            checked = connector.sourceArchiveEnabled,
            onValueChange = { viewModel.setConnectorArchiveEnabled(it, connector) },
        )

        ValidatedArchiveDir(
            modifier = modifier.fillMaxWidth(),
            remoteViewValidations = remoteViewValidations,
            viewModel = viewModel,
            clientConfig = uiState.clientServiceConfig,
            connector = connector,
        )

        //
        // BEGIN - Target directories
        //
        NamedSectionDivider(text = stringResource(Res.string.label_client_connector_download_dirs))

        ValidatedBaseTargetDir(
            modifier = modifier.fillMaxWidth(),
            remoteViewValidations = remoteViewValidations,
            viewModel = viewModel,
            clientConfig = uiState.clientServiceConfig,
            connector = connector,
        )

        // Document-type-specific target directories
        for (doctype in DTOs.CdrClientConfig.DocumentType.entries.filter { it != DTOs.CdrClientConfig.DocumentType.UNDEFINED }) {
            val docTypeFolder: String? = connector.docTypeFolders[doctype]?.targetFolder
            ValidatedDocTypeTargetDir(
                modifier = modifier.fillMaxWidth(),
                remoteViewValidations = remoteViewValidations,
                viewModel = viewModel,
                clientConfig = uiState.clientServiceConfig,
                doctype = doctype,
                directory = docTypeFolder,
                connector = connector,
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
        ValidatedSourceBaseDir(
            modifier = modifier.fillMaxWidth(),
            remoteViewValidations = remoteViewValidations,
            viewModel = viewModel,
            clientConfig = uiState.clientServiceConfig,
            connector = connector,
        )

        // Document-type-specific source directories
        for (doctype in DTOs.CdrClientConfig.DocumentType.entries.filter { it != DTOs.CdrClientConfig.DocumentType.UNDEFINED }) {
            val docTypeFolder: String? = connector.docTypeFolders[doctype]?.sourceFolder
            ValidatedDocTypeSourceDir(
                modifier = modifier.fillMaxWidth(),
                remoteViewValidations = remoteViewValidations,
                viewModel = viewModel,
                clientConfig = uiState.clientServiceConfig,
                doctype = doctype,
                directory = docTypeFolder,
                connector = connector,
            )
        }

        //
        // END - Source directories
        //
    }
}

@Composable
private fun ValidatedTempDownloadDir(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    clientConfig: DTOs.CdrClientConfig,
) {
    var tmpDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(clientConfig) {
        tmpDirValidationResult = validateNeitherBlankNorRoot(clientConfig.localFolder) +
                remoteViewValidations.validateDirectory(
                    clientConfig,
                    clientConfig.localFolder,
                    DomainObjects.ConfigurationItem.LOCAL_DIRECTORY
                )
    }
    ValidatedTextField(
        name = DomainObjects.ConfigurationItem.LOCAL_DIRECTORY,
        modifier = modifier,
        validatable = { tmpDirValidationResult },
        label = { Text(text = stringResource(Res.string.label_client_local_folder)) },
        value = clientConfig.localFolder,
        placeHolder = { Text(text = stringResource(Res.string.label_client_local_folder_placeholder)) },
        onValueChange = { viewModel.setLocalPath(it) },
    )
}

@Composable
private fun ValidatedConnectorId(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    connector: DTOs.CdrClientConfig.Connector,
) {
    var connectorIdValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(connector.connectorId) {
        connectorIdValidationResult =
            remoteViewValidations.validateNotBlank(
                connector.connectorId,
                DomainObjects.ConfigurationItem.CONNECTOR_ID
            )
    }
    ValidatedTextField(
        name = DomainObjects.ConfigurationItem.CONNECTOR_ID,
        modifier = modifier,
        validatable = { connectorIdValidationResult },
        label = { Text(text = stringResource(Res.string.label_client_connector_id)) },
        value = connector.connectorId,
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_id_placeholder)) },
        onValueChange = { viewModel.setConnectorId(it, connector) },
    )
}

@Composable
private fun ValidatedMode(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    clientConfig: DTOs.CdrClientConfig,
    connector: DTOs.CdrClientConfig.Connector,
) {
    var connectorModeValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(connector.connectorId, connector.mode) {
        connectorModeValidationResult =
            remoteViewValidations.validateConnectorMode(
                connectorId = connector.connectorId,
                config = clientConfig,
                fieldName = DomainObjects.ConfigurationItem.CONNECTOR_MODE
            )
    }
    DropDownList(
        name = DomainObjects.ConfigurationItem.CONNECTOR_MODE,
        modifier = modifier,
        validatable = { connectorModeValidationResult },
        initiallyExpanded = false,
        options = { DTOs.CdrClientConfig.Mode.entries.filter { it != DTOs.CdrClientConfig.Mode.NONE } },
        label = { Text(text = stringResource(Res.string.label_client_connector_mode)) },
        value = connector.mode.toString(),
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_mode_placeholder)) },
        onValueChange = { viewModel.setConnectorMode(it, connector) },
    )
}

@Composable
private fun ValidatedErrorDir(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    clientConfig: DTOs.CdrClientConfig,
    connector: DTOs.CdrClientConfig.Connector,
) {
    var errorDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(clientConfig) {
        errorDirValidationResult = validateNeitherBlankNorRoot(connector.sourceErrorFolder) +
                remoteViewValidations.validateDirectory(
                    clientConfig,
                    connector.sourceErrorFolder,
                    DomainObjects.ConfigurationItem.ERROR_DIRECTORY
                )
    }
    ValidatedTextField(
        name = DomainObjects.ConfigurationItem.ERROR_DIRECTORY,
        modifier = modifier,
        validatable = { errorDirValidationResult },
        label = { Text(text = stringResource(Res.string.label_client_connector_error_dir)) },
        value = connector.sourceErrorFolder,
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_error_dir_placeholder)) },
        onValueChange = { viewModel.setConnectorErrorDir(it, connector) },
    )
}

@Composable
private fun ValidatedArchiveDir(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    clientConfig: DTOs.CdrClientConfig,
    connector: DTOs.CdrClientConfig.Connector,
) {
    var archiveDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(clientConfig) {
        archiveDirValidationResult = validateNeitherBlankNorRoot(connector.sourceArchiveFolder) +
                remoteViewValidations.validateDirectory(
                    clientConfig,
                    connector.sourceArchiveFolder,
                    DomainObjects.ConfigurationItem.ARCHIVE_DIRECTORY
                )
    }
    ValidatedTextField(
        name = DomainObjects.ConfigurationItem.ARCHIVE_DIRECTORY,
        modifier = modifier,
        validatable = { archiveDirValidationResult },
        label = { Text(text = stringResource(Res.string.label_client_connector_archive_dir)) },
        value = connector.sourceArchiveFolder,
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_archive_dir_placeholder)) },
        onValueChange = { viewModel.setConnectorArchiveDir(it, connector) },
    )
}

@Composable
private fun ValidatedBaseTargetDir(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    clientConfig: DTOs.CdrClientConfig,
    connector: DTOs.CdrClientConfig.Connector,
) {
    var targetDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(clientConfig) {
        targetDirValidationResult = validateNeitherBlankNorRoot(connector.targetFolder) +
                remoteViewValidations.validateDirectory(
                    clientConfig,
                    connector.targetFolder,
                    DomainObjects.ConfigurationItem.TARGET_DIRECTORY
                )
    }
    ValidatedTextField(
        name = DomainObjects.ConfigurationItem.TARGET_DIRECTORY,
        modifier = modifier,
        validatable = { targetDirValidationResult },
        label = { Text(text = stringResource(Res.string.label_client_connector_base_target_dir)) },
        value = connector.targetFolder,
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_base_target_dir_placeholder)) },
        onValueChange = { viewModel.setConnectorBaseTargetDir(it, connector) },
    )
}

@Composable
private fun ValidatedDocTypeTargetDir(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    clientConfig: DTOs.CdrClientConfig,
    doctype: DTOs.CdrClientConfig.DocumentType,
    directory: String?,
    connector: DTOs.CdrClientConfig.Connector,
) {
    var docTypeDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(clientConfig) {
        docTypeDirValidationResult =
            if (directory.isNullOrBlank()) {
                DTOs.ValidationResult.Success
            } else {
                // Validate the document type specific target directory
                validateNeitherBlankNorRoot(directory) +
                        remoteViewValidations.validateDirectory(
                            clientConfig,
                            directory,
                            DomainObjects.ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY
                        )
            }
    }
    ValidatedTextField(
        name = DomainObjects.ConfigurationItem.DOC_TYPE_TARGET_DIRECTORY,
        modifier = modifier,
        validatable = { docTypeDirValidationResult },
        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_target_dir, doctype.name)) },
        value = directory ?: "",
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_target_dir_placeholder)) },
        onValueChange = { viewModel.setConnectorDocTypeTargetDir(doctype, it.trim(), connector) },
    )
}

@Composable
private fun ValidatedSourceBaseDir(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    clientConfig: DTOs.CdrClientConfig,
    connector: DTOs.CdrClientConfig.Connector,
) {
    var sourceDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(clientConfig) {
        sourceDirValidationResult = validateNeitherBlankNorRoot(connector.sourceFolder) +
                remoteViewValidations.validateDirectory(
                    clientConfig,
                    connector.sourceFolder,
                    DomainObjects.ConfigurationItem.SOURCE_DIRECTORY
                )
    }
    ValidatedTextField(
        name = DomainObjects.ConfigurationItem.SOURCE_DIRECTORY,
        modifier = modifier,
        validatable = { sourceDirValidationResult },
        label = { Text(text = stringResource(Res.string.label_client_connector_base_source_dir)) },
        value = connector.sourceFolder,
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_base_source_dir_placeholder)) },
        onValueChange = { viewModel.setConnectorBaseSourceDir(it, connector) },
    )
}

@Composable
private fun ValidatedDocTypeSourceDir(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    clientConfig: DTOs.CdrClientConfig,
    doctype: DTOs.CdrClientConfig.DocumentType,
    directory: String?,
    connector: DTOs.CdrClientConfig.Connector,
) {
    var docTypeDirValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
    LaunchedEffect(clientConfig) {
        docTypeDirValidationResult =
            if (directory.isNullOrBlank()) {
                DTOs.ValidationResult.Success
            } else {
                // Validate the document type specific source directory
                validateNeitherBlankNorRoot(directory) +
                        remoteViewValidations.validateDirectory(
                            clientConfig,
                            directory,
                            DomainObjects.ConfigurationItem.DOC_TYPE_SOURCE_DIRECTORY
                        )
            }
    }
    ValidatedTextField(
        name = DomainObjects.ConfigurationItem.DOC_TYPE_SOURCE_DIRECTORY,
        modifier = modifier,
        validatable = { docTypeDirValidationResult },
        label = { Text(text = stringResource(Res.string.label_client_connector_doctype_source_dir, doctype.name)) },
        value = directory ?: "",
        placeHolder = { Text(text = stringResource(Res.string.label_client_connector_doctype_source_dir_placeholder)) },
        onValueChange = { viewModel.setConnectorDocTypeSourceDir(doctype, it.trim(), connector) },
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
                    configItem = DomainObjects.ConfigurationItem.LOCAL_DIRECTORY,
                    messageKey = DTOs.ValidationMessageKey.NOT_A_DIRECTORY
                )
            )
        )
    } else {
        DTOs.ValidationResult.Success
    }
}
