package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.CONFIG_CHANGE_EXIT_CODE
import com.swisscom.health.des.cdr.client.common.Constants.SHUTDOWN_DELAY
import com.swisscom.health.des.cdr.client.common.Constants.UNKNOWN_EXIT_CODE
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.time.delay
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Service
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

@Service
internal class ShutdownService(
    private val context: ConfigurableApplicationContext,
) {

    @OptIn(DelicateCoroutinesApi::class)
    internal fun scheduleShutdown(shutdownTrigger: ShutdownTrigger) {
        if (SHUTDOWN_GUARD.tryLock()) {
            GlobalScope.launch {
                logger.info { "Shutdown requested for reason: '$shutdownTrigger'" }
                // Wait a bit to allow the http response to be sent before shutting down
                delay(SHUTDOWN_DELAY)
                // previously producing the exit code was a single line:
                // `exitProcess(SpringApplication.exit(context, { shutdownTrigger.exitCode }))`
                // however, there must be some sort of race condition present; half the time the exit code was 0 instead of the code specified in the
                // `shutdownTrigger`
                SpringApplication.exit(context).let { contextExitCode ->
                    if (contextExitCode != 0) {
                        logger.warn { "Spring application context did not exit cleanly, exit code: '$contextExitCode'" }
                    }
                }
                exitProcess(shutdownTrigger.exitCode)
            }
        } else {
            logger.info { "Shutdown already scheduled, ignoring request with given reason: '$shutdownTrigger'" }
        }
    }

    enum class ShutdownTrigger(val reason: String, val exitCode: Int) {
        CONFIG_CHANGE("configurationChange", CONFIG_CHANGE_EXIT_CODE),
        UNKNOWN("unknown", UNKNOWN_EXIT_CODE);

        companion object {
            fun fromReason(reason: String): ShutdownTrigger {
                return entries.firstOrNull { it.reason == reason } ?: UNKNOWN
            }
        }
    }

    companion object {
        @JvmStatic
        private val SHUTDOWN_GUARD = Mutex()
    }
}
