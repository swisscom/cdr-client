package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.getConnectorForSourceFile
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.UploadDocumentResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
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
internal class RetryUploadFileHandling(
    private val cdrClientConfig: CdrClientConfig,
    private val tracer: Tracer,
    private val cdrApiClient: CdrApiClient,
) {

    private val uploadGuard = Semaphore(cdrClientConfig.pushThreadPoolSize)

    /**
     * Retries the upload of a file until it is successful or a 4xx error occurred.
     */
    @Suppress("NestedBlockDepth", "LongMethod")
    suspend fun uploadRetrying(file: Path, connector: Connector) {
        logger.debug { "Uploading file '$file'" }
        var retryCount = 0
        var retryNeeded: Boolean

        val uploadFile: Path = file.resolveSibling("${file.nameWithoutExtension}.upload")

        runCatching {
            uploadGuard.acquire()
            // a successful rename of the file to upload should guarantee that we can also delete it after a successful upload,
            // and thus prevent duplicate uploads of a file if we fail to delete or archive it after a successful upload
            file.moveTo(uploadFile)
            do {
                val retryIndex = min(retryCount, cdrClientConfig.retryDelay.size - 1)

                val response: UploadDocumentResult = cdrApiClient.uploadDocument(
                    contentType = connector.contentType,
                    file = uploadFile,
                    connectorId = connector.connectorId.id,
                    mode = connector.mode,
                    traceId = tracer.currentSpan()?.context()?.traceId() ?: EMPTY_STRING
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
            onSuccess = {
                logger.debug { "Upload of file '${uploadFile.fileName}' done." }
                uploadGuard.release()
            },
            onFailure = { t: Throwable ->
                when (t) {
                    is CancellationException -> {
                        // don't release the semaphore if we are getting cancelled; the CancellationException might have been thrown by the `acquire()` call;
                        // calling release() without the matching successful acquire() might throw an IllegalStateException
                        throw t.also {
                            if (uploadFile.exists()) {
                                logger.debug {
                                    "Upload of file '${uploadFile.fileName}' was cancelled due to shutdown, " +
                                            "renaming it to original name '$file' for a future upload."
                                }
                                // we are getting shut down; moving the file back to its original location so it gets picked up again on restart
                                runCatching { uploadFile.moveTo(file) }
                            }
                        }
                    }

                    else -> throw t.also {
                        logger.error(t) { "Upload of file '${uploadFile.fileName}' has failed." }
                        uploadGuard.release()
                    }
                }
            }
        )
    }

    private fun deleteOrArchiveFile(file: Path): Unit = runCatching {
        cdrClientConfig.customer.getConnectorForSourceFile(file).let { connector ->
            connector.getEffectiveSourceArchiveFolder(file)?.let { archiveDir ->
                file.moveTo(
                    archiveDir.resolve("${file.nameWithoutExtension}_${UUID.randomUUID()}.xml")
                )
            } ?: run {
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
     * For an error case, renames the file to '.error' and creates a file with the response body.
     */
    private fun renameFileToErrorAndCreateLogFile(file: Path, responseBody: String): Unit = runCatching {
        val uuidString = UUID.randomUUID().toString()
        val errorFile = file.resolveSibling("${file.nameWithoutExtension}_$uuidString.error")
        val logFile = file.resolveSibling("${file.nameWithoutExtension}_$uuidString.response")
        file.moveTo(errorFile)
        Files.write(logFile, responseBody.toByteArray(), StandardOpenOption.APPEND, StandardOpenOption.CREATE)

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
