package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.CONFIG_CHANGE_EXIT_CODE
import com.swisscom.health.des.cdr.client.common.Constants.SHUTDOWN_DELAY
import com.swisscom.health.des.cdr.client.common.Constants.UNKNOWN_EXIT_CODE
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.time.delay
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

@Service
internal class ShutdownService(
    private val context: ConfigurableApplicationContext,
) {

    @OptIn(DelicateCoroutinesApi::class)
    internal fun scheduleShutdown(shutdownTrigger: ShutdownTrigger) {
        if (SHUTDOWN_GUARD.tryLock()) {
            // newSingleThreadExecutor is used to ensure that the shutdown logic runs in a non daemon thread so that SpringApplication.exit won't kill
            // the application context before the shutdown logic is executed (and would therefore never return our defined exit code)
            GlobalScope.launch(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
                logger.info { "Shutdown requested for reason: '$shutdownTrigger'" }
                // Wait a bit to allow the http response to be sent before shutting down
                delay(SHUTDOWN_DELAY)
                exitProcess(SpringApplication.exit(context, { shutdownTrigger.exitCode }))
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
