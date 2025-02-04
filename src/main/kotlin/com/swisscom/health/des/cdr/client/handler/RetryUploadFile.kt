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
import kotlin.io.path.moveTo
import kotlin.io.path.nameWithoutExtension


private val logger = KotlinLogging.logger {}

/**
 * 4xx errors are not retried, the xml is rewritten to `<filename>.error` and a `<filename>.response` file is created with the response body.
 * Other non-2xx responses are retried with a delay.
 * Deletes the local file after successful upload.
 *
 * TODO: Replace this class with a Spring retry template; push non-retry logic down into [CdrApiClient]
 */
@Component
@Suppress("TooManyFunctions", "LongParameterList")
class RetryUploadFile(
    private val cdrClientConfig: CdrClientConfig,
    private val tracer: Tracer,
    private val cdrApiClient: CdrApiClient,
) {

    /**
     * Retries the upload of a file until it is successful or a 4xx error occurred.
     */
    @Suppress("NestedBlockDepth")
    suspend fun uploadRetrying(file: Path, connector: CdrClientConfig.Connector) {
        logger.debug { "Uploading file '$file'" }
        var retryCount = -1
        var retryNeeded = false

        // a successful rename of the file to upload should guarantee that we can also delete it after a successful upload,
        // and thus prevent duplicate uploads of files we fail to delete
        val uploadFile = file.moveTo(file.resolveSibling("${file.nameWithoutExtension}.upload"))

        do {
            if (retryNeeded) {
                // FIXME: Cannot use delay() here because we might continue on another thread and we would either loose the span id or continue with
                //  the wrong span id (thread local)
                delay(cdrClientConfig.retryDelay[retryCount].toMillis())
            }

            val response: UploadDocumentResult = cdrApiClient.uploadDocument(
                contentType = connector.contentType.toString(),
                file = uploadFile,
                connectorId = connector.connectorId,
                mode = connector.mode,
                traceId = tracer.currentSpan()?.context()?.traceId() ?: ""
            )

            retryNeeded = when (response) {
                is UploadDocumentResult.Success -> {
                    if (!uploadFile.deleteIfExists()) {
                        logger.warn { "Tried to delete the file '$uploadFile' but it was already gone" }
                    }
                    false
                }

                is UploadDocumentResult.UploadClientErrorResponse -> {
                    logger.error {
                        "File synchronization failed for '${uploadFile.fileName}'. Received a 4xx client error (response code: '${response.code}'). " +
                                "No retry will be attempted due to client-side issue."
                    }
                    renameFileToErrorAndCreateLogFile(uploadFile, response.responseBody)
                    false
                }

                is UploadDocumentResult.UploadServerErrorResponse -> {
                    logger.error {
                        "Failed to sync file '${uploadFile.fileName}', retry will be attempted in '${cdrClientConfig.retryDelay[retryCount + 1]}' - " +
                                "server response: '${response.responseBody}'"
                    }
                    true
                }

                is UploadDocumentResult.UploadError -> {
                    logger.error {
                        "Failed to sync file '${uploadFile.fileName}', retry will be attempted in '${cdrClientConfig.retryDelay[retryCount + 1]}' - " +
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
