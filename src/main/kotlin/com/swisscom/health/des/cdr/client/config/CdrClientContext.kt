package com.swisscom.health.des.cdr.client.config

import com.mayakapps.kache.InMemoryKache
import com.mayakapps.kache.KacheStrategy
import com.mayakapps.kache.ObjectKache
import com.microsoft.aad.msal4j.ClientCredentialFactory
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.ConfidentialClientApplication
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import okhttp3.Response
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.cloud.context.config.annotation.RefreshScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.retry.support.RetryTemplate
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * A Spring configuration class for creating and configuring beans used by the CDR client.
 */
@Configuration
class CdrClientContext {

    /**
     * Creates and returns an instance of the OkHttpClient.
     *
     * @param builder The OkHttpClient.Builder used to build the client.
     * @return The fully constructed OkHttpClient.
     */
    @Bean
    fun okHttpClient(
        builder: OkHttpClient.Builder,
        @Value("\${client.connection-timeout-ms}") timeout: Long,
        @Value("\${client.read-timeout-ms}") readTimeout: Long
    ): OkHttpClient =
        builder
            .connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(readTimeout, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val response: Response = chain.proceed(chain.request())

                @Suppress("MagicNumber")
                if (response.code in 500..599) {
                    throw HttpServerErrorException(
                        message = "Received error status code '${response.code}'.",
                        statusCode = response.code,
                        responseBody = response.body?.string() ?: ""
                    )
                }

                response
            }
            .build()

    /**
     * Creates and returns an instance of the OkHttpClient.Builder if one does not already exist.
     *
     * @return The OkHttpClient.Builder instance.
     */
    @Bean
    @ConditionalOnMissingBean
    fun okHttpClientBuilder(): OkHttpClient.Builder? {
        return OkHttpClient.Builder()
    }

    /**
     * Creates a coroutine dispatcher for blocking I/O operations with limited parallelism.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Bean(name = ["limitedParallelismCdrUploadsDispatcher"])
    fun limitedParallelCdrUploadsDispatcher(config: CdrClientConfig): CoroutineDispatcher {
        return Dispatchers.IO.limitedParallelism(config.pushThreadPoolSize)
    }

    /**
     * Creates a coroutine dispatcher for blocking I/O operations with limited parallelism.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Bean(name = ["limitedParallelismCdrDownloadsDispatcher"])
    fun limitedParallelCdrDownloadsDispatcher(config: CdrClientConfig): CoroutineDispatcher {
        return Dispatchers.IO.limitedParallelism(config.pullThreadPoolSize)
    }

    /**
     * Creates a cache to store fully qualified file names of files that are currently being processed
     * in order to avoid a race condition between processing files by the polling and event trigger processes.
     */
    @Bean
    fun processingInProgressCache(config: CdrClientConfig): ObjectKache<String, Path> =
        InMemoryKache(maxSize = config.filesInProgressCacheSize.toBytes())
        {
            strategy = KacheStrategy.LRU
            onEntryRemoved = { evicted: Boolean, key: String, _: Path, _: Path? ->
                if (evicted) {
                    logger.warn {
                        "The file object with key '$key' has been evicted from the processing cache because the capacity limit of the cache " +
                                "has been reached; this indicates a very large number of files in the source directories that cannot be processed. " +
                                "Please investigate. "
                    }
                }
            }
        }

    /**
     * Creates and returns an instance of the MSAL4J client object through which we can obtain an OAuth2 token.
     */
    @RefreshScope
    @Bean
    fun confidentialClientApp(config: CdrClientConfig): IConfidentialClientApplication =
        ConfidentialClientApplication.builder(
            config.idpCredentials.clientId,
            ClientCredentialFactory.createFromSecret(config.idpCredentials.clientSecret)
        ).authority(config.idpEndpoint.toString())
            .build()

    /**
     * Creates and returns an instance of credentials to be used with the MSAL4J client to obtain an OAuth2 token.
     */
    @Bean
    fun clientCredentialParams(config: CdrClientConfig): ClientCredentialParameters =
        ClientCredentialParameters.builder(config.idpCredentials.scopes.toSet()).build()

    /**
     * Creates and returns a spring retry-template that retries on IOExceptions up to three times before bailing out.
     */
    @Bean(name = ["retryIoAndServerErrors"])
    @Suppress("MagicNumber")
    fun retryIOExceptionsAndServerErrorsTemplate(
        @Value("\${client.retry-template.retries}")
        retries: Int,
        @Value("\${client.retry-template.initial-delay}")
        initialDelay: Duration,
        @Value("\${client.retry-template.multiplier}")
        multiplier: Double,
        @Value("\${client.retry-template.max-delay}")
        maxDelay: Duration
    ): RetryTemplate = RetryTemplate.builder()
        .maxAttempts(retries) // 1 initial attempt + retries
        .exponentialBackoff(initialDelay, multiplier, maxDelay, true)
        .retryOn(IOException::class.java)
        .retryOn(HttpServerErrorException::class.java)
        .traversingCauses()
        .build()

}

internal class HttpServerErrorException(message: String, val statusCode: Int, val responseBody: String) : RuntimeException(message)
