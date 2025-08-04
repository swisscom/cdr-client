package com.swisscom.health.des.cdr.client.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IAuthenticationResult
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.getRootestCause
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.HttpServerErrorException
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.retry.RetryContext
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.util.UriComponentsBuilder
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

private val logger = KotlinLogging.logger {}

@Suppress("TooManyFunctions")
@Service
internal class CdrApiClient(
    private val cdrClientConfig: CdrClientConfig,
    private val httpClient: OkHttpClient,
    private val clientCredentialParams: ClientCredentialParameters,
    @param:Qualifier("retryIoAndServerErrors")
    private val retryIOExceptionsAndServerErrors: RetryTemplate,
    private val securedApp: IConfidentialClientApplication,
    private val objectMapper: ObjectMapper
) {

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun renewClientCredential(traceId: String): RenewClientSecretResult = runCatching {
        logger.debug { "Renewing client secret" }
        val accessToken = getAccessToken()
        retryIOExceptionsAndServerErrors.execute<Response, Exception> { retry: RetryContext ->
            if (retry.retryCount > 0) {
                logger.debug {
                    "Operation targeting '${buildClientCredentialUrl(cdrClientConfig.idpCredentials.clientId.id)}' has failed; exception: " +
                            "'${retry.lastThrowable?.let { getRootestCause(it)::class.java }}'; message: '${retry.lastThrowable?.message}'"
                }
                logger.info {
                    "Retry attempt '#${retry.retryCount}' of 'renew client credential' operation targeting " +
                            "'${buildClientCredentialUrl(cdrClientConfig.idpCredentials.clientId.id)}'"
                }
            }
            httpClient.newCall(
                Request.Builder()
                    .url(buildClientCredentialUrl(cdrClientConfig.idpCredentials.clientId.id))
                    .headers(
                        Headers.Builder().run {
                            this[AZURE_TRACE_ID_HEADER] = traceId
                            if (accessToken.isNotBlank()) {
                                this[HttpHeaders.AUTHORIZATION] = "Bearer $accessToken"
                            }
                            this.build()
                        }
                    )
                    // unfortunately, okHttp requires a non-null body for patch/post requests
                    // https://github.com/square/okhttp/issues/7005
                    .patch(byteArrayOf().toRequestBody())
                    .build()
            ).execute()
        }.use { response: Response ->
            /*
             * {
             *   "id": "25944ef9-adc5-47f7-b607-9e85c572e576",
             *   "displayName": "cdr client #1 by client id",
             *   "clientId": "eb34fc22-f68c-4b7f-8774-e5dc943cd0b1",
             *   "clientSecret": "Placeholder_eWN8Q~MkHkK4vI.97tH2S65AQcP4qBsg2KzBcbSy",
             *   "notOnOrAfter": "2026-11-13T17:32:00.1851404Z",
             *   "orgId": "uuid-in-real-life",
             *   "warnings": []
             * }
             */
            when {
                response.isSuccessful -> {
                    requireNotNull(response.header(HttpHeaders.CONTENT_TYPE)) {
                        "Client credential renewal response has no content type"
                    }.let { mediaTypeString ->
                        require(MediaType.parseMediaType("application/*+json").isCompatibleWith(MediaType.parseMediaType(mediaTypeString))) {
                            "Client credential renewal response is not a JSON content type; received content type: '$mediaTypeString'"
                        }
                    }

                    val appRegistration = response.body?.let { body ->
                        objectMapper.readValue(body.byteStream(), CdrClientAppRegistrationDto::class.java)
                    } ?: CdrClientAppRegistrationDto.EMPTY

                    appRegistration.warnings.forEach { warning ->
                        logger.warn { "Client secret renewal server-side warning: '$warning'" }
                    }

                    require(cdrClientConfig.idpCredentials.clientId.id == appRegistration.clientId) {
                        "Client id in credential renewal response does not match local client id; " +
                                "local: '${cdrClientConfig.idpCredentials.clientId}', received: '${appRegistration.clientId}'"
                    }

                    val clientSecret = appRegistration.clientSecret
                    RenewClientSecretResult.Success(
                        clientId = cdrClientConfig.idpCredentials.clientId.id,
                        clientSecret = clientSecret,
                    ).also { _ ->
                        logger.debug { "Renewing client secret done" }
                    }
                }

                else ->
                    RenewClientSecretResult.RenewHttpErrorResponse(
                        code = response.code,
                        responseBody = response.body?.string() ?: "no response body"
                    ).also { result ->
                        logger.warn { "Renewing client secret encountered client error; status code: '${result.code}'" }
                        logger.trace { "Response body: '${result.responseBody}'" }
                    }
            }
        }
    }.fold(
        onSuccess = { it },
        onFailure = { t ->
            when (t) {
                is HttpServerErrorException -> {
                    RenewClientSecretResult.RenewHttpErrorResponse(
                        code = t.statusCode,
                        responseBody = t.responseBody
                    ).also {
                        logger.warn { "Renewing client secret encountered server error; status code: '${t.statusCode}'" }
                        logger.trace { "Response body: '${t.responseBody}'" }
                    }
                }

                else -> {
                    logger.error { "Renewing client secret for client id '${cdrClientConfig.idpCredentials.clientId}' failed: '$t'" }
                    RenewClientSecretResult.RenewError(message = t.message ?: "Unknown error", cause = t)
                }
            }
        }
    )


    /**
     * Sends a POST-Request to the CDR API with a document as the body.
     *
     * @param contentType the content type of the document; currently only `application/forumdatenaustausch+xml;charset=UTF-8`
     * @param file the file to upload
     * @param connectorId the connector to upload the file for
     * @param mode the processing mode configured on the connector, which has to match the processing mode of the document for it to be accepted
     * @param traceId the trace id to propagate upstream for log correlation
     * @return the result of the HTTP post translated into a [UploadDocumentResult]
     */
    @Suppress("MagicNumber")
    fun uploadDocument(contentType: String, file: Path, connectorId: String, mode: CdrClientConfig.Mode, traceId: String): UploadDocumentResult =
        runCatching {
            logger.debug { "Upload '$file' start" }
            httpClient
                .newCall(
                    createPostRequest(
                        file.readBytes().toRequestBody(contentType.toMediaType()),
                        buildDocumentTargetUrl(cdrClientConfig.cdrApi.basePath),
                        buildBaseHeaders(connectorId, mode, traceId)
                    )
                )
                .execute()
                .use { response: Response ->
                    when {
                        response.isSuccessful -> UploadDocumentResult.Success.also { _ ->
                            logger.debug { "Upload '$file' done" }
                        }

                        response.code in 400..499 -> UploadDocumentResult.UploadClientErrorResponse(
                            code = response.code,
                            responseBody = response.body?.string() ?: "no response body"
                        ).also { result ->
                            logger.info { "Upload '$file' encountered client error: '$result'" }
                        }

                        else -> UploadDocumentResult.UploadServerErrorResponse(
                            code = response.code,
                            responseBody = response.body?.string() ?: "no response body"
                        ).also { result ->
                            logger.info { "Upload '$file' encountered server error: '$result'" }
                        }
                    }
                }
        }.fold(
            onSuccess = { it },
            onFailure = { t ->
                logger.error { "Upload file '$file' failed: ${t.message}" }
                UploadDocumentResult.UploadError(message = t.message ?: "Unknown error", t = t)
            }
        )

    /**
     * Sends a GET-Request to the CDR API to request a file from the download queue of the specified connector.
     * This is the first step in the "download and confirm" document retrieval process. If the request did yield
     * a document, then the response object returned by the method contains the path to the downloaded file and
     * the document ID required to confirm the download.
     *
     * @param connectorId the connector to request a file for
     * @param mode the processing mode for the request
     * @param traceId the trace id to propagate upstream
     * @return the result of the HTTP get translated into a [DownloadDocumentResult]
     * @see acknowledgeDocumentDownload
     */
    fun downloadDocument(connectorId: String, mode: CdrClientConfig.Mode, traceId: String): DownloadDocumentResult = runCatching {
        logger.debug { "Request file start" }
        val queryParameters = LinkedMultiValueMap<String, String>().apply {
            add("limit", "1")
        }
        httpClient
            .newCall(
                createGetRequest(
                    buildDocumentTargetUrl(cdrClientConfig.cdrApi.basePath, queryParameters),
                    buildBaseHeaders(connectorId = connectorId, mode = mode, traceId = traceId)
                )
            )
            .execute()
            .use { response: Response ->
                when {
                    response.isNoContentFound() -> DownloadDocumentResult.NoDocumentPending.also { _ ->
                        logger.debug { "Request file done - no document pending" }
                    }

                    response.isSuccessWithContent() -> {
                        val pullResultId: String = requireNotNull(response.header(PULL_RESULT_ID_HEADER)) { error("No pull result id found in response") }
                        val tmpFile: Path = cdrClientConfig.localFolder.path.resolve("$pullResultId.tmp")
                            .apply {
                                outputStream().use { os ->
                                    response.body!!.byteStream().use { iss -> iss.copyTo(os) }
                                }
                            }

                        DownloadDocumentResult.DownloadSuccess(
                            pullResultId = pullResultId,
                            file = tmpFile
                        ).also { _ ->
                            logger.debug { "Request file done - file stream opened" }
                        }
                    }

                    else -> DownloadDocumentResult.Error(
                        code = response.code,
                        message = response.body?.string() ?: "no response body"
                    ).also { result ->
                        logger.warn { "Request file encountered error: '$result'" }
                    }
                }
            }
    }.fold(
        onSuccess = { it },
        onFailure = { t ->
            logger.error { "Request file failed: $t" }
            DownloadDocumentResult.Error(message = t.message ?: "Unknown error", t = t)
        }
    )

    /**
     * Sends a DELETE-Request to the CDR API to acknowledge the download of a document. This is the second step in the
     * "download and confirm" document retrieval process. The download ID is the ID of the document that was downloaded
     * in the first step.
     *
     * @param connectorId the connector to acknowledge the download for
     * @param mode the processing mode for the request, must match the mode for which the document was downloaded in the previous step
     * @param downloadId the ID of the document that was downloaded in the previous step
     * @param traceId the trace id to propagate upstream for log correlation
     * @return the result of the HTTP delete translated into a [DownloadDocumentResult]
     * @see downloadDocument
     */
    fun acknowledgeDocumentDownload(connectorId: String, mode: CdrClientConfig.Mode, downloadId: String, traceId: String): DownloadDocumentResult =
        runCatching {
            logger.debug { "Acknowledging pulled file with id '$downloadId' start" }
            httpClient.newCall(
                createDeleteRequest(
                    buildDocumentTargetUrl("${cdrClientConfig.cdrApi.basePath}/$downloadId"),
                    buildBaseHeaders(connectorId = connectorId, mode = mode, traceId = traceId)
                )
            ).execute().use { response: Response ->
                when {
                    response.isSuccessWithContent() -> DownloadDocumentResult.AcknowledgeSuccess(
                        pullResultId = downloadId
                    ).also { _ ->
                        logger.debug { "Pulled file with id '$downloadId' acknowledged" }
                    }

                    else -> DownloadDocumentResult.Error(
                        code = response.code,
                        message = response.body?.string() ?: "no response body"
                    ).also { result ->
                        logger.info { "Acknowledging pulled file with id '$downloadId' encountered a http error: '$result'" }
                    }
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { t ->
                logger.error { "Acknowledging pulled file with id '$downloadId' failed: '$t'" }
                DownloadDocumentResult.Error(message = t.message ?: "Unknown error", t = t)
            }
        )

    /**
     * Builds a target URL from an endpoint and a path.
     *
     * @param path the path to add to the URL
     * @return the resulting URL
     */
    private fun buildDocumentTargetUrl(path: String, queryParameters: MultiValueMap<String, String> = LinkedMultiValueMap()): URL {
        return UriComponentsBuilder
            .newInstance()
            .scheme(cdrClientConfig.cdrApi.scheme)
            .host(cdrClientConfig.cdrApi.host.fqdn)
            .port(cdrClientConfig.cdrApi.port)
            .path(path)
            .queryParams(queryParameters)
            .build()
            .toUri()
            .toURL()
    }

    private fun buildClientCredentialUrl(clientId: String): URL {
        return UriComponentsBuilder
            .newInstance()
            .scheme(cdrClientConfig.credentialApi.scheme)
            .host(cdrClientConfig.credentialApi.host.fqdn)
            .port(cdrClientConfig.credentialApi.port)
            .pathSegment(cdrClientConfig.credentialApi.basePath, clientId)
            .build()
            .toUri()
            .toURL()
    }

    /**
     * Build headers with connector-id, access token, processing mode and trace id.
     */
    private fun buildBaseHeaders(connectorId: String, mode: CdrClientConfig.Mode, traceId: String): Headers {
        val accessToken = getAccessToken()

        return Headers.Builder().run {
            this[CONNECTOR_ID_HEADER] = connectorId
            this[CDR_PROCESSING_MODE_HEADER] = mode.value
            this[AZURE_TRACE_ID_HEADER] = traceId
            this[CLIENT_TYPE_HEADER] = clientType ?: "cdr-client"
            this[CLIENT_VERSION_HEADER] = clientVersion ?: "unknown"
            if (accessToken.isNotBlank()) {
                this[HttpHeaders.AUTHORIZATION] = "Bearer $accessToken"
            }
            this.build()
        }
    }

    /**
     * Checks if an HTTP response is successful.
     *
     * @return whether the response is a success or not
     */
    private fun Response.isSuccessWithContent(): Boolean = this.isSuccessful && this.body != null

    /**
     * Checks if the HTTP response is of status code 204 (NO_CONTENT).
     */
    private fun Response.isNoContentFound(): Boolean = this.code == HttpStatus.NO_CONTENT.value()

    private fun createPostRequest(requestBody: RequestBody, to: URL, headers: Headers): Request =
        Request.Builder()
            .url(to)
            .headers(headers)
            .post(requestBody)
            .build()

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
     *
     */
    private fun getAccessToken(): String = runCatching {
        retryIOExceptionsAndServerErrors.execute<String, Exception> { retry: RetryContext ->
            if (retry.retryCount > 0) {
                logger.debug {
                    "Operation targeting '${cdrClientConfig.idpEndpoint}' has failed; exception: " +
                            "'${retry.lastThrowable?.let { getRootestCause(it)::class.java }}'; message: '${retry.lastThrowable?.message}'"
                }
                logger.info {
                    "Retry attempt '#${retry.retryCount}' of 'get access token' operation targeting " +
                            "'${cdrClientConfig.idpEndpoint}'"
                }
            }
            val authResult: IAuthenticationResult = securedApp.acquireToken(clientCredentialParams).get()
            logger.debug { "Token taken from ${authResult.metadata().tokenSource()}" }
            authResult.accessToken()
        }
    }.fold(
        onSuccess = { token: String -> token },
        onFailure = { e ->
            logger.error { "Failed to get access token: $e" }
            EMPTY_STRING
        }
    )

    companion object {
        const val CONNECTOR_ID_HEADER = "cdr-connector-id"
        const val CDR_PROCESSING_MODE_HEADER = "cdr-processing-mode"
        const val AZURE_TRACE_ID_HEADER = "x-ms-request-id"
        const val CLIENT_TYPE_HEADER = "SWISSCOM-CLIENT-TYPE"
        const val CLIENT_VERSION_HEADER = "SWISSCOM-CLIENT-VERSION"
        val clientType: String? = CdrApiClient::class.java.`package`.implementationTitle
        val clientVersion: String? = CdrApiClient::class.java.`package`.implementationVersion
    }

    sealed interface DownloadDocumentResult {
        object NoDocumentPending : DownloadDocumentResult
        sealed class Success : DownloadDocumentResult
        data class DownloadSuccess(val pullResultId: String, val file: Path) : Success()
        data class AcknowledgeSuccess(val pullResultId: String) : Success()
        data class Error(val code: Int? = null, val message: String, val t: Throwable? = null) : DownloadDocumentResult
    }

    sealed interface UploadDocumentResult {
        object Success : UploadDocumentResult
        data class UploadClientErrorResponse(val code: Int, val responseBody: String) : UploadDocumentResult
        data class UploadServerErrorResponse(val code: Int, val responseBody: String) : UploadDocumentResult
        data class UploadError(val message: String, val t: Throwable? = null) : UploadDocumentResult
    }

    sealed interface RenewClientSecretResult {
        data class Success(val clientId: String, val clientSecret: String) : RenewClientSecretResult
        data class RenewHttpErrorResponse(val code: Int, val responseBody: String) : RenewClientSecretResult
        data class RenewError(val message: String, val cause: Throwable) : RenewClientSecretResult

    }

    data class CdrClientAppRegistrationDto(
        val id: String = EMPTY_STRING,
        val displayName: String = EMPTY_STRING,
        val clientId: String = EMPTY_STRING,
        val clientSecret: String = EMPTY_STRING,
        val notOnOrAfter: String = EMPTY_STRING,
        val orgId: String = EMPTY_STRING, // org id of the UI user who creates/updates/deletes the app registration
        val warnings: List<String> = emptyList(),
    ) {
        companion object {
            @JvmStatic
            val EMPTY = CdrClientAppRegistrationDto(EMPTY_STRING, EMPTY_STRING, EMPTY_STRING, EMPTY_STRING, EMPTY_STRING, EMPTY_STRING, emptyList())
        }
    }

}
