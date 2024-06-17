package com.swisscom.health.des.cdr.clientvm.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Bean(name = ["pullDispatcher"])
    fun pullDispatcher(config: CdrClientConfig): CoroutineDispatcher = Executors.newFixedThreadPool(config.pullThreadPoolSize).asCoroutineDispatcher()

    @Bean(name = ["pushDispatcher"])
    fun pushDispatcher(config: CdrClientConfig): CoroutineDispatcher = Executors.newFixedThreadPool(config.pushThreadPoolSize).asCoroutineDispatcher()
}
