package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.getConnectorForSourceFile
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.UploadDocumentResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.time.delay
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.math.min


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
class RetryUploadFileHandling(
    private val cdrClientConfig: CdrClientConfig,
    private val tracer: Tracer,
    private val cdrApiClient: CdrApiClient,
) {

    /**
     * Retries the upload of a file until it is successful or a 4xx error occurred.
     */
    @Suppress("NestedBlockDepth", "LongMethod")
    suspend fun uploadRetrying(file: Path, connector: CdrClientConfig.Connector) {
        logger.debug { "Uploading file '$file'" }
        var retryCount = 0
        var retryNeeded = false

        // a successful rename of the file to upload should guarantee that we can also delete it after a successful upload,
        // and thus prevent duplicate uploads of a file if we fail to delete or archive it after a successful upload
        val uploadFile = file.moveTo(file.resolveSibling("${file.nameWithoutExtension}.upload"))

        runCatching {
            do {
                val retryIndex = min(retryCount, cdrClientConfig.retryDelay.size - 1)

                val response: UploadDocumentResult = cdrApiClient.uploadDocument(
                    contentType = connector.contentType.toString(),
                    file = uploadFile,
                    connectorId = connector.connectorId,
                    mode = connector.mode,
                    traceId = tracer.currentSpan()?.context()?.traceId() ?: ""
                )

                retryNeeded = when (response) {
                    is UploadDocumentResult.Success -> {
                        logger.debug { "File '${uploadFile.fileName}' successfully synchronized." }
                        deleteOrArchiveFile(uploadFile)
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
                            "Failed to sync file '${uploadFile.fileName}', retry will be attempted in '${cdrClientConfig.retryDelay[retryIndex]}' - " +
                                    "server response: '${response.responseBody}'"
                        }
                        true
                    }

                    is UploadDocumentResult.UploadError -> {
                        logger.error {
                            "Failed to sync file '${uploadFile.fileName}', retry will be attempted in '${cdrClientConfig.retryDelay[retryIndex]}' - " +
                                    "exception message: '${response.t?.message}'"
                        }
                        true
                    }
                }

                if (retryNeeded) {
                    delay(cdrClientConfig.retryDelay[retryIndex])
                    retryCount++
                    logger.info { "Retry attempt '#$retryCount' for file '${uploadFile.fileName}'" }
                }
            } while (retryNeeded)
        }.fold(
            onSuccess = {},
            onFailure = { t: Throwable ->
                if (t is CancellationException) {
                    // we are getting shut down; moving the file back to its original location so it gets picked up again on restart
                    runCatching { if (uploadFile.exists()) uploadFile.moveTo(file) }
                }
                throw t
            }
        )
    }

    private fun deleteOrArchiveFile(file: Path): Unit = runCatching {
        cdrClientConfig.customer.getConnectorForSourceFile(file).let { connector ->
            if (connector.sourceArchiveEnabled) {
                file.moveTo(
                    connector.getEffectiveSourceArchiveFolder(file).resolve("${file.nameWithoutExtension}_${UUID.randomUUID()}.xml")
                )
            } else {
                if (!file.deleteIfExists()) {
                    logger.warn { "Tried to delete the file '$file' but it was already gone" }
                }
            }
        }
    }.fold(
        onSuccess = {},
        onFailure = { t: Throwable -> logger.error { "Error during handling of successful upload of '${file}': '$t'" } }
    )

    /**
     * For an error case renames the file to '.error' and creates a file with the response body.
     */
    private fun renameFileToErrorAndCreateLogFile(file: Path, responseBdy: String): Unit = runCatching {
        val uuidString = UUID.randomUUID().toString()
        val errorFile = file.resolveSibling("${file.nameWithoutExtension}_$uuidString.error")
        val logFile = file.resolveSibling("${file.nameWithoutExtension}_$uuidString.response")
        file.moveTo(errorFile)
        Files.write(logFile, responseBdy.toByteArray(), StandardOpenOption.APPEND, StandardOpenOption.CREATE)

        cdrClientConfig.customer.getConnectorForSourceFile(file).let { connector ->
            if (connector.getEffectiveSourceErrorFolder(file) != file.parent) {
                errorFile.moveTo(connector.getEffectiveSourceErrorFolder(file).resolve(errorFile.name))
                logFile.moveTo(connector.getEffectiveSourceErrorFolder(file).resolve(logFile.name))
            }
        }
    }.fold(
        onSuccess = {},
        onFailure = { t: Throwable -> logger.error { "Error during handling of failed upload of '${file}': '$t'" } }
    )

}
