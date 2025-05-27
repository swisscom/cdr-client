package com.swisscom.health.des.cdr.client.ui.data

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URL
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class CdrClientApiClient {

    suspend fun shutdownClientServiceProcess(): ShutdownResult = withContext(Dispatchers.IO) {
        logger.info { "BEGIN - Send command to shut down the client service" }
        runCatching {
            HttpClient
                .configure()
                .newCall(
                    HttpClient.get(SHUTDOWN_URL.toHttpUrl().toUrl())
                )
                .execute()
                .use { response: Response ->
                    if (response.isSuccessful) {
                        response.body.use { body ->
                            logger.trace { "Response body: ${body?.string()}" }
                        }
                        logger.info { "END success - Send command to shut down the client service" }
                        ShutdownResult.Success()
                    } else {
                        logger.error {
                            "END failed- Send command to shut down the client service; code: " +
                                    "[${response.code}]; body: [${response.body.use { it.toString() }}]"
                        }
                        ShutdownResult.Failure(Throwable("HTTP error: code: [${response.code}]; body; [${response.body.use { it.toString() }}]"))
                    }
                }
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                logger.error { "END failed - Send command to shut down the client service; error: [$error]" }
                ShutdownResult.Failure(error)
            }
        )
    }

    companion object {
        private const val CDR_CLIENT_BASE_URL = "http://localhost:8191/api"
        private const val SHUTDOWN_URL = "$CDR_CLIENT_BASE_URL/shutdown?reason=configurationChange"
    }

}


sealed class ShutdownResult {
    class Success : ShutdownResult()
    class Failure(val exception: Throwable) : ShutdownResult()
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
