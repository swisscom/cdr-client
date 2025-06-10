package com.swisscom.health.des.cdr.client.ui

import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal class StringResourceWithArgs(
    val resourceId: StringResource,
    vararg val args: Any,
) {
    @Composable
    fun asString(): String {
        return stringResource(
            resource = resourceId,
            formatArgs = (args)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ButtonWithToolTip(
    label: String,
    toolTip: String = "",
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
            Button(
                onClick = onClick,
            ) { Text(text = "Apply") }
        }
    } else {
        Button(
            onClick = onClick,
        ) {
            Text(text = label)
        }
    }