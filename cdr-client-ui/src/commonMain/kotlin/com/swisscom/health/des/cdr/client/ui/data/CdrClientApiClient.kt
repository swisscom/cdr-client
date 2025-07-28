package com.swisscom.health.des.cdr.client.ui.data

import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.ui.data.HttpClient.MEDIA_TYPE_APPLICATION_JSON
import com.swisscom.health.des.cdr.client.ui.data.HttpClient.addQueryParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Client for the CDR Client service API.
 *
 * This client provides methods to interact with the CDR Client service, including validation of values,
 * directory checks, configuration retrieval and updates, and service status checks. Details about failed
 * validations are of type [DTOs.ValidationMessageKey] to facilitate localization of the validation error
 * message in the UI. Either the message keys are the returned result or they are wrapped in a
 * [DTOs.ValidationResult.Failure] instance.
 */
internal class CdrClientApiClient {

    sealed interface Result<U> {
        data class Success<T>(val response: T) : Result<T>
        data class IOError<Nothing>(val errors: Map<String, Any>) : Result<Nothing>
        data class ServiceError<Nothing>(val errors: Map<String, Any>) : Result<Nothing>
    }

    /**
     * Validates that the given value is not blank.
     *
     * @param value The value to validate.
     * @return A [Result] containing a list of validation message keys if the value is blank, or an empty
     * list if it is valid.
     */
    suspend fun validateValueIsNotBlank(value: String?): Result<List<DTOs.ValidationMessageKey>> =
        getAnything<List<DTOs.ValidationMessageKey>>(
            CDR_CLIENT_VALIDATE_VALUE_NOT_BLANK_AND_NOT_PLACEHOLDER.addQueryParams(
                "value" to value
            ),
            "Validate value is not blank"
        )

    /**
     * Validates the given directory.
     *
     * @param config The CDR client configuration as currently held by the CDR Client ui.
     * @param directory The directory to validate.
     * @param validations The list of [validations][DomainObjects.ValidationType] to perform on the directory.
     * @return A [Result] containing a [DTOs.ValidationResult] with the results of the validations.
     */
    suspend fun validateDirectory(
        config: DTOs.CdrClientConfig,
        directory: String?,
        validations: List<DomainObjects.ValidationType>
    ): Result<DTOs.ValidationResult> =
        putAnything<DTOs.CdrClientConfig, DTOs.ValidationResult>(
            CDR_CLIENT_VALIDATE_DIRECTORY_URL
                .run {
                    addQueryParams(
                        "dir" to directory
                    )
                }.run {
                    addQueryParams(*(validations.map { validation -> "validation" to validation.name }.toTypedArray()))
                },
            config,
            "Validate directory is read/writable"
        )

    /**
     * Retrieves the current client service configuration.
     *
     * @return A [Result] containing the [DTOs.CdrClientConfig]
     */
    suspend fun getClientServiceConfiguration(): Result<DTOs.CdrClientConfig> =
        getAnything<DTOs.CdrClientConfig>(CDR_CLIENT_CONFIG_URL, "Get client service configuration")

    /**
     * Updates the CDR Client service configuration.
     *
     * @param config The new configuration to set.
     * @return A [Result] containing that same configuration if it was successfully persisted, or an
     * error if the update failed.
     */
    suspend fun updateClientServiceConfiguration(config: DTOs.CdrClientConfig): Result<DTOs.CdrClientConfig> =
        putAnything<DTOs.CdrClientConfig, DTOs.CdrClientConfig>(CDR_CLIENT_CONFIG_URL, config, "Update client service configuration")

    /**
     * Sends a command to the CDR client service to shut itself down. (The platform's service control
     * process is responsible for restarting the service.)
     *
     * @return A [Result] containing a [DTOs.ShutdownResponse] if the command was successfully sent,
     * or an error if the request failed.
     */
    suspend fun shutdownClientServiceProcess(): Result<DTOs.ShutdownResponse> =
        getAnything<DTOs.ShutdownResponse>(SHUTDOWN_URL, "Send command to shut down the client service")

