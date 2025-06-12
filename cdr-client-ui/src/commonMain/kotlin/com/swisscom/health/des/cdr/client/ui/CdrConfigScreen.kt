package com.swisscom.health.des.cdr.client.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Divider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Swisscom_Lifeform_Colour_RGB
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_apply
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_cancel
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_service_status
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_client_service
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_client_service_subtitle
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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        val uiState by viewModel.uiStateFlow.collectAsStateWithLifecycle()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround,
            modifier = modifier.padding(16.dp),
        ) {
            SwisscomLogo(modifier.size(86.dp).padding(16.dp))
            Divider(modifier = modifier)
            Row(modifier = modifier.padding(16.dp)) {
                Text(text = stringResource(Res.string.label_client_service_status))
                Spacer(Modifier.weight(1f))
                Text(text = statusStringResource(uiState.clientServiceStatus))
            }
            Divider(modifier = modifier)
            ClientServiceOption(modifier, viewModel, uiState.clientServiceConfig.fileSynchronizationEnabled)
            ButtonRow(viewModel = viewModel, modifier = modifier.fillMaxWidth(.75f).padding(16.dp))
        }

        uiState.errorMessageKey?.let { errorKey ->
            val errorMessage = errorKey.asString()
            LaunchedEffect(Unit) {
                runCatching {
                    // we ignore the SnackbarResult; is that a good idea?
                    snackbarHostState.showSnackbar(message = errorMessage)
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
private fun ClientServiceOption(modifier: Modifier, viewModel: CdrConfigViewModel, checked: Boolean) =
    SettingsMenuLink(
        title = { Text(text = stringResource(Res.string.label_enable_client_service)) },
        subtitle = { Text(text = stringResource(Res.string.label_enable_client_service_subtitle)) },
        modifier = modifier,
        enabled = true,
        action = {
            Switch(
                checked = checked,
                onCheckedChange = { viewModel.fileSynchronizationEnabled = it; logger.debug {  "file sync enabled: $it" } },
            )
        },
        onClick = { },
    ).also {
        logger.trace { "ClientServiceOption has been (re-)composed." }
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
    ).also {  logger.trace { "ApplyButton has been (re-)composed." } }
