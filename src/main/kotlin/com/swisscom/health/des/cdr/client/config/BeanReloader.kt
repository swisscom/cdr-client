package com.swisscom.health.des.cdr.client.config

import ch.qos.logback.classic.LoggerContext
import com.swisscom.health.des.cdr.client.CdrClientApplication
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component

private val logger: KLogger = KotlinLogging.logger {}

@Component
class BeanReloader(
    private val applicationContext: ApplicationContext,
) {

    fun refreshAndReloadBeans() {
        val restartThread = Thread {
            try {
                Thread.sleep(RESTART_DELAY)
                logger.info { "Restarting the application" }
                val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
                loggerContext.reset()
                (applicationContext as ConfigurableApplicationContext).stop()
                runApplication<CdrClientApplication>()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        restartThread.isDaemon = false
        restartThread.start()
    }

    private companion object {
        private const val RESTART_DELAY = 1000L
    }

}
