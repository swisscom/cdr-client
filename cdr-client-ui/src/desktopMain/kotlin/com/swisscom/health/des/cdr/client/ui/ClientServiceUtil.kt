package com.swisscom.health.des.cdr.client.ui

import com.sun.jna.Platform
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger: KLogger = KotlinLogging.logger {}

internal fun findClientServiceExecutable(): Path? =
    ProcessHandle.current().info().command().get().let { cdrUiCmd: String ->
        logger.debug { "cdr-client-ui command: '$cdrUiCmd'" }
        val cdrServiceCmd =
            if (cdrUiCmd.endsWith(javaExecutableForPlatform()) || cdrUiCmd.endsWith(javawExecutableForPlatform())) {
                logger.debug { $$"UI run via java cmd; assuming UI is run from the IDE and the service is available on the user's '$PATH'" }
                Path.of(clientUiExecutableForPlatform())
            } else if (cdrUiCmd.endsWith(clientUiExecutableForPlatform())) {
                // if the UI is run from its generated executable, it is safe to assume the cdr-client-service executable is present in the same location
                Path.of(cdrUiCmd).resolveSibling(clientServiceExecutableForPlatform()).let { cdrServiceCmd: Path ->
                    if (Files.exists(cdrServiceCmd) && Files.isExecutable(cdrServiceCmd)) {
                        logger.debug { "The CDR client service executable was found relative to the client UI executable." }
                        cdrServiceCmd
                    } else {
                        logger.debug {
                            "The CDR client service executable '$cdrServiceCmd' could not be resolved relative to the client UI executable. " +
                                    $$"Assuming the service executable is available on the '$PATH'."
                        }
                        Path.of(clientUiExecutableForPlatform())
                    }
                }
            } else {
                logger.warn { "Don't know how to derive 'cdr-client-service' cmd from cdr-client-ui cmd '$cdrUiCmd' " }
                null
            }
        cdrServiceCmd.also { cdrServiceCmd ->
            if (cdrServiceCmd != null) {
                logger.debug { "cdr-client-service command: '$cdrServiceCmd'" }
            } else {
                logger.warn { "Could not derive cdr-client-service command from cdr-client-ui command '$cdrUiCmd'" }
            }
        }
    }

internal fun uiIsServiceController(): Boolean =
    System.getProperty("cdr.client.ui.isServiceController", "false").toBoolean()
        .also {
            logger.info { "cdr.client.ui.isServiceController = '$it'" }
        }

internal fun clientServiceExecutableForPlatform(): String =
    when {
        Platform.isLinux() || Platform.isMac() -> LINUX_CLIENT_SERVICE_NAME
        Platform.isWindows() -> WINDOWS_CLIENT_SERVICE_NAME
        else -> error("Unsupported platform: '${Platform.getOSType()}'")
    }

internal fun clientUiExecutableForPlatform(): String =
    when {
        Platform.isLinux() || Platform.isMac() -> LINUX_CLIENT_UI_NAME
        Platform.isWindows() -> WINDOWS_CLIENT_UI_NAME
        else -> error("Unsupported platform: '${Platform.getOSType()}'")
    }

internal fun javaExecutableForPlatform(): String =
    when {
        Platform.isLinux() || Platform.isMac() -> LINUX_JAVA_EXECUTABLE_NAME
        Platform.isWindows() -> WINDOWS_JAVA_EXECUTABLE_NAME
        else -> error("Unsupported platform: '${Platform.getOSType()}'")
    }

internal fun javawExecutableForPlatform(): String =
    when {
        Platform.isLinux() || Platform.isMac() -> LINUX_JAVAW_EXECUTABLE_NAME
        Platform.isWindows() -> WINDOWS_JAVAW_EXECUTABLE_NAME
        else -> error("Unsupported platform: '${Platform.getOSType()}'")
    }

private const val LINUX_CLIENT_SERVICE_NAME = "cdr-client-service"
private const val WINDOWS_CLIENT_SERVICE_NAME = "cdr-client-service.exe"
private const val LINUX_CLIENT_UI_NAME = "cdr-client-ui"
private const val WINDOWS_CLIENT_UI_NAME = "cdr-client-ui.exe"
private const val LINUX_JAVA_EXECUTABLE_NAME = "java"
private const val WINDOWS_JAVA_EXECUTABLE_NAME = "javaw.exe"
private const val LINUX_JAVAW_EXECUTABLE_NAME = "javaw"
private const val WINDOWS_JAVAW_EXECUTABLE_NAME = "javaw.exe"
