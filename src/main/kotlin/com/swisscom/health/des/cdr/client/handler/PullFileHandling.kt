package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.DownloadDocumentResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

private val logger = KotlinLogging.logger {}
internal const val PULL_RESULT_ID_HEADER = "cdr-document-uuid"

/**
 * A class responsible for handling files for a customer by syncing the files to a local folder,
 * and moving the files to the customer folder after successfully downloading them locally.
 */
@Component
@Suppress("TooManyFunctions")
class PullFileHandling(
    private val tracer: Tracer,
    private val cdrApiClient: CdrApiClient,
) {
    /**
     * Downloads files for a specific customer.
     *
     * @param connector the connector to synchronize
     */
    suspend fun pullSyncConnector(connector: CdrClientConfig.Connector) {
        traced(tracer, "Pull Sync Connector ${connector.connectorId}") {
            logger.info { "Sync connector '${connector.connectorId}' (${connector.mode}) - pulling" }
            var counter = 0
            runCatching {
                do {
                    require(pathIsDirectoryAndWritable(connector.targetFolder, "pulled", logger))
                    when (val result = tryDownloadNextDocument(connector)) {
                        is DownloadDocumentResult.Success -> counter++  // carry on; there might be more files in the download queue
                        is DownloadDocumentResult.NoDocumentPending -> break // we are done for this time round
                        is DownloadDocumentResult.DownloadError -> throw IllegalStateException(
                            "Error while downloading file for connector '${connector.connectorId}'",
                            result.t
                        )
                    }
                } while (true)
            }.fold(
                onSuccess = { logger.info { "Sync connector done - '$counter' file(s) pulled" } },
                onFailure = { t: Throwable -> logger.info { "Synced '$counter' file(s) before exception happened" } }
            )
        }
    }


    /**
     * Requests a file and decides if the next file should be called.
     *
     * @param connector the connector to request a file for
     * @return whether to try the next file
     */
    @Suppress("NestedBlockDepth")
    private fun tryDownloadNextDocument(connector: CdrClientConfig.Connector): DownloadDocumentResult =
        cdrApiClient.downloadDocument(
            connectorId = connector.connectorId,
            mode = connector.mode,
            traceId = tracer.currentSpan()?.context()?.traceId() ?: ""
        ).let { downloadResult: DownloadDocumentResult ->
            if (downloadResult is DownloadDocumentResult.Success) {
                cdrApiClient.acknowledgeDocumentDownload(
                    connectorId = connector.connectorId,
                    mode = connector.mode,
                    downloadId = downloadResult.pullResultId,
                    traceId = tracer.currentSpan()?.context()?.traceId() ?: ""
                ).let { ackResult: DownloadDocumentResult ->
                    if (ackResult is DownloadDocumentResult.Success) {
                        moveFileToClientFolder(connector, downloadResult.file)
                    }
                    ackResult
                }
            } else {
                downloadResult
            }
        }


    /**
     * Moves the file from the local folder to the connectors target folder.
     * If the file already exists in the target folder, it will be overwritten.
     * The file that is currently on the local file system, without file extension, will be moved to the target folder, also with no file extension.
     * The file extension is changed from .tmp to .xml after a successful file copy.
     *
     * @param connector the connector for whom the file was requested
     * @param file the file to move
     */
    private fun moveFileToClientFolder(connector: CdrClientConfig.Connector, file: Path): Boolean {
        logger.debug { "Move file to target directory start" }
        val sourceFile = file
        val targetFolder = connector.targetFolder
        val targetTmpFile = targetFolder.resolve(sourceFile.name)

        val success: Boolean = runCatching {
            Files.move(
                sourceFile,
                targetTmpFile,
                StandardCopyOption.REPLACE_EXISTING
            )
            logger.debug { "Move file to target directory done" }
        }.mapCatching {
            // be aware, that this is not an atomic operation on Windows operating system (but it is on Unix-based systems)
            Files.move(
                targetTmpFile,
                targetTmpFile.resolveSibling("${targetTmpFile.nameWithoutExtension}.xml"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.fold(
            onSuccess = { path: Path ->
                true.also { logger.debug { "Moved file '$file' to '${targetTmpFile.resolveSibling("${targetTmpFile.nameWithoutExtension}.xml")}'" } }
            },
            onFailure = { t: Throwable ->
                false.also { logger.error { "Unable to move file '$file' to '${connector.targetFolder}': ${t.message}" } }
            }
        )

        return success
    }

}
