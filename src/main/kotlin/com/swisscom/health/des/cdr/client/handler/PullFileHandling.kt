package com.swisscom.health.des.cdr.client.handler

import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.outputStream

private val logger = KotlinLogging.logger {}
internal const val PULL_RESULT_ID_HEADER = "cdr-document-uuid"

/**
 * A class responsible for handling files for a customer by syncing the files to a local folder,
 * and moving the files to the customer folder after successfully downloading them locally.
 */
@Component
@Suppress("TooManyFunctions")
class PullFileHandling(
    cdrClientConfig: CdrClientConfig,
    private val httpClient: OkHttpClient,
    clientCredentialParams: ClientCredentialParameters,
    @Qualifier("retryIoErrorsThrice")
    private val retryIoErrorsThrice: RetryTemplate,
    securedApp: IConfidentialClientApplication,
    tracer: Tracer,
) : FileHandlingBase(cdrClientConfig, clientCredentialParams, retryIoErrorsThrice, securedApp, tracer) {
    /**
     * Downloads files for a specific customer.
     *
     * @param connector the connector to synchronize
     */
    suspend fun pullSyncConnector(connector: CdrClientConfig.Connector) {
        traced("Pull Sync Connector ${connector.connectorId}") {
            logger.info { "Sync connector '${connector.connectorId}' (${connector.mode}) - pulling" }
            var counter = 0
            var tryNext: Boolean
            runCatching {
                do {
                    tryNext = checkDirectoryAndProcessFile(connector)
                    if (tryNext) counter++
                } while (tryNext)
            }.onFailure {
                logger.info { "Synced '$counter' file(s) before exception happened" }
                throw it
            }
            logger.info { "Sync connector done - '$counter' file(s) pulled" }
        }
    }

    private fun checkDirectoryAndProcessFile(connector: CdrClientConfig.Connector): Boolean =
        if (pathIsDirectoryAndWritable(connector.targetFolder, "pulled", logger)) {
            requestFileAndDecideIfNextFileShouldBeCalled(connector)
        } else {
            false
        }

    /**
     * Requests a file and decides if the next file should be called.
     *
     * @param connector the connector to request a file for
     * @return whether to try the next file
     */
    private fun requestFileAndDecideIfNextFileShouldBeCalled(connector: CdrClientConfig.Connector): Boolean =
        requestFile(connector).use { response ->
            return when {
                response.isNoContentFound() -> false

                !response.isSuccessWithContent() -> {
                    logger.error {
                        "Error requesting file for connector '${connector.connectorId}' (${connector.mode}), because of: (${response.code}) ${response.message}"
                    }
                    false
                }

                else -> {
                    val pullResultHeader = response.headers[PULL_RESULT_ID_HEADER]
                    return if (pullResultHeader == null) {
                        logger.error { "didn't receive header '$PULL_RESULT_ID_HEADER'" }
                        false
                    } else {
                        syncFileToLocalFolder(response.body!!.byteStream(), pullResultHeader)
                        acknowledgeFileDownload(connector, pullResultHeader)
                                && moveFileToClientFolder(connector, pullResultHeader)
                    }
                }
            }
        }

    /**
     * Sends a GET-Request to the CDR API to request a file.
     *
     * @param connector the connector to request a file for
     * @return the HTTP response for the request
     */
    private fun requestFile(connector: CdrClientConfig.Connector): Response {
        logger.debug { "Request file start" }
        val queryParameters = LinkedMultiValueMap<String, String>()
        queryParameters.add("limit", "1")
        val response = httpClient.newCall(
            createGetRequest(
                buildTargetUrl(cdrClientConfig.endpoint.basePath, queryParameters), buildBaseHeaders(connector.connectorId, connector.mode)
            )
        ).execute()
        logger.debug { "Request file done" }
        return response
    }

    /**
     * Checks if an HTTP response is successful.
     *
     * @return whether the response is a success or not
     */
    fun Response.isSuccessWithContent(): Boolean = this.isSuccessful && this.body != null

    /**
     * Checks if the HTTP response is of status code 204 (NO_CONTENT).
     */
    fun Response.isNoContentFound(): Boolean = this.code == HttpStatus.NO_CONTENT.value()

    /**
     * Creates a GET request with the given target and headers
     * @param to the target URL for the request
     * @param headers the headers for the request
     * @return the created GET request
     */
    private fun createGetRequest(to: URL, headers: Headers): Request {
        return Request.Builder()
            .url(to)
            .headers(headers)
            .get()
            .build()
    }

    /**
     * Creates a DELETE request with the given target and headers
     * @param to the target URL for the request
     * @param headers the headers for the request
     * @return the created DELETE request
     */
    private fun createDeleteRequest(to: URL, headers: Headers): Request {
        return Request.Builder()
            .url(to)
            .headers(headers)
            .delete()
            .build()
    }

    /**
     * Synchronizes the file for the given transaction ID to the local folder
     * @param inputStream the input stream for the file
     * @param pullResultId the ID to identify the pulled file
     */
    private fun syncFileToLocalFolder(inputStream: InputStream, pullResultId: String) =
        cdrClientConfig.localFolder.resolve(getTempFileName(pullResultId)).outputStream().use { output -> inputStream.copyTo(output) }

    /**
     * Reports the success of the transaction to the connectors' endpoint.
     * Needs to be done after each file as otherwise the CDR API will provide the same file again.
     *
     * @param connector the connector for whom the file was requested
     * @param pullResultId the ID to identify the pulled file
     * @return true if the reporting was successful, false otherwise
     */
    private fun acknowledgeFileDownload(connector: CdrClientConfig.Connector, pullResultId: String): Boolean {
        logger.debug { "Acknowledge pulled file start" }
        httpClient.newCall(
            createDeleteRequest(
                buildTargetUrl("${cdrClientConfig.endpoint.basePath}/$pullResultId"),
                buildBaseHeaders(connector.connectorId, connector.mode)
            )
        ).execute().use { response ->
            return if (response.isSuccessful) {
                logger.debug { "Acknowledge pulled file done" }
                true
            } else {
                logger.error {
                    "Error when acknowledging file with identifier '$pullResultId' of connector '${connector.connectorId}' (${connector.mode}): " +
                            "(${response.code}) ${response.message}"
                }
                false
            }
        }
    }

    /**
     * Moves the file from the local folder to the connectors target folder.
     * If the file already exists in the target folder, it will be overwritten.
     * The file that is currently on the local file system, without file extension, will be moved to the target folder, also with no file extension.
     * The file extension is changed from .tmp to .xml after a successful file copy.
     *
     * @param connector the connector for whom the file was requested
     * @param pullResultId the ID to identify the pulled file
     */
    private fun moveFileToClientFolder(connector: CdrClientConfig.Connector, pullResultId: String): Boolean {
        return try {
            logger.debug { "Move file to target directory start" }
            val tmpFileName = getTempFileName(pullResultId)
            val sourceFile = cdrClientConfig.localFolder.resolve(tmpFileName)
            val targetFolder = connector.targetFolder
            val targetTmpFile = targetFolder.resolve(tmpFileName)
            Files.move(
                sourceFile,
                targetTmpFile,
                StandardCopyOption.REPLACE_EXISTING
            )
            logger.debug { "Move file to target directory done" }
            // be aware, that this is not an atomic operation on Windows operating system (but it is on Unix-based systems)
            val success: Boolean = runCatching {
                Files.move(
                    targetTmpFile,
                    targetTmpFile.resolveSibling("$pullResultId.xml"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.fold(
                onSuccess = { path: Path ->
                    true.also { logger.debug { "Renamed file '$targetTmpFile' to '$path'" } }
                },
                onFailure = { t: Throwable ->
                    false.also { logger.error { "Unable to rename file '$targetTmpFile' for transactionId '$pullResultId': ${t.message}" } }
                }
            )
            success
        } catch (e: IOException) {
            logger.error { "Unable to move file for transactionId '$pullResultId': ${e.message}" }
            false
        }
    }

    private fun getTempFileName(pullResultId: String) = "${pullResultId}.tmp"

}
