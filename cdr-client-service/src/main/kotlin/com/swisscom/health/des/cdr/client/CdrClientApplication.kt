package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.installer.Installer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.actuate.autoconfigure.scheduling.ScheduledTasksObservabilityAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling

private val logger = KotlinLogging.logger {}
internal const val CONFIG_FILE = "application-customer.properties"

/**
 * Spring Boot entry point
 */
@SpringBootApplication(exclude = [ScheduledTasksObservabilityAutoConfiguration::class])
@EnableConfigurationProperties(CdrClientConfig::class)
@EnableScheduling
class CdrClientApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    if (checkIfInstallationIsRequired(args)) {
        Installer().install()
    } else {
        startSpringBootApp(args)
    }
}

@Suppress("SpreadOperator", "ComplexCondition")
private fun startSpringBootApp(args: Array<String>) {
    val app = SpringApplicationBuilder(CdrClientApplication::class.java)
    if (isRunningFromJpackageInstallation() && (!isSkipInstaller(args))) {
        System.getProperty("LOGGING_FILE_NAME") ?: run {
            logger.debug { "setting loggin_file_name" }
            System.setProperty(
                "LOGGING_FILE_NAME",
                "${getInstallDir().resolve("logs").resolve("cdr-client.log")}"
            )
        }
        logger.info { "setting spring.config.additional-location" }
        System.getProperty("spring.config.additional-location") ?: run {
            System.setProperty(
                "spring.config.additional-location",
                "${getInstallDir().resolve(CONFIG_FILE)}"
            )
        }
    }

    app.run(*args)
}

internal tailrec fun getRootestCause(e: Throwable): Throwable =
    if (e.cause == null)
        e
    else {
        getRootestCause(e.cause!!)
    }
