package com.swisscom.health.des.cdr.client.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.kdroid.composetray.tray.api.Tray
import com.swisscom.health.des.cdr.client.ui.CdrConfigViewModel.Companion.STATUS_CHECK_DELAY
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Swisscom_Lifeform_Colour_RGB_icon
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.app_name
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_status
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_exit
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_open_application_window
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_unknown
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
fun main() = application {
    var isWindowVisible: Boolean by remember { mutableStateOf(true) }
    val cdrConfigViewModel: CdrConfigViewModel = remember { CdrConfigViewModel(cdrClientApiClient = CdrClientApiClient()) }

    // I have found no way to push this down into the CdrConfigScreen composable;
    // the client status query runs in a different coroutine scope, so it won't be canceled automatically when the main window is closed
    var statusQueryJob: Job by remember { mutableStateOf(Job().apply { complete() }) }
    LaunchedEffect(isWindowVisible) {
        statusQueryJob.cancelAndJoin()
        while (isWindowVisible) {
            statusQueryJob = cdrConfigViewModel.queryClientServiceStatus(CdrClientApiClient.RetryStrategy.EXPONENTIAL)
            statusQueryJob.join()
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

    // I am not using JetBrains' default Tray() implementation:
    // The AWT-based tray looks ugly (on Linux, others unknown at the time of writing), but it works;
    // notifications sent via tray state pop up but are horribly ugly
    // Do not use `com.dorkbox:SystemTray`, it crashes the desktop session on Ubuntu 24.04.!
    // We use https://github.com/kdroidFilter/ComposeNativeTray, appears to work nicely (on Linux, others unknown at the time of writing)
    // FIXME: client service status must be updated from model! But cannot figure out how to update the model on demand on opening the tray menu?
    //   We need on-demand update because status polling is suspended while the main window is closed.
    CdrSystemTray(
        primaryAction = { isWindowVisible = true }
    )

}

@Composable
private fun ApplicationScope.CdrSystemTray(
    toolTip: String = stringResource(Res.string.app_name),
    labelPrimaryAction: String = stringResource(Res.string.label_open_application_window),
    labelStatusItem: String = "${stringResource(Res.string.label_client_status)}: ${stringResource(Res.string.status_unknown)}",
    labelExit: String = stringResource(Res.string.label_exit),
    primaryAction: (() -> Unit)? = null,
) =
    Tray(
        iconContent = {
            Icon(
                painter = painterResource(Res.drawable.Swisscom_Lifeform_Colour_RGB_icon),
                contentDescription = "",
                tint = Color.Unspecified,
                modifier = Modifier.fillMaxSize()
                // neither of these options works (on linux, at least); intention was to update the client service status when the tray icon is clicked
//                    .onClick(onClick = { logger.info { "updating client service status because tray icon was clicked" } })
//                    .clickable(onClick = { logger.info { "updating client service status because tray icon was clicked" } })
            )
        },
        tooltip = toolTip,
        primaryAction = primaryAction,
        primaryActionLinuxLabel = labelPrimaryAction
    ) {
        Item(
            label = labelStatusItem,
            isEnabled = false,
            onClick = {}
        )
        Item(
            label = labelExit,
            onClick = ::exitApplication
        )
    }
