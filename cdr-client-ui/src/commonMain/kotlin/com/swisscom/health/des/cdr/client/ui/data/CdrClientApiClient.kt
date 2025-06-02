package com.swisscom.health.des.cdr.client.ui.data

import com.swisscom.health.des.cdr.client.common.DTOs
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URL
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

internal class CdrClientApiClient {

    suspend fun shutdownClientServiceProcess(): ShutdownResult = withContext(Dispatchers.IO) {
        logger.info { "BEGIN - Send command to shut down the client service" }
        runCatching {
            HttpClient
                .configure()
                .newCall(
                    HttpClient.get(SHUTDOWN_URL)
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
                        val shutdownResponse = JSON.decodeFromString<DTOs.ShutdownResponse>(responseString)
                        logger.info { "END success - Send command to shut down the client service - scheduled for '${shutdownResponse.shutdownScheduledFor}'" }
                        ShutdownResult.Success()
                    } else {
                        logger.error {
                            "END failed - Send command to shut down the client service; code: " +
                                    "'${response.code}'; body: '$responseString'"
                        }
                        ShutdownResult.Failure()
                    }
                }
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                logger.error { "END failed - Send command to shut down the client service; error: '$error'" }
                ShutdownResult.Failure()
            }
        )
    }

    suspend fun getClientServiceStatus(): DTOs.StatusResponse.StatusCode = withContext(Dispatchers.IO) {
        // logging on DEBUG level as this method gets called every second
        logger.debug { "BEGIN - Get client service status" }
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
                        val responseJson = JSON.decodeFromString<DTOs.StatusResponse>(responseString)
                        logger.debug { "END success - Get client service status" }
                        responseJson.statusCode
                    } else {
                        logger.debug {
                            "END failed - Get client service status; code: '${response.code}'; body: '$responseString'"
                        }
                        DTOs.StatusResponse.StatusCode.ERROR
                    }
                }
        }.getOrElse { error ->
            logger.debug { "END failed - Get client service status; error: '$error'" }
            DTOs.StatusResponse.StatusCode.OFFLINE
        }
    }

    companion object {
        private const val CDR_CLIENT_BASE_URL = "http://localhost:8191/api"

        @JvmStatic
        private val SHUTDOWN_URL = "$CDR_CLIENT_BASE_URL/shutdown?reason=configurationChange".toHttpUrl().toUrl()

        @JvmStatic
        private val STATUS_URL = "$CDR_CLIENT_BASE_URL/status".toHttpUrl().toUrl()

        @JvmStatic
        private val JSON = Json {}
    }

}

internal sealed class ShutdownResult {
    class Success : ShutdownResult()
    class Failure : ShutdownResult()
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

    const val CONNECT_TIMEOUT_SECONDS: Long = 1L
    const val READ_TIMEOUT_SECONDS: Long = 1L

}
