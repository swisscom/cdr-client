package com.swisscom.health.des.cdr.client.ui

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application


fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "CDR Client Manager",
    ) {
        App()
    }
}
