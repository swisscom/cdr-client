package com.swisscom.health.des.cdr.client.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Swisscom_Lifeform_Colour_RGB
import org.jetbrains.compose.resources.painterResource

@Composable
@Preview
fun CdrConfigView() =
    MaterialTheme {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround,
            modifier = Modifier.padding(16.dp),
        ) {
            SwisscomLogo()
            ClientServiceOption()
            ButtonRow()
        }
    }

@Composable
private fun SwisscomLogo() =
    Image(
        painter = painterResource(resource = Res.drawable.Swisscom_Lifeform_Colour_RGB),
        contentDescription = null,
        modifier = Modifier.size(86.dp).padding(16.dp),
    )

@Composable
private fun ClientServiceOption() =
    SettingsMenuLink(
        title = { Text(text = "Enable Client Service") },
        subtitle = { Text(text = "Enable to start synchronizing your documents") },
        modifier = Modifier,
        enabled = true,
        action = {
            // you need to manually import the getter and setter functions for the `by` keyword to work in combination with `remember`
            //import androidx.compose.runtime.getValue
            //import androidx.compose.runtime.setValue
            var checked by remember { mutableStateOf(true) }
            Switch(
                checked = checked,
                onCheckedChange = { checked = it },
            )
        },
        onClick = { },
    )

@Composable
private fun ButtonRow() =
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = Modifier.fillMaxWidth(.75f).padding(16.dp),
    ) {
        CancelButton()
        ApplyButton()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CancelButton() =
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                caretSize = DpSize(16.dp, 8.dp),
                shadowElevation = 16.dp,
                shape = MaterialTheme.shapes.extraSmall,

                ) { Text("Reset form values to currently active configuration") }
        },
        state = rememberTooltipState(
            isPersistent = true, // set to false in order to make the tooltip automatically disappear after a short time
        ),
    ) {
        Button(
            onClick = {},
        ) { Text(text = "Cancel") }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApplyButton() =
    TooltipArea(
        tooltip = {
            Surface(
                modifier = Modifier.shadow(4.dp),
                color = Color(255, 255, 210),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "Save new configuration and restart the client service to pick up the changes",
                    modifier = Modifier.padding(10.dp)
                )
            }
        },
        modifier = Modifier.padding(start = 40.dp),
        delayMillis = 300, // In milliseconds
        tooltipPlacement = TooltipPlacement.CursorPoint(
            alignment = Alignment.TopEnd,
//            offset = if (index % 2 == 0) DpOffset(
//                (-16).dp,
//                0.dp
//            ) else DpOffset.Zero // Tooltip offset
        )
    ) {
        Button(
            onClick = {},
        ) { Text(text = "Apply") }

    }