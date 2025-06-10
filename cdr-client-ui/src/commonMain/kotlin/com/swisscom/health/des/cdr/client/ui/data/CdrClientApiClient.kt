package com.swisscom.health.des.cdr.client.ui.data

import com.swisscom.health.des.cdr.client.common.DTOs
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

internal class CdrClientApiClient {

    sealed interface Result<U> {
        data class Success<T>(val response: T) : Result<T>
        data class IOError<Nothing>(val errors: Map<String, Any>) : Result<Nothing>
        data class ValidationError<Nothing>(val errors: Map<String, Any>) : Result<Nothing>
    }

    suspend fun getClientServiceConfiguration(): Result<DTOs.CdrClientConfig> =
        getAnything<DTOs.CdrClientConfig>(CDR_CLIENT_CONFIG_URL, "Get client service configuration")

    suspend fun updateClientServiceConfiguration(config: DTOs.CdrClientConfig): Result<DTOs.CdrClientConfig> =
        putAnything<DTOs.CdrClientConfig>(CDR_CLIENT_CONFIG_URL, config, "Update client service configuration")

    suspend fun shutdownClientServiceProcess(): Result<DTOs.ShutdownResponse> =
        getAnything<DTOs.ShutdownResponse>(SHUTDOWN_URL, "Send command to shut down the client service")

    // TODO: Make condition for retry configurable, so that it can be used for any response type;
    //  then add retry to getAnything() and use it here as well.
    suspend fun getClientServiceStatus(retryStrategy: RetryStrategy): DTOs.StatusResponse.StatusCode = withContext(Dispatchers.IO) {
        // logging on DEBUG level as this method gets called every second
        retryStrategy.apply { retryCount ->
            logger.debug { "BEGIN - Get client service status; retry count '$retryCount'" }
            runCatching {
                HttpClient
                    .configure()
                    .newCall(
                        HttpClient.get(STATUS_URL)
                    )
                    .execute()
                    .use { response: Response ->
                        val responseString: String = response.body
                            .use { body ->
                                body?.string() ?: ""
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

    private suspend inline fun <reified T> getAnything(url: URL, action: String): Result<T> = withContext(Dispatchers.IO) {
        logger.info { "BEGIN - $action" }
        runCatching {
            HttpClient
                .configure()
                .newCall(
                    HttpClient.get(url)
                )
                .execute()
                .use { response: Response ->
                    val responseString: String = response.body
                        .use { body ->
                            body?.string() ?: ""
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
                        Result.ValidationError(mapOf("statusCode" to response.code, "body" to responseString))
                    }
                }
        }.getOrElse { error ->
            logger.info { "END failed - $action; error: '$error'" }
            Result.IOError(mapOf("exception" to error))
        }
    }

    private suspend inline fun <reified T> putAnything(url: URL, body: T, action: String): Result<T> = withContext(Dispatchers.IO) {
        logger.info { "BEGIN - $action" }
        runCatching {
            HttpClient
                .configure()
                .newCall(
                    HttpClient.put(url, JSON.encodeToString(body))
                )
                .execute()
                .use { response: Response ->
                    val responseString: String = response.body
                        .use { body ->
                            body?.string() ?: ""
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
                            Result.ValidationError(problemPropertiesMap)
                        }.getOrElse { exception ->
                            logger.debug {
                                "failed to parse response body as problem json with a properties map; response: '$responseString', exception: $exception"
                            }
                            Result.ValidationError(mapOf("statusCode" to response.code, "body" to responseString))
                        }
                    }
                }
        }.getOrElse { error ->
            logger.info { "END failed - $action; error: '$error'" }
            Result.IOError(mapOf("exception" to error))
        }
    }

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
        private val SHUTDOWN_URL = "$CDR_CLIENT_BASE_URL/shutdown?reason=configurationChange".toHttpUrl().toUrl()

        @JvmStatic
        private val STATUS_URL = "$CDR_CLIENT_BASE_URL/status".toHttpUrl().toUrl()

        @JvmStatic
        private val CDR_CLIENT_CONFIG_URL = "$CDR_CLIENT_BASE_URL/service-configuration".toHttpUrl().toUrl()

        @JvmStatic
        private val JSON = Json {}
    }

}


private object HttpClient : OkHttpClient() {

    fun configure(): OkHttpClient = newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun get(to: URL): Request {
        return Request.Builder()
            .url(to)
            .get()
            .build()
    }

    fun put(to: URL, body: String): Request {
        return Request.Builder()
            .url(to)
            .put(body.toRequestBody())
            .header("Content-Type", "application/json")
            .build()
    }

    const val CONNECT_TIMEOUT_SECONDS: Long = 1L
    const val READ_TIMEOUT_SECONDS: Long = 1L

}
