package com.swisscom.health.des.cdr.client.ui

import com.sun.jna.Platform
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

private const val SERVICE_NAME = "cdr-client-service"
private val logger = KotlinLogging.logger {}

internal fun isMacOS(): Boolean {
    return Platform.isMac()
}

internal fun monitorServiceProcess(
    processRef: AtomicReference<Process?>,
    shouldRestart: (Int) -> Boolean,
    startProcess: () -> Process?
): Job {
    return CoroutineScope(Dispatchers.IO).launch {
        var proc = processRef.get()
        while (proc != null) {
            val exitCode = try {
                proc.waitFor()
            } catch (_: Exception) {
                break
            }
            if (shouldRestart(exitCode)) {
                logger.debug { "cdr-client-service exited with code '$exitCode', restarting..." }
                proc = startProcess()
                processRef.set(proc)
            } else {
                break
            }
        }
    }
}

internal fun startCdrClientServiceIfNotRunning(): Process? {
    if (isServiceProcessRunning()) {
        logger.debug { "Process is already running, not starting a new one." }
        return null
    }

    val possibleDirs = mutableListOf<File>()

    try {
        val codeSource = object {}.javaClass.protectionDomain.codeSource
        if (codeSource != null) {
            val location = File(codeSource.location.toURI())
            if (location.isFile) {
                // If running from a JAR, try its parent and common bin locations
                possibleDirs += location.parentFile
                location.parentFile?.parentFile?.resolve("bin")?.let { possibleDirs += it }
                location.parentFile?.parentFile?.parentFile?.resolve("bin")?.let { possibleDirs += it }
            } else if (location.isDirectory) {
                // If running from a native launcher in bin/
                logger.debug { "Found code source location: ${location.absolutePath}" }
                possibleDirs += location
            }
        }
    } catch (_: Exception) {
        // ignore, fallback below
    }

    possibleDirs += File(System.getProperty("user.dir"))
    if (logger.isDebugEnabled()) {
        possibleDirs.forEach { logger.debug { "possibleDir '$it'" } }
    }
    val serviceExec = possibleDirs
        .map { File(it, SERVICE_NAME) }
        .firstOrNull { it.exists() && it.canExecute() }
        ?: File(possibleDirs.first(), SERVICE_NAME)

    logger.debug { "Trying to start ${serviceExec.absolutePath}" }
    return try {
        ProcessBuilder(serviceExec.absolutePath)
            .directory(serviceExec.parentFile)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    } catch (_: IOException) {
        logger.error { "Failed to start cdr-client-service from '${serviceExec.absolutePath}', it may not be installed or executable." }
        // TODO: provide user feedback that the service cannot be started
        null
    }
}

internal fun isServiceProcessRunning(): Boolean {
    return try {
        logger.debug { "Checking if process '$SERVICE_NAME' is running" }
        val proc = ProcessBuilder("pgrep", "-f", SERVICE_NAME).start()
        proc.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}
