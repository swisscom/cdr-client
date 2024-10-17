package com.swisscom.health.des.cdr.clientvm.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isWritable


private val logger = KotlinLogging.logger {}

/**
 * CDR client specific configuration
 */
@ConfigurationProperties("client")
data class CdrClientConfig(
    val functionKey: String,
    val scheduleDelay: Duration,
    val localFolder: Path,
    val endpoint: Endpoint,
    val customer: List<Connector>,
    val pullThreadPoolSize: Int,
    val pushThreadPoolSize: Int,
    val retryDelay: List<Duration>,
    val filesInProgressCacheSize: DataSize
) {

    /**
     * Clients identified by their customer id
     */
    data class Connector(
        val connectorId: String,
        val targetFolder: Path,
        val sourceFolder: Path,
        val contentType: MediaType,
        val mode: Mode,
    )

    /**
     * CDR API definition
     */
    data class Endpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val basePath: String,
    )

    enum class Mode(val value: String) {
        TEST("test"), PRODUCTION("production");
    }

    @PostConstruct
    fun checkAndReport() {
        if (customer.isEmpty()) {
            error("There were no customer entries configured")
        }
        sourceTargetFolderOverlap()
        // we don't check target folder for duplicate as this can be configured deliberately by customers
        duplicateSourceFolders()
        checkNoConnectorIdHasTheSameModeDefinedTwice()
        allFoldersAreReadWriteable()

        if (localFolder.exists() && !localFolder.isDirectory()) {
            error("Local folder is not a directory: '$localFolder'")
        }

        if (!localFolder.exists()) {
            localFolder.createDirectories()
        }

        logger.debug { "Client configuration: $this" }
    }

    private fun sourceTargetFolderOverlap(): Unit =
        customer.map { it.sourceFolder }.intersect(customer.map { it.targetFolder }.toSet()).let { sourceAsTargetAndViceVersa ->
            if (sourceAsTargetAndViceVersa.isNotEmpty()) {
                error("The following directories are configured as both source and target directories: $sourceAsTargetAndViceVersa")
            }
        }

    private fun duplicateSourceFolders(): Unit =
        customer.groupingBy { it.sourceFolder }.eachCount().filter { it.value > 1 }.let { duplicateSources ->
            if (duplicateSources.keys.isNotEmpty()) {
                error("Duplicate source folders detected: ${duplicateSources.keys}")
            }

        }

    private fun checkNoConnectorIdHasTheSameModeDefinedTwice(): Unit =
        customer.groupBy { it.connectorId }.filter { cd -> cd.value.size > 1 }.values.forEach { connector ->
            if (connector.groupingBy { cr -> cr.mode }.eachCount().any { it.value > 1 }) {
                error("A connector has `production` or `test` mode defined defined twice: ${connector[0].connectorId}")
            }
        }

    private fun allFoldersAreReadWriteable() {
        val allFolders: List<Path> = customer.flatMap { listOf(it.sourceFolder, it.targetFolder) }
        allFolders.forEach { folder ->
            if (!folder.isDirectory()) {
                error("Configured path '$folder' is not a directory or does not exist.")
            }
            if (!folder.isWritable()) {
                error("Configured path '$folder' isn't writable by running user.")
            }
            if (!folder.isReadable()) {
                error("Configured path '$folder' isn't readable by running user.")
            }
        }
    }

    override fun toString(): String {
        return "CdrClientConfig(functionKey='xxx', scheduleDelay='$scheduleDelay', localFolder='$localFolder', " +
                "customer=$customer, endpoint=$endpoint)"
    }

}
