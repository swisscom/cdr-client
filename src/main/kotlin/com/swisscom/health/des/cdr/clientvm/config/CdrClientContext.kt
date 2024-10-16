package com.swisscom.health.des.cdr.clientvm.config

import com.mayakapps.kache.InMemoryKache
import com.mayakapps.kache.KacheStrategy
import com.mayakapps.kache.ObjectKache
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
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
        builder.connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(readTimeout, TimeUnit.MILLISECONDS)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Bean(name = ["limitedParallelismCdrUploadsDispatcher"])
    fun limitedParallelCdrUploadsDispatcher(config: CdrClientConfig): CoroutineDispatcher {
        return Dispatchers.IO.limitedParallelism(config.pushThreadPoolSize)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Bean(name = ["limitedParallelismCdrDownloadsDispatcher"])
    fun limitedParallelCdrDownloadsDispatcher(config: CdrClientConfig): CoroutineDispatcher {
        return Dispatchers.IO.limitedParallelism(config.pullThreadPoolSize)
    }

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

}
