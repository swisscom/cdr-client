package com.swisscom.health.des.cdr.client.handler

import com.mayakapps.kache.ObjectKache
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Component
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Objects.isNull
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes


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
    cdrClientConfig: CdrClientConfig,
    tracer: Tracer,
    private val httpClient: OkHttpClient,
    private val processingInProgressCache: ObjectKache<String, Path>,
    clientCredentialParams: ClientCredentialParameters,
    @Qualifier("retryIoErrorsThrice")
    private val retryIoErrorsThrice: RetryTemplate,
    securedApp: IConfidentialClientApplication,
) : FileHandlingBase(cdrClientConfig, clientCredentialParams, retryIoErrorsThrice, securedApp, tracer) {

    /**
     * Retries the upload of a file until it is successful or a 4xx error occurred.
     */
    @Suppress("NestedBlockDepth")
    suspend fun uploadFile(file: Path, connector: CdrClientConfig.Connector) {
        logger.debug { "Push file '$file'" }
        var retryCount = 0
        var retryNeeded = true
        try {
            while (retryNeeded) {
                uploadFileToApi(file, connector).use { response: Response ->
                    retryNeeded = handleResponseAndSignalIfRetryIsNeeded(file, response, retryCount)
                    if (retryNeeded && retryCount < cdrClientConfig.retryDelay.size - 1) {
                        retryCount++
                    }
                }
            }
        } finally {
            processingInProgressCache.remove(file.absolutePathString()).also { removed ->
                if (isNull(removed)) {
                    logger.warn {
                        "File '${file.absolutePathString()}' was not in the processing cache! It appears that we have a bug in our state management."
                    }
                }
            }
        }
    }

    private fun uploadFileToApi(file: Path, connector: CdrClientConfig.Connector): Response {
        // TODO: change this to handle big files
        return httpClient.newCall(
            createPostRequest(
                file.readBytes().toRequestBody(connector.contentType.toString().toMediaType()),
                buildTargetUrl(cdrClientConfig.endpoint.basePath),
                buildBaseHeaders(connector.connectorId, connector.mode)
            )
        ).execute()
    }

    /**
     * Checks the HTTP return status of the API and decides whether a retry is needed and/or an error needs to be logged.
     */
    private suspend fun handleResponseAndSignalIfRetryIsNeeded(file: Path, response: Response, retryCount: Int): Boolean {
        val responseStatus: HttpStatus? = HttpStatus.resolve(response.code)
        return if (responseStatus != null) {
            when {
                responseStatus.is2xxSuccessful -> {
                    if (!file.deleteIfExists()) {
                        logger.warn { "Tried to delete the file '$file' but it was already gone" }
                    }
                    false
                }

                responseStatus.is4xxClientError -> {
                    logger.error {
                        "File synchronization failed for '${file.fileName}'. Received a 4xx client error (response code: '${response.code}'). " +
                                "No retry will be attempted due to client-side issue."
                    }
                    renameFileToErrorAndCreateLogFile(file, response)
                    false
                }

                else -> {
                    logger.error {
                        "Failed to sync file '${file.fileName}', retry will be attempted in '${cdrClientConfig.retryDelay[retryCount]}' - " +
                                "'${response.message}': ${response.body?.string() ?: "no response body"}"
                    }
                    delay(cdrClientConfig.retryDelay[retryCount].toMillis())
                    true
                }
            }
        } else {
            logger.error { "Unknown HTTP response code retrieved ('${response.code}') - couldn't evaluate HttpStatus" }
            true
        }
    }

    /**
     * For an error case and to prevent a failed file to be uploaded again it renames the file to '.error' and creates a file with the response body.
     */
    private fun renameFileToErrorAndCreateLogFile(file: Path, response: Response) {
        val errorFile = file.resolveSibling("${file.fileName}.error")
        val logFile = file.resolveSibling("${file.fileName}.response")
        if (!file.toFile().renameTo(errorFile.toFile())) {
            logger.error { "Failed to rename file '${file.fileName}'" }
        }
        response.body?.byteStream()
            ?.use { inputStream -> Files.write(logFile, inputStream.readBytes(), StandardOpenOption.APPEND, StandardOpenOption.CREATE) }
    }

    private fun createPostRequest(requestBody: RequestBody, to: URL, headers: Headers): Request =
        Request.Builder()
            .url(to)
            .headers(headers)
            .post(requestBody)
            .build()

}
