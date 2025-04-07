package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import org.springframework.boot.actuate.autoconfigure.scheduling.ScheduledTasksObservabilityAutoConfiguration
import com.swisscom.health.des.cdr.client.installer.Installer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.annotation.EnableScheduling
import java.awt.AWTException
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import kotlin.system.exitProcess

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
        .listeners(ApplicationListener<ApplicationReadyEvent> {
            if (isUiAvailable()) {
                System.setProperty("java.awt.headless", "false") // for system tray only
                initSystemTray()
            }
        })
        .listeners(ApplicationListener<ContextClosedEvent> {
            if (isUiAvailable()) {
                SystemTray.getSystemTray().trayIcons.forEach { SystemTray.getSystemTray().remove(it) }
            }
        })
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

private fun initSystemTray() {
    if (!SystemTray.isSupported()) {
        logger.warn { "System tray is not supported" }
        return
    }

    val tray = SystemTray.getSystemTray()
    val imageFile = ClassPathResource("icon.png")
    val image = Toolkit.getDefaultToolkit().createImage(imageFile.inputStream.readAllBytes())
    val trayIcon = TrayIcon(image, "Swisscom CDR Client")
    trayIcon.isImageAutoSize = true
    trayIcon.toolTip = "Swisscom CDR Client"

    val popup = PopupMenu()
    val exitItem = MenuItem("Exit")
    exitItem.addActionListener {
        tray.remove(trayIcon)
        exitProcess(0)
    }
    popup.add(exitItem)
    trayIcon.popupMenu = popup

    try {
        tray.add(trayIcon)
    } catch (e: AWTException) {
        logger.error { "TrayIcon could not be added: ${e.message}" }
    }
}

internal tailrec fun getRootestCause(e: Throwable): Throwable =
    if (e.cause == null)
        e
    else {
        getRootestCause(e.cause!!)
    }
