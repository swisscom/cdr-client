package com.swisscom.health.des.cdr.clientvm.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.File
import java.time.Duration


private val logger = KotlinLogging.logger {}

/**
 * CDR client specific configuration
 */
@ConfigurationProperties("client")
data class CdrClientConfig(
    val functionKey: String,
    val scheduleDelay: String,
    val localFolder: String,
    val endpoint: Endpoint,
    val customer: List<Connector>,
    val pullThreadPoolSize: Int,
    val pushThreadPoolSize: Int,
    val retryDelay: Array<Duration>,
) {

    /**
     * Clients identified by their customer id
     */
    data class Connector(
        val connectorId: String,
        val targetFolder: String,
        val sourceFolder: String,
        val contentType: String,
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
            error("There where no customer entries configured")
        }
        // we don't check target folder for duplicate as this can be configured deliberately by customers
        if (duplicateFolderUsage() || duplicateUsage(Connector::sourceFolder)) {
            error("Duplicate folder usage detected. Please make sure that each customer has a unique source and that no target folder is used " +
                    "at the same time as source folder.")
        }
        checkNoConnectorIdHasTheSameModeDefinedTwice()
        File(localFolder).mkdirs()
        logger.debug { "Client configuration: $this" }
    }

    private fun duplicateFolderUsage(): Boolean = customer.map { it.sourceFolder }.intersect(customer.map { it.targetFolder }.toSet()).isNotEmpty()

    private fun <T> duplicateUsage(selector: (Connector) -> T): Boolean =
        customer.map(selector).distinct().size != customer.size

    private fun checkNoConnectorIdHasTheSameModeDefinedTwice(): Unit =
        customer.groupBy { it.connectorId }.filter { cd -> cd.value.size > 1 }.values.forEach { connector ->
            val modeConnectorsMap: Map<Mode, List<Connector>> = connector.groupBy { cr -> cr.mode }
            if (modeConnectorsMap.values.any { it.size > 1 }) {
                error("A single connector ID has production or test defined twice.")
            }
        }

    override fun toString(): String {
        return "CdrClientConfig(functionKey='xxx', scheduleDelay='$scheduleDelay', localFolder='$localFolder', " +
                "customer=$customer, endpoint=$endpoint)"
    }
}
