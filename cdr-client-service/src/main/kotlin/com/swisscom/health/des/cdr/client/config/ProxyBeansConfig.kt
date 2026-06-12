package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.handler.ConfigValidationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException

private val logger = KotlinLogging.logger {}

/**
 * Registers separate beans for Proxy and ProxyCredentials when configured.
 */
@Configuration
internal class ProxyBeansConfig {

    // Remove userinfo (username:password@) from a URL for safe logging
    private fun sanitizeUrlForLogging(rawUrl: String): String {
        return runCatching {
            val uri = URI(rawUrl)
            URI(
                uri.scheme,
                null, // userInfo removed
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toString()
        }.getOrElse {
            // Fallback: redact anything before the @ if present, otherwise return original
            rawUrl.replace(Regex("^.*?@"), "<redacted>@")
        }
    }

    @Bean
    @ConditionalOnExpression($$"'${client.proxy-config.url:}' != ''")
    fun proxy(config: CdrClientConfig, configValidationService: ConfigValidationService): Proxy? {
        val proxyConfig = config.proxyConfig
        return when {
            proxyConfig.url.value.isBlank() -> {
                logger.debug { "Proxy URL is blank in configuration" }
                null
            }
            configValidationService.validateProxyUrl(proxyConfig.url.value) is DTOs.ValidationResult.Failure -> {
                val safe = sanitizeUrlForLogging(proxyConfig.url.value)
                logger.error { "Invalid proxy URL '$safe': must start with http:// or https://" }
                null
            }

            else -> runCatching {
                val proxyUri = URI(proxyConfig.url.value)
                if (proxyUri.host == null) {
                    val safe = sanitizeUrlForLogging(proxyConfig.url.value)
                    logger.error { "Invalid proxy URL '$safe': missing host" }
                    null
                } else {
                    val port = getProxyPort(proxyUri)

                    Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyUri.host, port)).also {
                        val safe = sanitizeUrlForLogging(proxyConfig.url.value)
                        logger.info { "Configured HTTP proxy: '$safe'" }
                    }
                }
            }.getOrElse { e ->
                val safe = try {
                    sanitizeUrlForLogging(proxyConfig.url.value)
                } catch (_: Throwable) {
                    proxyConfig.url.value
                }
                when (e) {
                    is URISyntaxException -> logger.error(e) { "Invalid proxy URL syntax: '$safe'" }
                    is IllegalArgumentException -> logger.error(e) { "Failed to configure proxy with URL '$safe'" }
                    else -> logger.error(e) { "Unexpected error configuring proxy with URL '$safe'" }
                }
                null
            }
        }
    }

    private fun getProxyPort(proxyUri: URI): Int = when {
        proxyUri.port != UNDEFINED_PORT -> proxyUri.port
        else -> when (proxyUri.scheme?.lowercase()) {
            "https" -> DEFAULT_HTTPS_PORT
            "http" -> DEFAULT_HTTP_PORT
            // should never happen, as the calling function already validated the scheme, but we need to handle it to satisfy the compiler
            else -> UNDEFINED_PORT
        }
    }

    @Bean
    @ConditionalOnExpression($$"'${client.proxy-config.url:}' != '' and '${client.proxy-config.username:}' != '' and '${client.proxy-config.password:}' != ''")
    fun proxyCredentials(config: CdrClientConfig): ProxyCredentials? =
        when {
            config.proxyConfig.username.value.isBlank() || config.proxyConfig.password.value.isBlank() -> {
                logger.debug { "No proxy credentials configured" }
                null
            }

            else -> {
                logger.info { "Configured proxy credentials for user: '${config.proxyConfig.username.value}'" }
                ProxyCredentials(config.proxyConfig.username, config.proxyConfig.password)
            }
        }

    companion object {
        private const val UNDEFINED_PORT = -1
        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_PORT = 443
    }
}
