package com.swisscom.health.des.cdr.client.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.swisscom.health.des.cdr.client.ui.CdrConfigViewModel.Companion.STATUS_CHECK_DELAY
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Swisscom_Lifeform_Colour_RGB_icon
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.app_name
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_status
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_exit
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_unknown
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
fun main() = application {
    var isWindowVisible: Boolean by remember { mutableStateOf(true) }
    val cdrConfigViewModel: CdrConfigViewModel = remember { CdrConfigViewModel(cdrClientApiClient = CdrClientApiClient()) }

    // I have found no way to push this down into the CdrConfigScreen composable
    LaunchedEffect(isWindowVisible) {
        while (isWindowVisible) {
            cdrConfigViewModel.updateClientServiceStatus()
            delay(STATUS_CHECK_DELAY)
        }
    }

    Window(
        onCloseRequest = { isWindowVisible = false },
        visible = isWindowVisible,
        title = stringResource(Res.string.app_name),
        icon = painterResource(Res.drawable.Swisscom_Lifeform_Colour_RGB_icon), // not rendered on Ubuntu-Linux
    ) {
        CdrConfigScreen(viewModel = cdrConfigViewModel)
    }

    // Swing tray looks ugly (on Linux, others unknown at the time of writing), but it works;
    // notifications sent via tray state pop up but are horribly ugly
    // Do not use `com.dorkbox:SystemTray`, it crashes the desktop session on Ubuntu 24.04.
    // TODO: give https://github.com/kdroidFilter/ComposeNativeTray a try
    // FIXME: client service status must be updated from model! But cannot figure out how to update the model on demand on opening the tray menu?
    //   We need on-demand update because status polling is suspended while the main window is closed.
    Tray(
        icon = painterResource(Res.drawable.Swisscom_Lifeform_Colour_RGB_icon), // clipped, background is not transparent
        tooltip = stringResource(Res.string.app_name),
        onAction = {
            isWindowVisible = true
        },
        menu = {
            Item(
                text = "${stringResource(Res.string.label_client_status)}: ${stringResource(Res.string.status_unknown)}",
                enabled = false,
                onClick = {}
            )
            Item(text = stringResource(Res.string.label_exit), onClick = ::exitApplication)
        },
    )

}
