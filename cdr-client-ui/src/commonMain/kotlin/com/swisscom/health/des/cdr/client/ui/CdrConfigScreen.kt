package com.swisscom.health.des.cdr.client.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Swisscom_Lifeform_Colour_RGB
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_apply
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_cancel
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_cdr_api_host
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_cdr_api_host_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_file_busy_strategy
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_file_busy_strategy_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_service_status
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_close
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_client_service
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_client_service_subtitle
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_broken
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_disabled
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_error
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_offline
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_synchronizing
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_unknown
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val logger = KotlinLogging.logger {}

@Composable
@Preview
internal fun CdrConfigScreen(
    modifier: Modifier = Modifier,
    viewModel: CdrConfigViewModel,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues: PaddingValues ->
        val uiState: CdrConfigUiState by viewModel.uiStateFlow.collectAsStateWithLifecycle()
        val scrollState: ScrollState = rememberScrollState()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = modifier
                .verticalScroll(scrollState)
                .padding(16.dp),
        ) {
            SwisscomLogo(modifier.size(86.dp).padding(16.dp))

            var showAboutDialog by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showAboutDialog = true },
                modifier = modifier
            ) {
                Text("About")
            }
            showAboutDialog(
                show = showAboutDialog,
                onDismiss = { showAboutDialog = false }
            )

            Divider(modifier = modifier)

            // client service status
            Row(modifier = modifier.padding(16.dp)) {
                Text(text = stringResource(Res.string.label_client_service_status))
                Spacer(Modifier.weight(1f))
                Text(text = statusStringResource(uiState.clientServiceStatus))
            }

            Divider(modifier = modifier)

            // client service enabled/disabled option
            OnOffSwitch(
                name = DomainObjects.ConfigurationItem.SYNC_SWITCH,
                modifier = modifier.padding(all = 8.dp).padding(bottom = 16.dp),
                title = stringResource(Res.string.label_enable_client_service),
                subtitle = stringResource(Res.string.label_enable_client_service_subtitle),
                checked = uiState.clientServiceConfig.fileSynchronizationEnabled,
                onValueChange = { viewModel.setFileSync(it) },
            )

            Divider(modifier = modifier)

            // CDR API Host
            var cdrHostValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
            LaunchedEffect(uiState.clientServiceConfig.cdrApi.host) {
                cdrHostValidationResult =
                    remoteViewValidations.validateNotBlank(uiState.clientServiceConfig.cdrApi.host, DomainObjects.ConfigurationItem.CDR_API_HOST)
            }
            ValidatedTextField(
                name = DomainObjects.ConfigurationItem.CDR_API_HOST,
                modifier = modifier.padding(8.dp).fillMaxWidth(),
                validatable = { cdrHostValidationResult },
                label = { Text(text = stringResource(Res.string.label_cdr_api_host)) },
                value = uiState.clientServiceConfig.cdrApi.host,
                placeHolder = { Text(text = stringResource(Res.string.label_cdr_api_host_placeholder)) },
                onValueChange = { viewModel.setCdrApiHost(it) },
            )

            Divider(modifier = modifier)

            // File busy test strategy
            DropDownList(
                name = DomainObjects.ConfigurationItem.FILE_BUSY_TEST_STRATEGY,
                modifier = modifier.padding(8.dp).fillMaxWidth(),
                initiallyExpanded = false,
                options = { DTOs.CdrClientConfig.FileBusyTestStrategy.entries.filter { it != DTOs.CdrClientConfig.FileBusyTestStrategy.ALWAYS_BUSY } },
                label = { Text(text = stringResource(Res.string.label_client_file_busy_strategy)) },
                placeHolder = { Text(text = stringResource(Res.string.label_client_file_busy_strategy_placeholder)) },
                value = uiState.clientServiceConfig.fileBusyTestStrategy.toString(),
                onValueChange = { viewModel.setFileBusyTestStrategy(it) }
            )

            Divider(modifier = modifier)

            ConnectorSettingsGroup(
                modifier = modifier,
                remoteViewValidations = remoteViewValidations,
                viewModel = viewModel,
                uiState = uiState,
            )

            Divider(modifier = modifier)

            // IdP settings
            IdpSettingsGroup(
                modifier = modifier,
                viewModel = viewModel,
                uiState = uiState,
                remoteViewValidations = remoteViewValidations,
            )

            Divider(modifier = modifier)

            ButtonRow(viewModel = viewModel, modifier = modifier.fillMaxWidth(.75f).padding(16.dp))
        }

        uiState.errorMessageKey?.let { errorKey ->
            val errorMessage = errorKey.asString()
            val closeActionLabel = stringResource(Res.string.label_close)
            LaunchedEffect(Unit) {
                runCatching {
                    // we ignore the SnackbarResult; is that a good idea?
                    snackbarHostState.showSnackbar(message = errorMessage, actionLabel = closeActionLabel)
                }.onFailure { logger.error { "Snackbar fail: '${it.toString()}'" } }

                viewModel.clearErrorMessage()
            }
        }

        logger.trace { "CdrConfigScreen has been (re-)composed; uiState: '$uiState'" }
    }
}

@Composable
private fun statusStringResource(status: DTOs.StatusResponse.StatusCode): String =
    when (status) {
        DTOs.StatusResponse.StatusCode.SYNCHRONIZING -> stringResource(Res.string.status_synchronizing)
        DTOs.StatusResponse.StatusCode.DISABLED -> stringResource(Res.string.status_disabled)
        DTOs.StatusResponse.StatusCode.ERROR -> stringResource(Res.string.status_error)
        DTOs.StatusResponse.StatusCode.OFFLINE -> stringResource(Res.string.status_offline)
        DTOs.StatusResponse.StatusCode.BROKEN -> stringResource(Res.string.status_broken)
        DTOs.StatusResponse.StatusCode.UNKNOWN -> stringResource(Res.string.status_unknown)
    }

@Composable
private fun SwisscomLogo(modifier: Modifier) =
    Image(
        painter = painterResource(resource = Res.drawable.Swisscom_Lifeform_Colour_RGB),
        contentDescription = null,
        modifier = modifier,
    ).also {
        logger.trace { "SwisscomLogo has been (re-)composed." }
    }

@Composable
private fun ButtonRow(viewModel: CdrConfigViewModel, modifier: Modifier) =
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = modifier,
    ) {
        CancelButton(onClick = {})
        ApplyButton(onClick = viewModel::applyClientServiceConfiguration)
    }.also {
        logger.trace { "ButtonRow has been (re-)composed." }
    }

@Composable
private fun CancelButton(onClick: () -> Unit) =
    ButtonWithToolTip(
        label = stringResource(Res.string.label_cancel),
//        toolTip = "Reset form values to currently active configuration",
        onClick = onClick,
    ).also { logger.trace { "CancelButton has been (re-)composed." } }

@Composable
private fun ApplyButton(onClick: () -> Unit) =
    ButtonWithToolTip(
        label = stringResource(Res.string.label_apply),
//        toolTip = "Save new configuration and restart the client service to pick up the changes",
        onClick = onClick,
    ).also { logger.trace { "ApplyButton has been (re-)composed." } }
