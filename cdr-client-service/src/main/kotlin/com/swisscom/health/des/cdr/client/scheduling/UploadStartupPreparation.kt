package com.swisscom.health.des.cdr.client.scheduling

import com.swisscom.health.des.cdr.client.common.Constants.RESTART_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.common.Constants.UPLOAD_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.effectiveSourceFolders
import com.swisscom.health.des.cdr.client.handler.SchedulingValidationService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

private val logger = KotlinLogging.logger {}

@Service("uploadStartupPreparation")
@ConditionalOnProperty(prefix = "client", name = ["file-synchronization-enabled"])
internal class UploadStartupPreparation(
    private val config: CdrClientConfig,
    private val schedulingValidationService: SchedulingValidationService,
    @param:Value($$"${management.tracing.sampling.probability:0.0}")
    private val samplerProbability: Double,
) {

    @PostConstruct
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    internal fun prepareUploadStartupState() {
        if (samplerProbability > BaseUploadScheduler.ZERO_SAMPLING_THRESHOLD) {
            logger.error {
                "Telemetry sampling is enabled (sampling probability is set to $samplerProbability). Currently we cannot support telemetry " +
                        "sampling without introducing a memory leak due to the lack of framework integration of micrometer/open-telemetry with Kotlin " +
                        "coroutines/asynchronous flows. You need to disable telemetry sampling."
            }
            error("Telemetry sampling is enabled. Please set the configuration property `management.tracing.sampling.probability` to 0.0")
        }

        val sourceDirectories = config.customer.flatMap { it.effectiveSourceFolders.values.flatten().distinct() }

        sourceDirectories.forEach { dir ->
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                dir.listDirectoryEntries("*.$UPLOAD_FILE_EXTENSION").forEach { file ->
                    logger.warn { "Found existing upload file '${file.absolutePathString()}'; leaving it untouched." }
                }
            }
        }

        if (!schedulingValidationService.isSchedulingAllowed) {
            logger.info { "Scheduling is not allowed. Skipping renaming of '.$RESTART_FILE_EXTENSION' files." }
            return
        }

        logger.info { "Renaming '.$RESTART_FILE_EXTENSION' files to '.xml' in source directories..." }
        sourceDirectories.forEach { dir ->
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                dir.listDirectoryEntries("*.$RESTART_FILE_EXTENSION").forEach { file ->
                    val newFile = file.resolveSibling("${file.nameWithoutExtension}.xml")
                    try {
                        Files.move(file, newFile)
                        logger.info { "Renamed file '${file.absolutePathString()}' to '${newFile.absolutePathString()}'" }
                    } catch (e: Exception) {
                        logger.error { "Failed to rename file '${file.absolutePathString()}': ${e.message}" }
                    }
                }
            }
        }
    }

}
