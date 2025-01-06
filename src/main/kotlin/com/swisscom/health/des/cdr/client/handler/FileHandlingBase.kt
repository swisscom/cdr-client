package com.swisscom.health.des.cdr.client.handler

import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.IAuthenticationResult
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.tracing.Tracer
import okhttp3.Headers
import org.springframework.http.HttpHeaders
import org.springframework.retry.support.RetryTemplate
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.util.UriComponentsBuilder
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

private val logger: KLogger = KotlinLogging.logger {}
internal const val CONNECTOR_ID_HEADER = "cdr-connector-id"
internal const val CDR_PROCESSING_MODE_HEADER = "cdr-processing-mode"
internal const val AZURE_TRACE_ID_HEADER = "x-ms-request-id"

/**
 * Helper function to check for each call if a path is a directory and writable.
 * This to prevent unexpected behaviour should access rights change during runtime.
 */
fun pathIsDirectoryAndWritable(path: Path, what: String, logger: KLogger): Boolean =
    when {
        !Files.isDirectory(path) -> {
            logger.error { "Configured path '$path' isn't a directory. Therefore no files are $what until a directory is configured." }
            false
        }

        !Files.isWritable(path) -> {
            logger.error { "Configured path '$path' isn't writable by running user. Therefore no files are $what until access rights are corrected." }
            false
        }

        else -> true
    }

/**
 * Basic functionality needed in all FileHandling classes
 */
abstract class FileHandlingBase(
    protected val cdrClientConfig: CdrClientConfig,
    private val clientCredentialParams: ClientCredentialParameters,
    private val retryIoErrorsThrice: RetryTemplate,
    private val securedApp: IConfidentialClientApplication,
    private val tracer: Tracer
) {

    /**
     * Builds a target URL from an endpoint and a path.
     *
     * @param path the path to add to the URL
     * @return the resulting URL
     */
    protected fun buildTargetUrl(path: String, queryParameters: MultiValueMap<String, String> = LinkedMultiValueMap()): URL {
        return UriComponentsBuilder
            .newInstance()
            .scheme(cdrClientConfig.endpoint.scheme)
            .host(cdrClientConfig.endpoint.host)
            .port(cdrClientConfig.endpoint.port)
            .path(path)
            .queryParams(queryParameters)
            .build()
            .toUri()
            .toURL()
    }

    /**
     * Build headers with connector-id, access token, processing mode and trace id.
     */
    protected fun buildBaseHeaders(connectorId: String, mode: CdrClientConfig.Mode): Headers {
        val traceId = tracer.currentSpan()?.context()?.traceId() ?: ""
        // TODO: Remove this check once the token is required
        val accessToken = if (cdrClientConfig.idpCredentials.tenantId != NO_TOKEN_TENANT_ID) {
            getAccessToken()
        } else {
            null
        }
        return Headers.Builder().run {
            this[CONNECTOR_ID_HEADER] = connectorId
            this[CDR_PROCESSING_MODE_HEADER] = mode.value
            this[AZURE_TRACE_ID_HEADER] = traceId
            if (accessToken != null) {
                this[HttpHeaders.AUTHORIZATION] = "Bearer $accessToken"
            }
            this.build()
        }
    }

    /**
     * Helper function to run a block of code with a new span.
     */
    protected fun <A> traced(spanName: String, block: () -> A): A {
        val newSpan = tracer.spanBuilder().name(spanName).start()
        return tracer.withSpan(newSpan).use {
            block()
        }
    }

    private fun getAccessToken(): String = runCatching {
        retryIoErrorsThrice.execute<String, Exception> { _ ->
            val authResult: IAuthenticationResult = securedApp.acquireToken(clientCredentialParams).get()
            logger.debug { "Token taken from ${authResult.metadata().tokenSource()}" }
            authResult.accessToken()
        }
    }.fold(
        onSuccess = { token: String -> token },
        onFailure = { e ->
            logger.error(e) { "Failed to get access token: ${e.message}" }
            ""
        }
    )

    companion object {
        private const val NO_TOKEN_TENANT_ID = "no-token"
    }

}
