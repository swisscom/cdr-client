package com.swisscom.health.des.cdr.client.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_advanced_settings
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_file_busy_strategy
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_file_busy_strategy_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_secret_renewal
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_secret_renewal_subtitle
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_idp_settings_client_secret_renewal_timestamp
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_proxy_password
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_proxy_password_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_proxy_url
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_proxy_url_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_proxy_username
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_proxy_username_placeholder
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AdvancedSettingsGroup(
    modifier: Modifier,
    viewModel: CdrConfigViewModel,
    uiState: CdrConfigUiState,
    remoteViewValidations: CdrConfigViewRemoteValidations,
    canEdit: Boolean,
) {
    CollapsibleGroup(
        modifier = modifier,
        title = stringResource(Res.string.label_advanced_settings),
        initiallyExpanded = false,
    ) { _ ->
        // Proxy URL
        AsyncValidatedTextField(
            modifier = modifier.padding(horizontal = 8.dp, vertical = 0.dp).fillMaxWidth(),
            name = DomainObjects.ConfigurationItem.PROXY_URL,
            value = uiState.clientServiceConfig.proxyConfig.url,
            onValueChange = { if (canEdit) viewModel.setProxyUrl(it) },
            label = { Text(text = stringResource(Res.string.label_proxy_url)) },
            placeHolder = { Text(text = stringResource(Res.string.label_proxy_url_placeholder)) },
            asyncValidation = suspend { remoteViewValidations.validateProxyUrl(uiState.clientServiceConfig.proxyConfig.url) },
            revalidationKey = uiState.clientServiceConfig.proxyConfig.url,
            enabled = canEdit,
        )

        // Proxy Username
        ValidatedTextField(
            name = DomainObjects.ConfigurationItem.PROXY_USERNAME,
            modifier = modifier.padding(horizontal = 8.dp, vertical = 0.dp).fillMaxWidth(),
            validatable = { DTOs.ValidationResult.Success },
            label = { Text(text = stringResource(Res.string.label_proxy_username)) },
            value = uiState.clientServiceConfig.proxyConfig.username,
            placeHolder = { Text(text = stringResource(Res.string.label_proxy_username_placeholder)) },
            onValueChange = { if (canEdit) viewModel.setProxyUsername(it) },
            enabled = canEdit,
        )

        // Proxy Password
        ValidatedTextField(
            name = DomainObjects.ConfigurationItem.PROXY_PASSWORD,
            modifier = modifier.padding(horizontal = 8.dp, vertical = 0.dp).fillMaxWidth(),
            validatable = { DTOs.ValidationResult.Success },
            label = { Text(text = stringResource(Res.string.label_proxy_password)) },
            value = uiState.clientServiceConfig.proxyConfig.password,
            placeHolder = { Text(text = stringResource(Res.string.label_proxy_password_placeholder)) },
            onValueChange = { if (canEdit) viewModel.setProxyPassword(it) },
            enabled = canEdit,
        )

        Divider(modifier = modifier)

        // Client secret renewal
        OnOffSwitch(
            name = DomainObjects.ConfigurationItem.IDP_CLIENT_SECRET_RENWAL,
            modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
            title = stringResource(Res.string.label_client_idp_settings_client_secret_renewal),
            subtitle = stringResource(Res.string.label_client_idp_settings_client_secret_renewal_subtitle),
            checked = uiState.clientServiceConfig.idpCredentials.renewCredential,
            onValueChange = { if (canEdit) viewModel.setIdpRenewClientSecret(it) },
            enabled = canEdit,
        )

        // Last credential renewal time
        DisabledTextField(
            name = DomainObjects.ConfigurationItem.IDP_CLIENT_SECRET_RENWAL_TIME,
            modifier = modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            label = { Text(text = stringResource(Res.string.label_client_idp_settings_client_secret_renewal_timestamp)) },
            value = uiState.clientServiceConfig.idpCredentials.lastCredentialRenewalTime.toString(),
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
            onValueChange = { if (canEdit) viewModel.setFileBusyTestStrategy(it) },
            validatable = { DTOs.ValidationResult.Success },
            enabled = canEdit,
        )

    }
}
