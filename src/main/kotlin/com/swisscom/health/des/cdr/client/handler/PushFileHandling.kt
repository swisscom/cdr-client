package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.UploadDocumentResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.nameWithoutExtension


private val logger = KotlinLogging.logger {}

/**
 * A class responsible for handling files for a connector by syncing the files from a local folder to the CDR API.
 * Only files with an '.xml' extension are uploaded.
 * 4xx errors are not retried, the xml is rewritten to .xml.error and a .xml.response file is created with the response body.
 * Other non-2xx responses are retried with a delay.
 * Deletes the local file after successful upload.
 */
@Component
@Suppress("TooManyFunctions", "LongParameterList")
class PushFileHandling(
    private val cdrClientConfig: CdrClientConfig,
    private val tracer: Tracer,
    private val cdrApiClient: CdrApiClient,
) {

    /**
     * Retries the upload of a file until it is successful or a 4xx error occurred.
     */
    @Suppress("NestedBlockDepth")
    suspend fun uploadFile(file: Path, connector: CdrClientConfig.Connector) {
        logger.debug { "Uploading file '$file'" }
        var retryCount = -1
        var retryNeeded = false

        do {
            if (retryNeeded) {
                // FIXME: Cannot use delay() here because we might continue on another thread and we would either loose or continue with the wrong span (thread local)
                delay(cdrClientConfig.retryDelay[retryCount].toMillis())
            }

            val response: UploadDocumentResult = cdrApiClient.uploadDocument(
                contentType = connector.contentType.toString(),
                file = file,
                connectorId = connector.connectorId,
                mode = connector.mode,
                traceId = tracer.currentSpan()?.context()?.traceId() ?: ""
            )

            retryNeeded = when (response) {
                is UploadDocumentResult.Success -> {
                    if (!file.deleteIfExists()) {
                        logger.warn { "Tried to delete the file '$file' but it was already gone" }
                    }
                    false
                }

                is UploadDocumentResult.UploadClientErrorResponse -> {
                    logger.error {
                        "File synchronization failed for '${file.fileName}'. Received a 4xx client error (response code: '${response.code}'). " +
                                "No retry will be attempted due to client-side issue."
                    }
                    renameFileToErrorAndCreateLogFile(file, response.responseBody)
                    false
                }

                is UploadDocumentResult.UploadServerErrorResponse -> {
                    logger.error {
                        "Failed to sync file '${file.fileName}', retry will be attempted in '${cdrClientConfig.retryDelay[retryCount + 1]}' - " +
                                "server response: '${response.responseBody}'"
                    }
                    true
                }

                is UploadDocumentResult.UploadError -> {
                    logger.error {
                        "Failed to sync file '${file.fileName}', retry will be attempted in '${cdrClientConfig.retryDelay[retryCount + 1]}' - " +
                                "exception message: '${response.t?.message}'"
                    }
                    true
                }
            }

            retryCount++
        } while (retryNeeded && retryCount < cdrClientConfig.retryDelay.size)
    }

    /**
     * For an error case and to prevent a failed file to be uploaded again it renames the file to '.error' and creates a file with the response body.
     */
    private fun renameFileToErrorAndCreateLogFile(file: Path, responseBdy: String) {
        val errorFile = file.resolveSibling("${file.nameWithoutExtension}.error")
        val logFile = file.resolveSibling("${file.nameWithoutExtension}.response")
        if (!file.toFile().renameTo(errorFile.toFile())) {
            logger.error { "Failed to rename file '${file}'" }
        }
        Files.write(logFile, responseBdy.toByteArray(), StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    }

}
