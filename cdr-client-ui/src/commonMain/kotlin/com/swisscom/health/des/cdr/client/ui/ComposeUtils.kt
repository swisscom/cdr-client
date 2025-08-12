package com.swisscom.health.des.cdr.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.arrow_drop_down_24dp_000000_FILL0_wght400_GRAD0_opsz24
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.arrow_drop_up_24dp_000000_FILL0_wght400_GRAD0_opsz24
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_directory_not_found
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_duplicate_mode
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_illegal_mode
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_is_placeholder
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_no_connector
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_not_a_directory
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_not_read_writable
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_overlaps_with_download_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_overlaps_with_other_upload_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_overlaps_with_upload_dir
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_test_timeout_too_long
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_value_is_mandatory
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

private val logger = KotlinLogging.logger { }

fun interface Validatable {
    fun validate(): DTOs.ValidationResult
}

internal class StringResourceWithArgs(
    val resourceId: StringResource,
    vararg val formatArgs: Any,
) {
    @Composable
    fun asString(): String {
        return stringResource(
            resource = resourceId,
            formatArgs = (formatArgs)
        )
    }
}

internal val DTOs.ValidationMessageKey.stringResource: StringResource
    get() =
        when (this) {
            DTOs.ValidationMessageKey.NOT_A_DIRECTORY -> Res.string.error_not_a_directory
            DTOs.ValidationMessageKey.DIRECTORY_NOT_FOUND -> Res.string.error_directory_not_found
            DTOs.ValidationMessageKey.VALUE_IS_BLANK -> Res.string.error_value_is_mandatory
            DTOs.ValidationMessageKey.NOT_READ_WRITABLE -> Res.string.error_not_read_writable
            DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_SOURCE_DIRS -> Res.string.error_overlaps_with_upload_dir
            DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_TARGET_DIRS -> Res.string.error_overlaps_with_download_dir
            DTOs.ValidationMessageKey.TARGET_DIR_OVERLAPS_SOURCE_DIRS -> Res.string.error_overlaps_with_upload_dir
            DTOs.ValidationMessageKey.DUPLICATE_SOURCE_DIRS -> Res.string.error_overlaps_with_other_upload_dir
            DTOs.ValidationMessageKey.DUPLICATE_MODE -> Res.string.error_duplicate_mode
            DTOs.ValidationMessageKey.FILE_BUSY_TEST_TIMEOUT_TOO_LONG -> Res.string.error_test_timeout_too_long
            DTOs.ValidationMessageKey.NO_CONNECTOR_CONFIGURED -> Res.string.error_no_connector
            DTOs.ValidationMessageKey.VALUE_IS_PLACEHOLDER -> Res.string.error_is_placeholder
            DTOs.ValidationMessageKey.ILLEGAL_MODE -> Res.string.error_illegal_mode
        }

@Composable
internal fun NamedSectionDivider(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().then(modifier),
        style = style,
        fontWeight = fontWeight,
        textAlign = TextAlign.Start,
        maxLines = 1,
    )
    Divider(modifier = modifier.padding(bottom = 8.dp))
}

@Composable
internal fun OnOffSwitch(
    name: DomainObjects.ConfigurationItem,
    modifier: Modifier,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onValueChange: (Boolean) -> Unit,
) {
    // whole line is clickable, click changes switch state
//    SettingsSwitch(
//        title = { Text(text = stringResource(Res.string.label_enable_client_service)) },
//        subtitle = { Text(text = stringResource(Res.string.label_enable_client_service_subtitle)) },
//        state = checked,
//        modifier =  modifier,
//        enabled = true,
//        onCheckedChange = { viewModel.fileSynchronizationEnabled = it; logger.debug { "file sync enabled: $it" } },
//    ).also {
//        logger.trace { "ClientServiceOption has been (re-)composed." }
//    }
    // Only the switch is clickable, click changes switch state
    // TODO: I am sure there is a less redundant way to set just the container color by using `LocalSettingsTileColors`, but how?
    val colors = SettingsTileDefaults.colors().run {
        SettingsTileColors(
            // copied from com.swisscom.health.des.cdr.client.ui.ComposeUtilsKt.CollapsibleGroup
            containerColor = containerColor,
            titleColor = titleColor,
            iconColor = iconColor,
            subtitleColor = subtitleColor,
            actionColor = actionColor,
            disabledTitleColor = disabledTitleColor,
            disabledIconColor = disabledIconColor,
            disabledSubtitleColor = disabledSubtitleColor,
            disabledActionColor = disabledActionColor,
        )
    }
    SettingsMenuLink(
        title = { Text(text = title) },
        subtitle = { Text(text = subtitle) },
        modifier = modifier.border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = OutlinedTextFieldDefaults.shape),
        enabled = enabled,
        action = {
            Switch(
                checked = checked,
                onCheckedChange = onValueChange,
            )
        },
        onClick = { },
        colors = colors
    ).also {
        logger.trace { "on/off switch has been (re-)composed - field '$name'" }
    }
}

