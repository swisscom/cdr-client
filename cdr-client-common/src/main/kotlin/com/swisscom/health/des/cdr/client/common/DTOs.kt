@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, UrlSerializer::class, NioPathSerializer::class)

package com.swisscom.health.des.cdr.client.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

object DurationSerializer : KSerializer<Duration> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Duration", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Duration = Duration.parse(decoder.decodeString())
}

object UrlSerializer : KSerializer<URL> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.net.URL", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: URL) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): URL = URL(decoder.decodeString())
}

// Need to be compatible with `com.fasterxml.jackson.databind.ext.NioPathSerializer.serialize` where a java.nio.file.Path is serialized as a URI string.
object NioPathSerializer : KSerializer<Path> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.nio.file.Path", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Path) = encoder.encodeString(value.toUri().toString())
    override fun deserialize(decoder: Decoder): Path = decoder.decodeString()
        .run {
            runCatching {
                // Try to parse as a URI first, as this is how Jackson serializes it.
                Path.of(URI(this))
            }
                .recoverCatching {
                    Path.of(this)
                }.getOrThrow()
        }
}

class DTOs {

    @Serializable
    data class StatusResponse(
        val statusCode: StatusCode,
        val errorCodes: List<String> = emptyList(),
    ) {

        enum class StatusCode {
            UNKNOWN,
            SYNCHRONIZING,
            DISABLED,
            ERROR,
            OFFLINE,
        }

        enum class ErrorCode(val code: String) {
            UNKNOWN("unknown"),
            CONFIGURATION_ERROR("configuration_error"),
        }
    }

    @Serializable
    data class ShutdownResponse(
        val shutdownScheduledOn: Instant = Instant.now(),
        val shutdownScheduledFor: Instant,
        val trigger: String,
        val exitCode: Int,
    )

    /**
     * CDR client configuration class hierarchy. It is an almost verbatim copy of `com.swisscom.health.des.cdr.client.config.CdrClientConfig`.
     *
     * NOTE: Deserialization with Jackson fails if constructor parameters don't have default values.
     */
    @Serializable
    data class CdrClientConfig(
        val fileSynchronizationEnabled: Boolean,
        val customer: List<Connector>,
        val cdrApi: Endpoint,
        val filesInProgressCacheSize: String,
        val idpCredentials: IdpCredentials,
        val idpEndpoint: URL,
        val localFolder: Path,
        val pullThreadPoolSize: Int,
        val pushThreadPoolSize: Int,
        val retryDelay: List<Duration>,
        val scheduleDelay: Duration,
        val credentialApi: Endpoint,
        val retryTemplate: RetryTemplateConfig,
        val fileBusyTestInterval: Duration,
        val fileBusyTestTimeout: Duration,
        val fileBusyTestStrategy: FileBusyTestStrategy,
    ) {

        companion object {
            @JvmStatic
            val EMPTY = CdrClientConfig(
                fileSynchronizationEnabled = false,
                customer = emptyList(),
                cdrApi = Endpoint.EMPTY,
                filesInProgressCacheSize = "",
                idpCredentials = IdpCredentials.EMPTY,
                idpEndpoint = URL("http://localhost:8080"),
                localFolder = EMPTY_PATH,
                pullThreadPoolSize = 0,
                pushThreadPoolSize = 0,
                retryDelay = emptyList(),
                scheduleDelay = Duration.ZERO,
                credentialApi = Endpoint.EMPTY,
                fileBusyTestInterval = Duration.ZERO,
                fileBusyTestTimeout = Duration.ZERO,
                fileBusyTestStrategy = FileBusyTestStrategy.NEVER_BUSY,
                retryTemplate = RetryTemplateConfig.EMPTY,
            )
        }

        @Serializable
        data class Connector(
            val connectorId: String,
            val targetFolder: Path,
            val sourceFolder: Path,
            val contentType: String,
            val sourceArchiveEnabled: Boolean,
            val sourceArchiveFolder: Path,
            val sourceErrorFolder: Path,
            val mode: Mode,
            val docTypeFolders: Map<DocumentType, DocTypeFolders>,
        ) {

            @Serializable
            data class DocTypeFolders(
                val sourceFolder: Path? = null,
                val targetFolder: Path,
            )

        }

        @Serializable
        data class Endpoint(
            val scheme: String,
            val host: String,
            val port: Int,
            val basePath: String,
        ) {
            companion object {
                @JvmStatic
                val EMPTY = Endpoint(
                    scheme = "",
                    host = "",
                    port = 0,
                    basePath = ""
                )
            }
        }

        @Serializable
        data class IdpCredentials(
            val tenantId: String,
            val clientId: String,
            val clientSecret: String,
            val scopes: List<String>,
            val renewCredential: Boolean,
            val maxCredentialAge: Duration,
            val lastCredentialRenewalTime: Instant,
        ) {
            companion object {
                @JvmStatic
                val EMPTY = IdpCredentials(
                    tenantId = "",
                    clientId = "",
                    clientSecret = "",
                    scopes = emptyList(),
                    renewCredential = false,
                    maxCredentialAge = Duration.ZERO,
                    lastCredentialRenewalTime = Instant.EPOCH
                )
            }
        }

        enum class DocumentType {
            UNDEFINED,
            CONTAINER,
            CREDIT,
            FORM,
            HOSPITAL_MCD,
            INVOICE,
            NOTIFICATION;
        }

        @Serializable
        data class RetryTemplateConfig(
            val retries: Int,
            val initialDelay: Duration,
            val maxDelay: Duration,
            val multiplier: Double,
        ) {
            companion object {
                @JvmStatic
                val EMPTY = RetryTemplateConfig(
                    retries = 0,
                    initialDelay = Duration.ZERO,
                    maxDelay = Duration.ZERO,
                    multiplier = 1.0
                )
            }
        }

        enum class Mode {
            TEST,
            PRODUCTION,
            NONE
        }

        enum class FileBusyTestStrategy {
            /** checks for file size changes over a configurable duration.  */
            FILE_SIZE_CHANGED,

            /**
             * always flags the file as 'not busy'; use if all downstream applications
             * create files in a single atomic operation (`move` on the same file system).
             */
            NEVER_BUSY,

            /** always flags the file as 'busy'; only useful for test scenarios. */
            ALWAYS_BUSY,
        }

    }

    companion object {

        @JvmStatic
        val EMPTY_PATH: Path = Path.of("")

    }

}
