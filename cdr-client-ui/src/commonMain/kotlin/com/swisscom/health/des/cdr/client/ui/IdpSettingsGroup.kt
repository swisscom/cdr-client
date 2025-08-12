package com.swisscom.health.des.cdr.client.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
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
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_id
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_id_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_secret
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_secret_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_secret_renewal
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_secret_renewal_subtitle
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_secret_renewal_timestamp
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_tenant_id
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_tenant_id_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.message_loading_initial_config
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.compose.resources.stringResource

private val logger = KotlinLogging.logger { }

/**
 * A composable function that displays a group of settings related to Identity Provider (IdP) configuration.
 */
@Composable
internal fun IdpSettingsGroup(
    modifier: Modifier,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    viewModel: CdrConfigViewModel,
    uiState: CdrConfigUiState,
    initialConfigLoaded: Boolean,
    canEdit: Boolean,
) {
    CollapsibleGroup(
        modifier = modifier,
        title = stringResource(Res.string.label_client_idp_settings),
        initiallyExpanded = false,
    ) { _ ->
        if (!initialConfigLoaded) {
            Divider(modifier = modifier.padding(bottom = 8.dp))
            Text(text = stringResource(Res.string.message_loading_initial_config))
            Divider(modifier = modifier.padding(bottom = 8.dp))
        } else {
            // Client secret renewal
            OnOffSwitch(
                name = DomainObjects.ConfigurationItem.IDP_CLIENT_SECRET_RENWAL,
                modifier = modifier.padding(bottom = 16.dp),
                title = stringResource(Res.string.label_client_idp_settings_client_secret_renewal),
                subtitle = stringResource(Res.string.label_client_idp_settings_client_secret_renewal_subtitle),
                checked = uiState.clientServiceConfig.idpCredentials.renewCredential,
                onValueChange = { if (canEdit) viewModel.setIdpRenewClientSecret(it) },
                enabled = canEdit,
            )

            // Tenant ID
            var tenantIdValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
            LaunchedEffect(uiState.clientServiceConfig.idpCredentials.tenantId) {
                tenantIdValidationResult =
                    remoteViewValidations.validateNotBlank(uiState.clientServiceConfig.idpCredentials.tenantId, DomainObjects.ConfigurationItem.IDP_TENANT_ID)
            }
            ValidatedTextField(
                name = DomainObjects.ConfigurationItem.IDP_TENANT_ID,
                modifier = modifier.fillMaxWidth(),
                validatable = { tenantIdValidationResult },
                label = { Text(text = stringResource(Res.string.label_client_idp_settings_tenant_id)) },
                placeHolder = { Text(text = stringResource(Res.string.label_client_idp_settings_tenant_id_placeholder)) },
                value = uiState.clientServiceConfig.idpCredentials.tenantId,
                onValueChange = { if (canEdit) viewModel.setIdpTenantId(it) },
                enabled = canEdit,
            )

            // Client ID
            var clientIdValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
            LaunchedEffect(uiState.clientServiceConfig.idpCredentials.clientId) {
                clientIdValidationResult =
                    remoteViewValidations.validateNotBlank(uiState.clientServiceConfig.idpCredentials.clientId, DomainObjects.ConfigurationItem.IDP_CLIENT_ID)
            }
            ValidatedTextField(
                name = DomainObjects.ConfigurationItem.IDP_CLIENT_ID,
                modifier = modifier.fillMaxWidth(),
                validatable = { clientIdValidationResult },
                label = { Text(text = stringResource(Res.string.label_client_idp_settings_client_id)) },
                value = uiState.clientServiceConfig.idpCredentials.clientId,
                placeHolder = { Text(text = stringResource(Res.string.label_client_idp_settings_client_id_placeholder)) },
                onValueChange = { if (canEdit) viewModel.setIdpClientId(it) },
                enabled = canEdit,
            )

            // Client password
            var clientPasswordValidationResult: DTOs.ValidationResult by remember { mutableStateOf(DTOs.ValidationResult.Success) }
            LaunchedEffect(uiState.clientServiceConfig.idpCredentials.clientSecret) {
                clientPasswordValidationResult =
                    remoteViewValidations.validateNotBlank(
                        uiState.clientServiceConfig.idpCredentials.clientSecret,
                        DomainObjects.ConfigurationItem.IDP_CLIENT_PASSWORD
                    )
            }
            ValidatedTextField(
                name = DomainObjects.ConfigurationItem.IDP_CLIENT_PASSWORD,
                modifier = modifier.fillMaxWidth(),
                validatable = { clientPasswordValidationResult },
                label = { Text(text = stringResource(Res.string.label_client_idp_settings_client_secret)) },
                value = uiState.clientServiceConfig.idpCredentials.clientSecret,
                placeHolder = { Text(text = stringResource(Res.string.label_client_idp_settings_client_secret_placeholder)) },
                onValueChange = { if (canEdit) viewModel.setIdpClientPassword(it) },
                enabled = canEdit,
            )

            // Last credential renewal time
            DisabledTextField(
                name = DomainObjects.ConfigurationItem.IDP_CLIENT_SECRET_RENWAL_TIME,
                modifier = modifier.fillMaxWidth(),
                label = { Text(text = stringResource(Res.string.label_client_idp_settings_client_secret_renewal_timestamp)) },
                value = uiState.clientServiceConfig.idpCredentials.lastCredentialRenewalTime.toString(),
            )

            logger.trace { "IdpSettingsGroup has been (re-)composed; uiState: '$uiState'" }
        }
    }
}
