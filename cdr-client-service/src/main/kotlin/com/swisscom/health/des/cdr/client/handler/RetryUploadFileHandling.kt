package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.Constants.RESTART_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.getConnectorForSourceFile
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.UploadDocumentResult
import com.swisscom.health.des.cdr.client.scheduling.BaseUploadScheduler.Companion.EXTENSION_XML
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.time.delay
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.moveTo
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
    @Suppress("NestedBlockDepth", "LongMethod", "CyclomaticComplexMethod")
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

                    is UploadDocumentResult.UploadClientRetryableErrorResponse -> {
                        logger.error {
                            "File synchronization failed for '${uploadFile.fileName}'. Received a client error (response code: '${response.code}'). " +
                                    "Retry will be attempted in '${cdrClientConfig.retryDelay[retryIndex]}'"
                        }
                        true
                    }

                    is UploadDocumentResult.UploadClientConfigNonRetryableErrorResponse -> {
                        logger.error {
                            "File synchronization failed for '${uploadFile.fileName}'. Received a client error (response code: '${response.code}'). " +
                                    "The file extension will be modified and it will be retried after the next restart."
                        }
                        renameFileToFail(uploadFile)
                        false
                    }

                    is UploadDocumentResult.UploadClientErrorResponse -> {
                        logger.error {
                            "File synchronization failed for '${uploadFile.fileName}'. Received a client error (response code: '${response.code}'). " +
                                    "No retry will be attempted and the file will be moved to the error directory due to client-side issue."
                        }
                        renameFileToErrorAndCreateLogFile(uploadFile, response.responseBody)
                        false
                    }

                    is UploadDocumentResult.UploadServerErrorResponse -> {
                        logger.error {
                            "File synchronization failed for '${uploadFile.fileName}'. Received a client error (response code: '${response.code}'). " +
                                    "Retry will be attempted in '${cdrClientConfig.retryDelay[retryIndex]}' - server response:\n'${response.responseBody}'"
                        }
                        true
                    }

                    is UploadDocumentResult.UploadError -> {
                        logger.error {
                            "File synchronization failed for '${uploadFile.fileName}'. No server response is available. " +
                                    "Retry will be attempted in '${cdrClientConfig.retryDelay[retryIndex]}' - exception message: '${response.t?.message}'"
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
            connector.getEffectiveSourceArchiveFolder()?.let { archiveDir ->
                file.moveTo(
                    archiveDir.resolve("${file.nameWithoutExtension}_${UUID.randomUUID()}.$EXTENSION_XML")
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

    private fun renameFileToFail(file: Path): Unit = runCatching {
        val failFile = file.resolveSibling("${file.nameWithoutExtension}.$RESTART_FILE_EXTENSION")
        file.moveTo(failFile)
    }.fold(
        onSuccess = {},
        onFailure = { t: Throwable -> logger.error { "Error during renaming of file '${file}': '$t'" } }
    )

    /**
     * For an error case, adds a UUID to the filename and creates a file with the response body with file extension '.response'.
     * If the filename already contains at least two UUIDs, replaces all but the first UUID with a new one to prevent excessively long filenames.
     */
    private fun renameFileToErrorAndCreateLogFile(file: Path, responseBody: String): Unit = runCatching {
        val newBaseName = getBaseNameWithSingleOrNewUuid(file.nameWithoutExtension)
        val errorFolder = cdrClientConfig.customer.getConnectorForSourceFile(file).getEffectiveSourceErrorFolder()
        val errorFile = errorFolder.resolve("$newBaseName.$EXTENSION_XML")
        val logFile = errorFolder.resolve("$newBaseName.response")
        file.moveTo(errorFile)
        Files.write(logFile, responseBody.toByteArray(), StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    }.fold(
        onSuccess = { },
        onFailure = { t: Throwable -> logger.error { "Error during handling of failed upload of '${file}': '$t'" } }
    )

    /**
     * Detects if a filename contains multiple UUIDs (identified by the UUID pattern).
     * If it contains at least two UUIDs, keeps the first UUID and replaces all subsequent ones with a new UUID.
     * If it contains fewer than two UUIDs, appends a new UUID.
     */
    private fun getBaseNameWithSingleOrNewUuid(baseName: String): String {
        val matches = uuidPattern.findAll(baseName).toList()

        return if (matches.size >= 2) {
            // Keep everything up to and including the first UUID
            val firstUuidMatch = matches[0]
            val beforeFirstUuid = baseName.substring(0, firstUuidMatch.range.first)
            val firstUuid = firstUuidMatch.value
            // Replace everything after the first UUID with a new UUID
            val newUuid = UUID.randomUUID().toString()
            "$beforeFirstUuid${firstUuid}_$newUuid"
        } else {
            // Less than two UUIDs, append a new one
            "${baseName}_${UUID.randomUUID()}"
        }
    }

    companion object {
        val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
    }

}