@OptIn(ExperimentalContracts::class)
private fun DTOs.ValidationResult.isError(): Boolean {
    contract {
        returns(true) implies (this@isError is DTOs.ValidationResult.Failure)
    }
    return this is DTOs.ValidationResult.Failure
}

private val DTOs.ValidationResult.message: @Composable (() -> Unit)
    get() =
        if (this.isError()) {
            { Text(text = stringResource(this.validationDetails.first().messageKey.stringResource)) }
        } else {
            { Text(text = EMPTY_STRING) } // reserve the screen estate for the supporting text
        }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DropDownList(
    enabled: Boolean,
    name: DomainObjects.ConfigurationItem,
    modifier: Modifier,
    initiallyExpanded: Boolean = false,
    validatable: Validatable,
    options: @Composable () -> Iterable<*>,
    label: @Composable (() -> Unit)? = null,
    placeHolder: @Composable (() -> Unit)? = null,
    value: String = EMPTY_STRING,
    onValueChange: (String) -> Unit,
) {
    // https://composables.com/material3/exposeddropdownmenubox
    var isExpanded: Boolean by remember { mutableStateOf(initiallyExpanded) }
    ExposedDropdownMenuBox(
        expanded = isExpanded,
        onExpandedChange = { isExpanded = it }
    ) {
        val validationResult = validatable.validate()

        OutlinedTextField(
            readOnly = true,
            modifier = modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
            enabled = enabled,
            value = value,
            onValueChange = { },
            label = label,
            isError = validationResult.isError(),
            singleLine = true,
            placeholder = placeHolder,
//            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = isExpanded,
                    modifier = Modifier.border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = OutlinedTextFieldDefaults.shape),
                )
            },
            supportingText = validationResult.message,
        ).also {
            logger.trace { "drop-down selection has been (re-)composed - field '$name'" }
        }

        ExposedDropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false }
        ) {
            options()
                .map { it.toString() }
                .forEach { optionText ->
                    DropdownMenuItem(
                        text = { Text(text = optionText) },
                        onClick = {
                            onValueChange(optionText)
                            isExpanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
//                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                }
        }
    }
}

@Composable
internal fun CollapsibleGroup(
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    modifier: Modifier,
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable (containerColor: Color) -> Unit,
) {
    var isExpanded: Boolean by remember { mutableStateOf(initiallyExpanded) }
    val icon =
        if (isExpanded)
            Res.drawable.arrow_drop_up_24dp_000000_FILL0_wght400_GRAD0_opsz24
        else
            Res.drawable.arrow_drop_down_24dp_000000_FILL0_wght400_GRAD0_opsz24

    Row(modifier = modifier.padding(16.dp)) {
        Text(text = title, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Icon(
            painter = painterResource(icon),
            modifier = Modifier
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = OutlinedTextFieldDefaults.shape)
                .clickable { isExpanded = !isExpanded },
            contentDescription = null,
        )
    }
    AnimatedVisibility(
        modifier = modifier
            .offset(y = (-8).dp)
            .background(containerColor)
            .fillMaxWidth(),
        visible = isExpanded,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            content(containerColor)
        }
    }
}

@Composable
internal fun ValidatedTextField(
    name: DomainObjects.ConfigurationItem,
    modifier: Modifier,
    validatable: Validatable,
    label: @Composable (() -> Unit)? = null,
    placeHolder: @Composable (() -> Unit)? = null,
    value: String? = null,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val validationResult = validatable.validate()

    OutlinedTextField(
        modifier = modifier,
        enabled = enabled,
        value = value ?: EMPTY_STRING,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeHolder,
        isError = validationResult.isError(),
        singleLine = true,
        supportingText = validationResult.message,
    ).also {
        logger.trace { "text field has been (re-)composed - field '$name'" }
    }
}

@Composable
internal fun DisabledTextField(
    name: DomainObjects.ConfigurationItem,
    modifier: Modifier,
    label: @Composable (() -> Unit)? = null,
    placeHolder: @Composable (() -> Unit)? = null,
    value: String = EMPTY_STRING,
) {
    OutlinedTextField(
        modifier = modifier,
        enabled = false,
        value = value,
        onValueChange = {},
        label = label,
        placeholder = placeHolder,
        singleLine = true,
    ).also {
        logger.trace { "text field has been (re-)composed - field '$name'" }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ButtonWithToolTip(
    label: String,
    toolTip: String = EMPTY_STRING,
    enabled: Boolean,
    onClick: () -> Unit,
) =
    if (toolTip.isNotBlank()) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip(
                    caretSize = DpSize(16.dp, 8.dp),
                    shadowElevation = 16.dp,
                    shape = MaterialTheme.shapes.extraSmall,

                    ) { Text(toolTip) }
            },
            state = rememberTooltipState(
                isPersistent = true, // set as `false` to make the tooltip automatically disappear after a short time
            ),
        ) {
            ElevatedButton(
                enabled = enabled,
                onClick = onClick,
            ) { Text(text = label) }
        }
    } else {
        ElevatedButton(
            enabled = enabled,
            onClick = onClick,
        ) {
            Text(text = label)
        }
    }
