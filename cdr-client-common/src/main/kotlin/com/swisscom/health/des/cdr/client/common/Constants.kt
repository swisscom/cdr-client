package com.swisscom.health.des.cdr.client.common

import java.time.Duration

object Constants {
    const val SHUTDOWN_DELAY_MILLIS: Long = 250

    @JvmStatic
    val SHUTDOWN_DELAY: Duration = Duration.ofMillis(SHUTDOWN_DELAY_MILLIS)

    const val CONFIG_CHANGE_EXIT_CODE = 29
    const val UNKNOWN_EXIT_CODE = 31
}
