package com.swisscom.health.des.cdr.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Swisscom_Lifeform_Colour_RGB_icon
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.app_name
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_status
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_exit
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_unknown
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime


@OptIn(ExperimentalTime::class)
fun main() = application {
    var isWindowVisible: Boolean by remember { mutableStateOf(true) }

    Window(
        onCloseRequest = { isWindowVisible = false },
        visible = isWindowVisible,
        title = stringResource(Res.string.app_name),
        icon = painterResource(Res.drawable.Swisscom_Lifeform_Colour_RGB_icon), // not rendered on Ubuntu-Linux
    ) {
        CdrConfigView()
    }

    // Swing tray looks ugly (on Linux, others unknown at the time of writing), but it works;
    // notifications sent vie tray state pop up, but are horribly ugly
    Tray(
        icon = painterResource(Res.drawable.Swisscom_Lifeform_Colour_RGB_icon), // clipped, background not transparent
        tooltip = stringResource(Res.string.app_name),
        onAction = {
            isWindowVisible = true
        },
        menu = {
            Item(
                // TODO: status must be updated from model!
                text = "${stringResource(Res.string.label_client_status)}: ${stringResource(Res.string.status_unknown)}",
                enabled = false,
                onClick = {}
            )
            Item(text = stringResource(Res.string.label_exit), onClick = ::exitApplication)
        },
    )

    // looks pretty, but crashes; the dorkbox/system tray library (com.dorkbox:SystemTray:4.4) fixes the ugly
    // looks of the tray icon and the menu; unfortunately, it crashes my desktop session on Ubuntu 24.04.
//    val systemTray: SystemTray? = SystemTray.get()
//    if (systemTray == null) {
//        throw RuntimeException("Unable to load SystemTray!")
//    }
//
//
//    systemTray.setImage("/home/taastrad/work/git/des/cdr-client/cdr-client-ui/src/commonMain/composeResources/drawable/Swisscom_Lifeform_Colour_RGB_icon.png")
//    systemTray.setStatus("Not Running")
//
//    systemTray.menu.add(MenuItem("Quit", object : ActionListener {
//        override fun actionPerformed(e: ActionEvent?) {
//            systemTray.shutdown()
//            //System.exit(0);  not necessary if all non-daemon threads have stopped.
//        }
//    })).setShortcut('q') // case does not matter

}
