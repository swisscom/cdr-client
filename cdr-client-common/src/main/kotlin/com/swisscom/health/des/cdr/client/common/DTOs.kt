@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, UrlSerializer::class)

package com.swisscom.health.des.cdr.client.common

import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.URL
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

class DTOs {

    @Serializable
    sealed interface ValidationResult {
        val type: String

        @Serializable
        @SerialName("success")
        object Success : ValidationResult {
            override val type = "success"
        }

        @Serializable
        @SerialName("failure")
        data class Failure(val validationDetails: List<ValidationDetail>) : ValidationResult {
            override val type = "failure"
        }

        operator fun plus(other: ValidationResult): ValidationResult =
            when (this) {
                is Success -> other
                is Failure -> when (other) {
                    is Success -> this
                    is Failure -> Failure(this.validationDetails + other.validationDetails)
                }
            }
    }

    @Serializable
    sealed interface ValidationDetail {
        val type: String
        val messageKey: ValidationMessageKey

        @Serializable
        @SerialName("configItemDetail")
        data class ConfigItemDetail(
            val configItem: DomainObjects.ConfigurationItem,
            override val messageKey: ValidationMessageKey
        ) : ValidationDetail {
            override val type: String = "configItemDetail"
        }

        @Serializable
        @SerialName("pathDetail")
        data class PathDetail(
            val path: String,
            override val messageKey: ValidationMessageKey
        ) : ValidationDetail {
            override val type: String = "pathDetail"
        }
    }

    enum class ValidationMessageKey {
        NOT_A_DIRECTORY,
        DIRECTORY_NOT_FOUND,
        NOT_READ_WRITABLE,
        LOCAL_DIR_OVERLAPS_WITH_SOURCE_DIRS,
        LOCAL_DIR_OVERLAPS_WITH_TARGET_DIRS,
        TARGET_DIR_OVERLAPS_SOURCE_DIRS,
        DUPLICATE_SOURCE_DIRS,
        DUPLICATE_MODE,
        VALUE_IS_BLANK,
        FILE_BUSY_TEST_TIMEOUT_TOO_LONG,
        NO_CONNECTOR_CONFIGURED,
    }


    @Serializable
    data class StatusResponse(
        val statusCode: StatusCode,
        val errorCodes: List<String> = emptyList(),
    ) {

        enum class StatusCode(val isOnlineState: Boolean) {
            UNKNOWN(false),
            SYNCHRONIZING(true),
            DISABLED(true),
            ERROR(true),
            OFFLINE(false);

            val isOfflineState: Boolean
                get() = !isOnlineState
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
     * A note on `java.nio.Path`:<br/>
     * All Path types have been replaced with the String type because `Path` removes trailing forward slashes in its string
     * representation. As the string value is the value displayed in the UI, this leads to an effect that makes it look like forward slashes cannot be
     * entered. While, in fact, they are entered, used to create a new Path instance, and then rendered as the string representation of that Path instance,
     * which does not contain trailing forward slashes.
     */
    @Serializable
    data class CdrClientConfig(
        val fileSynchronizationEnabled: Boolean,
        val customer: List<Connector>,
        val cdrApi: Endpoint,
        val filesInProgressCacheSize: String,
        val idpCredentials: IdpCredentials,
        val idpEndpoint: URL,
        val localFolder: String,
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
                filesInProgressCacheSize = EMPTY_STRING,
                idpCredentials = IdpCredentials.EMPTY,
                idpEndpoint = URL("http://localhost:8080"),
                localFolder = EMPTY_STRING,
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
            val targetFolder: String,
            val sourceFolder: String,
            val contentType: String,
            val sourceArchiveEnabled: Boolean,
            val sourceArchiveFolder: String,
            val sourceErrorFolder: String? = null,
            val mode: Mode,
            val docTypeFolders: Map<DocumentType, DocTypeFolders>,
        ) {

            @Serializable
            data class DocTypeFolders(
                val sourceFolder: String? = null,
                val targetFolder: String? = null,
            ) {
                companion object {
                    @JvmStatic
                    val EMPTY = DocTypeFolders(
                        sourceFolder = null,
                        targetFolder = null
                    )
                }
            }

            companion object {
                @JvmStatic
                val EMPTY = Connector(
                    connectorId = EMPTY_STRING,
                    targetFolder = EMPTY_STRING,
                    sourceFolder = EMPTY_STRING,
                    contentType = EMPTY_STRING,
                    sourceArchiveEnabled = false,
                    sourceArchiveFolder = EMPTY_STRING,
                    sourceErrorFolder = null,
                    mode = Mode.NONE,
                    docTypeFolders = emptyMap()
                )
            }

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
                    scheme = EMPTY_STRING,
                    host = EMPTY_STRING,
                    port = 0,
                    basePath = EMPTY_STRING
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
                    tenantId = EMPTY_STRING,
                    clientId = EMPTY_STRING,
                    clientSecret = EMPTY_STRING,
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

}
