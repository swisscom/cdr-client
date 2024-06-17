package com.swisscom.health.des.cdr.clientvm.scheduling

import com.swisscom.health.des.cdr.clientvm.config.CdrClientConfig
import com.swisscom.health.des.cdr.clientvm.handler.PullFileHandling
import com.swisscom.health.des.cdr.clientvm.handler.PushFileHandling
import com.swisscom.health.des.cdr.clientvm.handler.pathIsDirectoryAndWritable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * A Spring service that defines a scheduled task to synchronize files to client folders.
 * @property cdrClientConfig An instance of [CdrClientConfig], which is a configuration class for the CDR client.
 * @property pullFileHandling An instance of [PullFileHandling], which is a service that provides methods for syncing files to client folders.
 */
@Service
class Scheduler(
    private val cdrClientConfig: CdrClientConfig,
    private val pullFileHandling: PullFileHandling,
    private val pushFileHandling: PushFileHandling,
) {

    /**
     * A scheduled task that syncs files to client folders at regular intervals.
     */
    @Scheduled(fixedDelayString = "\${client.schedule-delay}")
    fun syncFilesToClientFolders() {
        logger.info { "Triggered pull sync" }
        runBlocking {
            callPullFileHandling()
        }
    }

    /**
     * Calls the file handling service for each connector in the configuration, in parallel using coroutines.
     */
    private suspend fun callPullFileHandling() {
        withContext(Dispatchers.IO) {
            if (pathIsDirectoryAndWritable(Path.of(cdrClientConfig.localFolder), "pulled", logger)) {
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

    /**
     * A scheduled task that syncs local files to the CDR API at regular intervals.
     */
    @Scheduled(fixedDelayString = "\${client.schedule-delay}")
    fun syncFilesToApi() {
        logger.info { "Triggered push sync" }
        runBlocking {
            callPushFileHandling()
        }
    }

    /**
     * Calls the file handling service for each connector in the configuration, in parallel using coroutines.
     */
    private suspend fun callPushFileHandling() {
        withContext(Dispatchers.IO) {
            cdrClientConfig.customer.forEach { connector ->
                launch {
                    runCatching {
                        pushFileHandling.pushSyncConnector(connector)
                    }.onFailure {
                        logger.error(it) { "Error syncing connector '${connector.connectorId}'. Reason: ${it.message}" }
                    }
                }
            }
        }
    }

}
