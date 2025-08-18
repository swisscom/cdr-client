package com.swisscom.health.des.cdr.client.config

import com.mayakapps.kache.InMemoryKache
import com.mayakapps.kache.KacheStrategy
import com.mayakapps.kache.ObjectKache
import com.microsoft.aad.msal4j.ClientCredentialFactory
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.ConfidentialClientApplication
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.time.delay
import okhttp3.OkHttpClient
import okhttp3.Response
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.retry.support.RetryTemplate
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.fileSize
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

/**
 * A Spring configuration class for creating and configuring beans used by the CDR client.
 */
@Suppress("TooManyFunctions")
@Configuration
internal class CdrClientContext {

    @Bean
    fun otelTracer(otel: OpenTelemetry): Tracer = otel.getTracer("cdr-client-trace-support")

    /**
     * Creates and returns an instance of the OkHttpClient.
     *
     * @param builder The OkHttpClient.Builder used to build the client.
     * @return The fully constructed OkHttpClient.
     */
    @Bean
    fun okHttpClient(
        builder: OkHttpClient.Builder,
        @Value($$"${client.connection-timeout-ms}") timeout: Long,
        @Value($$"${client.read-timeout-ms}") readTimeout: Long
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
                        responseBody = response.body?.string() ?: EMPTY_STRING
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
     *
     * Note: As we use the non-blocking `delay()` to wait for the file-size-growth test and
     * to wait in case a re-try of an upload is required, a lot more files than the configured
     * thread pool size can be enrolled in the upload process at the same time. Only the
     * blocking i/o operations are limited to the configured thread pool size.
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
     * to avoid a race condition between processing files by the polling and event trigger processes.
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
    @Bean
    fun confidentialClientApp(config: CdrClientConfig): IConfidentialClientApplication =
        ConfidentialClientApplication.builder(
            config.idpCredentials.clientId.id,
            ClientCredentialFactory.createFromSecret(config.idpCredentials.clientSecret.value)
        ).authority(config.idpEndpoint.toString())
            // TODO: Implement application level retry of all remote calls and then comment in the line below
            // .disableInternalRetries()
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
        @Value($$"${client.retry-template.retries}")
        retries: Int,
        @Value($$"${client.retry-template.initial-delay}")
        initialDelay: Duration,
        @Value($$"${client.retry-template.multiplier}")
        multiplier: Double,
        @Value($$"${client.retry-template.max-delay}")
        maxDelay: Duration
    ): RetryTemplate = RetryTemplate.builder()
        .maxAttempts(retries) // 1 initial attempt + retries
        .exponentialBackoff(initialDelay, multiplier, maxDelay, true)
        .retryOn(IOException::class.java)
        .retryOn(HttpServerErrorException::class.java)
        .traversingCauses()
        .build()

    @Bean
    @ConditionalOnProperty(prefix = "client", name = ["file-busy-test-strategy"], havingValue = "FILE_SIZE_CHANGED")
    fun fileSizeChanged(@Value($$"${client.file-size-busy-test-interval:PT0.25S}") testInterval: Duration): FileBusyTester =
        require(!testInterval.isZero && !testInterval.isNegative).run {
            FileBusyTester.FileSizeChanged(testInterval)
                .also { logger.info { "Using file-busy-test strategy 'FILE_SIZE_CHANGED', sampling file at interval '$testInterval'" } }
        }

    @Bean
    @ConditionalOnProperty(prefix = "client", name = ["file-busy-test-strategy"], havingValue = "ALWAYS_BUSY")
    fun alwaysBusyFileTester(): FileBusyTester = FileBusyTester.AlwaysBusy.also { logger.info { "Using file-busy-test strategy 'ALWAYS_BUSY'" } }

    @Bean
    @ConditionalOnProperty(prefix = "client", name = ["file-busy-test-strategy"], havingValue = "NEVER_BUSY")
    fun neverBusyFileTester(): FileBusyTester = FileBusyTester.NeverBusy.also { logger.info { "Using file-busy-test strategy 'NEVER_BUSY'" } }

    @Bean
    @ConditionalOnMissingBean(FileBusyTester::class)
    fun defaultBusyFileTester(): FileBusyTester =
        FileBusyTester.NeverBusy.also { logger.warn { "No file-busy-test strategy defined, defaulting to 'NEVER_BUSY'" } }

}

internal class HttpServerErrorException(message: String, val statusCode: Int, val responseBody: String) : RuntimeException(message)

sealed interface FileBusyTester {
    suspend fun isBusy(file: Path): Boolean

    object NeverBusy : FileBusyTester {
        override suspend fun isBusy(file: Path): Boolean = false
    }

    // Only useful for testing
    object AlwaysBusy : FileBusyTester {
        override suspend fun isBusy(file: Path): Boolean = true
    }

    class FileSizeChanged(private val testInterval: Duration) : FileBusyTester {
        override suspend fun isBusy(file: Path): Boolean = runCatching {
            logger.debug { "'${file.name}' busy state check..." }
            val startSize = file.fileSize()
            delay(testInterval)
            val endSize = file.fileSize()
            (startSize != endSize).also { logger.debug { "'${file.name}' busy state: '$it'; start size: '$startSize', end size: '$endSize'" } }
        }.fold(
            onSuccess = { it },
            onFailure = { t: Throwable ->
                when (t) {
                    is IOException -> {
                        logger.warn { "Failed to determine file size for file '$file': ${t.message}" }
                        false
                    }

                    else -> throw t
                }
            }
        )
    }

}
