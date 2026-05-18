package com.swisscom.health.des.cdr.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.TopAppBar
import androidx.compose.material3.BottomAppBar
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Swisscom_Lifeform_RGB_Colour_icon
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_client_internal_error
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_apply
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_cdr_api_host
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_cdr_api_host_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_service_status
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_close
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_client_service
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_enable_client_service_subtitle
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_reset
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_authn_communication_error
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_authn_denied
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_authn_unknown_error
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_broken
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_disabled
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_error
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_offline
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_synchronizing
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_unknown
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val logger = KotlinLogging.logger {}

@Composable
internal fun CdrConfigScreen(
    modifier: Modifier = Modifier,
    viewModel: CdrConfigViewModel,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val uiState: CdrConfigUiState by viewModel.uiStateFlow.collectAsStateWithLifecycle()

    var initialConfigLoaded: Boolean by remember { mutableStateOf(false) }
    var canEdit: Boolean by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.clientServiceConfig, uiState.clientServiceStatus) {
        initialConfigLoaded = uiState.clientServiceConfig !== DTOs.CdrClientConfig.EMPTY
        canEdit = initialConfigLoaded && uiState.clientServiceStatus.isOnlineCategory
    }

    Scaffold(
        modifier = modifier,
        topBar = { StatusTopBar(modifier = modifier, uiState = uiState) },
        bottomBar = { ButtonsBottomAppBar(modifier = modifier, viewModel = viewModel, enabled = canEdit) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colors.surface
    ) { paddingValues: PaddingValues ->
        val scrollState: ScrollState = rememberScrollState()


        val horizontalPaddingModifier = modifier.fillMaxWidth().padding(horizontal = 8.dp)
        val verticalPaddingModifier = modifier.padding(vertical = 16.dp)
        val topPaddingModifier = modifier.padding(top = 16.dp)

        val columnModifier = modifier.verticalScroll(scrollState)
            .padding(paddingValues) // contains calculated padding to account for top and bottom bars
            .padding(16.dp) // additional padding for the content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = columnModifier
        ) {
            SwisscomLogo(modifier.size(86.dp).padding(16.dp))

            var showAboutDialog by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showAboutDialog = true },
                modifier = modifier
            ) {
                Text("About")
            }
            if (showAboutDialog) {
                AboutDialog(
                    modifier = modifier,
                    onDismissRequest = { showAboutDialog = false }
                )
            }

            // File monitoring status warnings
            if (uiState.fileMonitoringStatus.errorFileCount > 0 || uiState.fileMonitoringStatus.oldTempFileCount > 0) {
                FileMonitoringWarningBanner(
                    modifier = horizontalPaddingModifier,
                    fileMonitoringStatus = uiState.fileMonitoringStatus
                )
            }

            Divider(modifier = verticalPaddingModifier)

            // client service enabled/disabled option
            OnOffSwitch(
                name = DomainObjects.ConfigurationItem.SYNC_SWITCH,
                modifier = horizontalPaddingModifier,
                title = stringResource(Res.string.label_enable_client_service),
                subtitle = stringResource(Res.string.label_enable_client_service_subtitle),
                checked = uiState.clientServiceConfig.fileSynchronizationEnabled,
                onValueChange = { if (canEdit) viewModel.setFileSync(it) },
                enabled = canEdit,
            )

            Divider(modifier = verticalPaddingModifier)

            // CDR API Host
            var cdrHostValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
            LaunchedEffect(uiState.clientServiceConfig.cdrApi) {
                cdrHostValidationResult =
                    // if the service configuration has an endpoint with a host set, that is not in the list of known hosts, then
                    // the factory method returns UNKNOWN, which has a blank host attribute value
                    remoteViewValidations.validateNotBlank(uiState.clientServiceConfig.cdrApi.host, DomainObjects.ConfigurationItem.CDR_API_HOST)
            }
            DropDownList(
                name = DomainObjects.ConfigurationItem.CDR_API_HOST,
                modifier = horizontalPaddingModifier,
                initiallyExpanded = false,
                options = { DomainObjects.ApiEndpoint.entries.filter { it == DomainObjects.ApiEndpoint.PRODUCTION || it == DomainObjects.ApiEndpoint.STAGING } },
                label = { Text(text = stringResource(Res.string.label_cdr_api_host)) },
                placeHolder = { Text(text = stringResource(Res.string.label_cdr_api_host_placeholder)) },
                value = uiState.clientServiceConfig.cdrApi.name,
                onValueChange = {
                    if (canEdit) {
                        DomainObjects.ApiEndpoint.valueOf(it).also { apiEndpoint ->
                            viewModel.setCdrApiEndpoint(apiEndpoint)
                            when (apiEndpoint) {
                                DomainObjects.ApiEndpoint.PRODUCTION -> {
                                    viewModel.setIdpCredentialsScope(DomainObjects.OAuthScope.PRODUCTION)
                                    viewModel.setIdpTenantId(DomainObjects.TenantId.PRODUCTION)
                                }

                                DomainObjects.ApiEndpoint.STAGING -> {
                                    viewModel.setIdpCredentialsScope(DomainObjects.OAuthScope.STAGING)
                                    viewModel.setIdpTenantId(DomainObjects.TenantId.STAGING)
                                }

                                else -> {
                                    viewModel.reportError(Res.string.error_client_internal_error, "Unknown API endpoint '$apiEndpoint'")
                                }
                            }
                        }
                    }
                },
                validatable = { cdrHostValidationResult },
                enabled = canEdit,
            )

            Divider(modifier = topPaddingModifier)

            // FIXME: The ConnectorList, IdpSettingsGroup, and AdvancedSettingsGroup composables set their own padding -> DON'T!
            //  Only manage internal padding, but not the padding of the container itself.

            ConnectorList(
                modifier = modifier,
                remoteViewValidations = remoteViewValidations,
                viewModel = viewModel,
                uiState = uiState,
                canEdit = canEdit,
                initialConfigLoaded = initialConfigLoaded,
            )

            Divider(modifier = modifier)

            IdpSettingsGroup(
                modifier = modifier,
                viewModel = viewModel,
                uiState = uiState,
                remoteViewValidations = remoteViewValidations,
                canEdit = canEdit,
                initialConfigLoaded = initialConfigLoaded,
            )

            Divider(modifier = modifier)

            AdvancedSettingsGroup(
                modifier = modifier,
                viewModel = viewModel,
                uiState = uiState,
                remoteViewValidations = remoteViewValidations,
                canEdit = canEdit,
            )
        }

        uiState.errorMessageKey?.let { errorKey ->
            val errorMessage = errorKey.asString()
            val closeActionLabel = stringResource(Res.string.label_close)
            LaunchedEffect(Unit) {
                runCatching {
                    // we ignore the SnackbarResult; is that a good idea?
                    snackbarHostState.showSnackbar(message = errorMessage, actionLabel = closeActionLabel)
                }.onFailure { t: Throwable ->
                    when (t) {
                        is CancellationException -> logger.debug { "Snackbar host encountered cancellation exception; are we being shut down?" }
                        else -> logger.error { "Snackbar fail: '${t}'" }
                    }
                }

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
        DTOs.StatusResponse.StatusCode.AUTHN_COMMUNICATION_ERROR -> stringResource(Res.string.status_authn_communication_error)
        DTOs.StatusResponse.StatusCode.AUTHN_UNKNOWN_ERROR -> stringResource(Res.string.status_authn_unknown_error)
        DTOs.StatusResponse.StatusCode.AUTHN_DENIED -> stringResource(Res.string.status_authn_denied)
        DTOs.StatusResponse.StatusCode.UNKNOWN -> stringResource(Res.string.status_unknown)
    }

@Composable
private fun SwisscomLogo(modifier: Modifier) =
    Image(
        painter = painterResource(resource = Res.drawable.Swisscom_Lifeform_RGB_Colour_icon),
        contentDescription = null,
        modifier = modifier,
    ).also {
        logger.trace { "SwisscomLogo has been (re-)composed." }
    }

@Composable
private fun StatusTopBar(
    modifier: Modifier = Modifier,
    uiState: CdrConfigUiState,
) {
    TopAppBar(
        modifier = modifier,
        backgroundColor = MaterialTheme.colors.surface
    ) {
        Row(modifier = modifier.padding(16.dp)) {
            Text(text = stringResource(Res.string.label_client_service_status))
            Spacer(Modifier.weight(1f))
            Text(text = statusStringResource(uiState.clientServiceStatus))
        }
    }
}


@Composable
private fun ButtonsBottomAppBar(
    modifier: Modifier,
    viewModel: CdrConfigViewModel,
    enabled: Boolean,
) {
    BottomAppBar(
        modifier = modifier,
        containerColor = MaterialTheme.colors.surface,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    ) {
        Column(
            modifier = modifier.fillMaxHeight(),
        ) {
            Divider(
                modifier = modifier.shadow(
                    elevation = 2.dp,
                    ambientColor = Color.Black,
                    spotColor = Color.LightGray
                )
            )
            Row(
                modifier = modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier.weight(0.5F))
                ResetButton(enabled = enabled, onClick = viewModel::queryClientServiceConfiguration)
                Spacer(modifier.width(30.dp))
                ApplyButton(enabled = enabled, onClick = viewModel::applyClientServiceConfiguration)
                Spacer(modifier.weight(0.5F))
            }
        }
    }
}

@Composable
private fun ResetButton(enabled: Boolean, onClick: () -> Unit) =
    ButtonWithToolTip(
        label = stringResource(Res.string.label_reset),
//        toolTip = "Reset form values to currently active configuration",
        onClick = onClick,
        enabled = enabled,
    ).also { logger.trace { "ResetButton has been (re-)composed." } }

@Composable
private fun ApplyButton(enabled: Boolean, onClick: () -> Unit) =
    ButtonWithToolTip(
        label = stringResource(Res.string.label_apply),
//        toolTip = "Save new configuration and restart the client service to pick up the changes",
        onClick = onClick,
        enabled = enabled,
    ).also { logger.trace { "ApplyButton has been (re-)composed." } }
