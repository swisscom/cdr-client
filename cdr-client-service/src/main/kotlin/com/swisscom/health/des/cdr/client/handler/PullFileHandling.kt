package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.DownloadDocumentResult
import com.swisscom.health.des.cdr.client.xml.DocumentType
import com.swisscom.health.des.cdr.client.xml.XmlUtil
import com.swisscom.health.des.cdr.client.xml.toDom
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

private val logger = KotlinLogging.logger {}
internal const val PULL_RESULT_ID_HEADER = "cdr-document-uuid"

/**
 * A class responsible for handling files for a customer by syncing the files to a local directory,
 * and moving the files to the customer directory after successfully downloading them locally.
 */
@Component
@Suppress("TooManyFunctions")
internal class PullFileHandling(
    private val tracer: Tracer,
    private val cdrApiClient: CdrApiClient,
    private val xmlParser: XmlUtil
) {
    /**
     * Downloads files for a specific customer.
     *
     * @param connector the connector to synchronize
     */
    suspend fun pullSyncConnector(connector: Connector) {
        tracer.withSpan("Pull Sync Connector ${connector.connectorId}") {
            logger.info { "Sync connector '${connector.connectorId}' (${connector.mode}) - pulling" }
            var counter = 0
            runCatching {
                do {
                    require(pathIsDirectoryAndWritable(connector.targetFolder, "pulled", logger))
                    when (val result = tryDownloadNextDocument(connector)) {
                        is DownloadDocumentResult.Success -> counter++  // carry on; there might be more files in the download queue
                        is DownloadDocumentResult.NoDocumentPending -> break // we are done for this time round
                        is DownloadDocumentResult.Error -> throw IllegalStateException(
                            "Error while downloading file for connector '${connector.connectorId}'",
                            result.t
                        )
                    }
                } while (true)
            }.fold(
                onSuccess = { logger.info { "Sync connector done - '$counter' file(s) pulled" } },
                onFailure = { logger.info { "Synced '$counter' file(s) before exception happened" } }
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
    private fun tryDownloadNextDocument(connector: Connector): DownloadDocumentResult =
        cdrApiClient.downloadDocument(
            connectorId = connector.connectorId.id,
            mode = connector.mode,
            traceId = tracer.currentSpan()?.context()?.traceId() ?: EMPTY_STRING
        ).let { downloadResult: DownloadDocumentResult ->
            if (downloadResult is DownloadDocumentResult.DownloadSuccess) {
                cdrApiClient.acknowledgeDocumentDownload(
                    connectorId = connector.connectorId.id,
                    mode = connector.mode,
                    downloadId = downloadResult.pullResultId,
                    traceId = tracer.currentSpan()?.context()?.traceId() ?: EMPTY_STRING
                ).let { ackResult: DownloadDocumentResult ->
                    if (ackResult is DownloadDocumentResult.AcknowledgeSuccess) {
                        moveFileToClientDirectory(connector, downloadResult.file)
                    }
                    ackResult
                }
            } else {
                downloadResult
            }
        }


    /**
     * Moves the file from the local directory to the connectors target directory.
     * If the file already exists in the target directory, it will be overwritten.
     * The file that is currently on the local file system, without file extension,
     * will be moved to the target directory, also with no file extension. The
     * file extension is changed from .tmp to .xml after a successful file copy.
     *
     * @param connector the connector for whom the file was requested
     * @param file the file to move
     */
    private fun moveFileToClientDirectory(connector: Connector, file: Path) {
        logger.debug { "Move file to target directory start" }
        val targetDir =
            if (connector.effectiveDocTypeFolders.isEmpty()) {
                connector.targetFolder
            } else {
                val documentType: DocumentType =
                    runCatching {
                        xmlParser.findSchemaDefinition(file.inputStream().use { it.toDom() })
                    }.fold(
                        onSuccess = { it },
                        onFailure = { e ->
                            logger.error { "Failed to determine document type of file '$file': ${e.message}" }
                            DocumentType.UNDEFINED
                        }
                    )
                connector.effectiveDocTypeFolders[documentType]?.let { docTypeFolder -> connector.effectiveTargetFolder(docTypeFolder) }
                    ?: connector.targetFolder.also { logger.debug { "No specific target directory defined for files of type '${documentType}'" } }
            }
        val targetTmpFile = targetDir.resolve(file.name)

        runCatching {
            Files.move(
                file,
                targetTmpFile,
                StandardCopyOption.REPLACE_EXISTING
            )
            logger.debug { "Move file to target directory done" }
        }.mapCatching {
            // be aware that this is not an atomic operation on Windows systems (but it is on Linux-based systems)
            Files.move(
                targetTmpFile,
                targetTmpFile.resolveSibling("${targetTmpFile.nameWithoutExtension}.xml"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.fold(
            onSuccess = {
                logger.debug { "Moved file '$file' to '${targetTmpFile.resolveSibling("${targetTmpFile.nameWithoutExtension}.xml")}'" }
            },
            onFailure = { t: Throwable ->
                logger.error { "Unable to move file '$file' to '${connector.targetFolder}': ${t.message}" }
            }
        )
    }

}