    /**
     * Retrieves the current [status][DTOs.StatusResponse.StatusCode] of the CDR Client service.
     *
     * @param retryStrategy The [RetryStrategy] to use for retrying the request in case of an IO error.
     * @return The current [status][DTOs.StatusResponse.StatusCode] of the CDR Client service.
     */
    // TODO: Make condition for retry configurable, so that it can be used for any response type;
    //  then add retry to getAnything() and use it here as well.
    suspend fun getClientServiceStatus(retryStrategy: RetryStrategy): DTOs.StatusResponse.StatusCode = withContext(Dispatchers.IO) {
        // logging on DEBUG level as this method gets called every second
        retryStrategy.apply { retryCount ->
            logger.debug { "BEGIN - Get client service status; retry count '$retryCount'" }
            runCatching {
                HttpClient
                    .INSTANCE
                    .newCall(
                        HttpClient.get(STATUS_URL)
                    )
                    .execute()
                    .use { response: Response ->
                        val responseString: String = response.body
                            .use { body ->
                                body?.string() ?: EMPTY_STRING
                            }.also {
                                logger.trace { "Response body: '$it'" }
                            }

                        if (response.isSuccessful) {
                            val statusResponse = JSON.decodeFromString<DTOs.StatusResponse>(responseString)
                            logger.debug { "END success - Get client service status; retry count '$retryCount'" }
                            statusResponse.statusCode
                        } else {
                            logger.debug {
                                "END failed - Get client service status; retry count '$retryCount'; code: '${response.code}'; body: '$responseString'"
                            }
                            DTOs.StatusResponse.StatusCode.ERROR
                        }
                    }
            }.getOrElse { error ->
                logger.debug { "END failed - Get client service status; retry count '$retryCount'; error: '$error'" }
                DTOs.StatusResponse.StatusCode.OFFLINE
            }
        }
    }

    /**
     * Generic method to perform a GET request to the CDR Client service API.
     *
     * @param url The URL to send the request to.
     * @param action A description of the action being performed, used for logging.
     * @return A [Result] containing the parsed response of type [T] if the request was successful,
     * or an error if the request failed.
     */
    private suspend inline fun <reified T> getAnything(
        url: HttpUrl,
        action: String,
    ): Result<T> = withContext(Dispatchers.IO) {
        logger.info { "BEGIN - $action" }
        runCatching {
            HttpClient
                .INSTANCE
                .newCall(
                    HttpClient.get(url)
                )
                .execute()
                .use { response: Response ->
                    val responseString: String = response.body
                        .use { body ->
                            body?.string() ?: EMPTY_STRING
                        }.also {
                            logger.trace { "raw response body: '$it'" }
                        }

                    if (response.isSuccessful) {
                        val result = JSON.decodeFromString<T>(responseString)
                        logger.trace { "parsed response body: '$result'" }
                        logger.info { "END success - $action" }
                        Result.Success(result)
                    } else {
                        logger.info {
                            "END failed - $action; code: '${response.code}'; body: '$responseString'"
                        }
                        Result.ServiceError(mapOf("statusCode" to response.code, "body" to responseString))
                    }
                }
        }.getOrElse { error ->
            logger.info { "END failed - $action; error: '$error'" }
            Result.IOError(mapOf("exception" to error))
        }
    }

    /**
     * Generic method to perform a PUT request to the CDR Client service API with a request body.
     *
     * @param url The URL to send the request to.
     * @param body The request body to send; must be [JSON][Json] serializable
     * @param action A description of the action being performed, used for logging.
     * @return A [Result] containing the parsed response of type [T] if the request was successful,
     * or an error if the request failed.
     * @see [kotlinx.serialization.Serializable]
     */
    private suspend inline fun <reified V, reified T> putAnything(url: HttpUrl, body: V, action: String): Result<T> = withContext(Dispatchers.IO) {
        logger.info { "BEGIN - $action" }
        logger.trace { "request body: '$body'" }
        runCatching {
            HttpClient
                .INSTANCE
                .newCall(
                    HttpClient.put(url, JSON.encodeToString(body), MEDIA_TYPE_APPLICATION_JSON)
                )
                .execute()
                .use { response: Response ->
                    val responseString: String = response.body
                        .use { body ->
                            body?.string() ?: EMPTY_STRING
                        }.also {
                            logger.trace { "Response body: '$it'" }
                        }

                    if (response.isSuccessful) {
                        val result = JSON.decodeFromString<T>(responseString)
                        logger.info { "END success - $action" }
                        Result.Success(result)
                    } else {
                        logger.info {
                            "END failed - $action; code: '${response.code}'; body: '$responseString'"
                        }
                        runCatching {
                            JSON.parseToJsonElement(responseString).jsonObject
                        }.mapCatching { problemJson ->
                            problemJson.entries.filter { (key, _) -> key != "status" && key != "message" && key != "title" && key != "type" }
                        }.mapCatching { problemProperties ->
                            problemProperties.associate { it.key to it.value.jsonPrimitive.content }
                        }.mapCatching<Result<T>, Map<String, String>> { problemPropertiesMap ->
                            Result.ServiceError(problemPropertiesMap)
                        }.getOrElse { exception ->
                            logger.debug {
                                "failed to parse response body as problem json with a properties map; response: '$responseString', exception: $exception"
                            }
                            Result.ServiceError(mapOf("statusCode" to response.code, "body" to responseString))
                        }
                    }
                }
        }.getOrElse { error ->
            logger.info { "END failed - '$action'; error: '$error'" }
            Result.IOError(mapOf("exception" to error))
        }
    }

