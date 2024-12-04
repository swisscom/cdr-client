package com.swisscom.health.des.cdr.client.scheduling

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.PullFileHandling
import com.swisscom.health.des.cdr.client.handler.pathIsDirectoryAndWritable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * A Spring service that defines a scheduled task to synchronize files to client folders.
 * @property cdrClientConfig An instance of [CdrClientConfig], which is a configuration class for the CDR client.
 * @property pullFileHandling An instance of [PullFileHandling], which is a service that provides methods for syncing files to client folders.
 */
@Service
@Profile("!noDownloadScheduler")
class DocumentDownloadScheduler(
    private val cdrClientConfig: CdrClientConfig,
    private val pullFileHandling: PullFileHandling,
    @Qualifier("limitedParallelismCdrDownloadsDispatcher")
    private val cdrDownloadsDispatcher: CoroutineDispatcher
) {

    /**
     * A scheduled task that syncs files to client folders at regular intervals.
     */
    @Scheduled(fixedDelayString = "\${client.schedule-delay}")
    suspend fun syncFilesToClientFolders() {
        logger.info { "Triggered pull sync" }
        callPullFileHandling()
    }

    /**
     * Calls the file handling service for each connector in the configuration, in parallel using coroutines.
     */
    private suspend fun callPullFileHandling() {
        withContext(cdrDownloadsDispatcher) {
            if (pathIsDirectoryAndWritable(cdrClientConfig.localFolder, "pulled", logger)) {
                cdrClientConfig.customer.forEach { connector ->
                    launch {
                        runCatching {
                            pullFileHandling.pullSyncConnector(connector)
                        }.onFailure {
                            logger.error(it) { "Error syncing connector '${connector.connectorId}'. Reason: ${it.message}" }
                        }
                    }
                }
            }
        }
    }

}
