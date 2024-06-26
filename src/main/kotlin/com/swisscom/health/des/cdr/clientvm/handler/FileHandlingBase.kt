package com.swisscom.health.des.cdr.clientvm.handler

import com.swisscom.health.des.cdr.clientvm.config.CdrClientConfig
import io.github.oshai.kotlinlogging.KLogger
import io.micrometer.tracing.Tracer
import okhttp3.Headers
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.util.UriComponentsBuilder
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path


internal const val FUNCTION_KEY_HEADER = "x-functions-key"
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
abstract class FileHandlingBase(protected val cdrClientConfig: CdrClientConfig, private val tracer: Tracer) {

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
     * Build headers with connector-id, function key, processing mode and trace id.
     */
    protected fun buildBaseHeaders(connectorId: String, mode: CdrClientConfig.Mode): Headers {
        val traceId = tracer.currentSpan()?.context()?.traceId() ?: ""
        return Headers.Builder().run {
            this[CONNECTOR_ID_HEADER] = connectorId
            this[FUNCTION_KEY_HEADER] = cdrClientConfig.functionKey
            this[CDR_PROCESSING_MODE_HEADER] = mode.value
            this[AZURE_TRACE_ID_HEADER] = traceId
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

}