    /**
     * Retry strategies to wrap the remote call in a retry loop. Unless the [RetryStrategy.NONE]
     * is used, the call will be retried in case the call yields the remote status
     * [DTOs.StatusResponse.StatusCode.OFFLINE].
     *
     * @see [DTOs.StatusResponse.StatusCode]
     */
    enum class RetryStrategy {
        NONE,
        LINEAR,
        EXPONENTIAL;

        suspend inline fun apply(block: suspend (retryCount: Int) -> DTOs.StatusResponse.StatusCode): DTOs.StatusResponse.StatusCode =
            when (this) {
                NONE -> block(0)
                LINEAR -> retry(initialDelay = 1.seconds, factor = 1.0, times = 25, block = block)
                EXPONENTIAL -> retry(initialDelay = 100.milliseconds, factor = 2.0, times = 8, block = block)
            }

        private suspend inline fun retry(
            times: Int,
            initialDelay: Duration,
            maxDelay: Duration = 10.seconds,
            factor: Double,
            block: suspend (Int) -> DTOs.StatusResponse.StatusCode
        ): DTOs.StatusResponse.StatusCode {
            var currentDelay: Duration = initialDelay
            repeat(times - 1) { retryCount ->
                val result = block(retryCount)
                if (result != FAILED_STATUS_CODE) {
                    return result
                } else {
                    logger.debug { "CDR client service is '${FAILED_STATUS_CODE}', retrying after '$currentDelay'" }
                }
                delay(currentDelay)
                currentDelay = (currentDelay * factor).coerceAtMost(maxDelay)
            }
            return block(times - 1) // last attempt
        }

        companion object {
            @JvmStatic
            private val FAILED_STATUS_CODE = DTOs.StatusResponse.StatusCode.OFFLINE
        }

    }


    companion object {
        private const val CDR_CLIENT_BASE_URL = "http://localhost:8191/api"

        @JvmStatic
        private val SHUTDOWN_URL = "$CDR_CLIENT_BASE_URL/shutdown?reason=configurationChange".toHttpUrl()

        @JvmStatic
        private val STATUS_URL = "$CDR_CLIENT_BASE_URL/status".toHttpUrl()

        @JvmStatic
        private val CDR_CLIENT_CONFIG_URL = "$CDR_CLIENT_BASE_URL/service-configuration".toHttpUrl()

        @JvmStatic
        private val CDR_CLIENT_VALIDATE_DIRECTORY_URL = "$CDR_CLIENT_BASE_URL/validate-directory".toHttpUrl()

        @JvmStatic
        private val CDR_CLIENT_VALIDATE_VALUE_NOT_BLANK_AND_NOT_PLACEHOLDER = "$CDR_CLIENT_BASE_URL/validate-not-blank-and-not-placeholder".toHttpUrl()

        @JvmStatic
        private val JSON = Json {}
    }

}

/**
 * Singleton wrapper around an [OkHttpClient] instance to provide convenience methods for creating
 * requests and adding query parameters.
 */
private object HttpClient {

    const val CONNECT_TIMEOUT_SECONDS: Long = 1L
    const val READ_TIMEOUT_SECONDS: Long = 1L

    @JvmStatic
    val MEDIA_TYPE_APPLICATION_JSON = "application/json".toMediaType()

    @JvmStatic
    val INSTANCE: OkHttpClient = configure()

    private fun configure(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun get(to: HttpUrl): Request {
        return Request.Builder()
            .url(to)
            .get()
            .build()
    }

    fun put(to: HttpUrl, body: String, mediaType: MediaType): Request {
        return Request.Builder()
            .url(to)
            .put(body.toRequestBody(mediaType))
            .build()
    }

    fun HttpUrl.addQueryParams(vararg queryParams: Pair<String, String?>): HttpUrl =
        this.newBuilder().apply {
            queryParams.forEach { (key, value) ->
                addQueryParameter(key, value)
            }
        }.build()

}
