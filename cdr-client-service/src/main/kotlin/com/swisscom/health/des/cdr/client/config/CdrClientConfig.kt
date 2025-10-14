package com.swisscom.health.des.cdr.client.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.config.CdrClientConfig.Mode
import com.swisscom.health.des.cdr.client.xml.DocumentType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory


private val logger = KotlinLogging.logger {}

@JsonIgnoreProperties(value = ["propertyName", "property-name"])
internal interface PropertyNameAware {
    val propertyName: String
}

/**
 * CDR client-specific configuration
 */
@ConfigurationProperties("client")
internal data class CdrClientConfig(
    /** Whether file synchronization is enabled. If set to `false`, the client will not download or upload files. */
    val fileSynchronizationEnabled: FileSynchronization,

    /** Customer-specific list of [Connectors][Connector]. */
    val customer: Customer,

    /** Endpoint coordinates (protocol scheme, host, port, basePath) of the CDR API. */
    val cdrApi: CdrApi,

    /** Maximum data size of the cache for files in progress. The cache holds filenames, not the files themselves. */
    val filesInProgressCacheSize: DataSize,

    /** Client credentials and tenant info used to authenticate against the OAUth identity provider (credential flow). */
    val idpCredentials: IdpCredentials,

    /** OAuth IdP URL. */
    val idpEndpoint: URL,

    /** Directory to temporarily store downloaded documents that are pending download acknowledgement. */
    val localFolder: TempDownloadDir,

    /** Maximum number of concurrent document downloads. */
    val pullThreadPoolSize: Int,

    /** Maximum number of concurrent document uploads. */
    val pushThreadPoolSize: Int,

    /** The delay between upload/download retries; the number of entries also defines how often a retry is attempted. */
    val retryDelay: List<Duration>,

    /** The delay between scheduled document uploads and downloads. */
    val scheduleDelay: Duration,

    /** Endpoint coordinates (protocol scheme, host, port, basePath) of the credential API. */
    val credentialApi: CredentialApi,

    /** Retry template configuration; retries http calls if IOExceptions or server errors are raised. */
    val retryTemplate: RetryTemplateConfig,

    /** Wait time between two tests whether a file scheduled for upload is still busy (written into). */
    val fileBusyTestInterval: Duration,

    /** Maximum time to wait for a file to become available for upload before skipping it. */
    val fileBusyTestTimeout: Duration,

    /** Strategy to test whether a file is still busy (written into) before attempting to upload it. */
    val fileBusyTestStrategy: FileBusyTestStrategyProperty,
) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    private companion object {
        const val PROPERTY_NAME = "client"
    }

    data class RetryTemplateConfig(
        /** The number of retries to attempt (on top of the initial request). */
        val retries: Int,

        /** The initial delay before the first retry attempt. */
        val initialDelay: Duration,

        /** The maximum delay between retries. */
        val maxDelay: Duration,

        /** The multiplier to apply to the previous delay to get the next delay. */
        val multiplier: Double,
    )

    enum class Mode(val value: String) {
        TEST("test"),
        PRODUCTION("production")
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

/**
 * Clients identified by their customer id
 */
internal data class Connector(

    /** Unique identifier for the connector; log into CDR web app to look up your connector ID(s). */
    val connectorId: ConnectorId,

    /** Destination directory where the CDR client will download files to. */
    val targetFolder: Path,

    /** Source directory where the CDR client will upload files from. */
    val sourceFolder: Path,

    /** Media type to set for file uploads; currently only `application/forumdatenaustausch+xml;charset=UTF-8` is supported. */
    val contentType: String,

    /**
     * Whether to enable the archiving of successfully uploaded files. If not enabled, files that have been uploaded get deleted.
     * If you enable archiving, beware that the client does not perform any housekeeping of the archive. Housekeeping is the
     * responsibility of the system's administrator.
     *
     * Defaults to `false`
     *
     * @see sourceArchiveFolder
     * @see getEffectiveSourceArchiveFolder
     */
    val sourceArchiveEnabled: Boolean = false,

    /**
     * Directory to archive uploaded files to. If you specify a relative path, it will be resolved relative to the source directory.
     * If you specify an absolute path, the path will be used as is for all archive directories (see [docTypeFolders]).
     *
     * Beware: On Linux empty string, `.`, and `./` all resolve to the current working directory, while `./archive` (and just `archive`) resolve
     * to `<source_dir>/archive`.
     *
     * Default is the system temp directory.
     *
     * @see sourceArchiveEnabled
     * @see getEffectiveSourceArchiveFolder
     * @see sourceFolder
     */
    val sourceArchiveFolder: Path? = null,

    /**
     * Directory to move documents to for which the upload has failed. If you specify a relative path, it will be resolved relative
     * to the source directory. If you specify an absolute path, the path will be used as is for all error directories, such as for all [docTypeFolders].
     *
     * Beware: On Linux empty string, `.`, and `./` all resolve to the current working directory, while `./archive` (and just `archive`) resolve
     * to `<source_dir>/archive`.
     *
     * Default is the system temp directory.
     *
     * @see getEffectiveSourceErrorFolder
     * @see sourceFolder
     */
    val sourceErrorFolder: Path? = null,

    /** Mode of the connector; either `test` or `production`; attempting to upload documents with a mismatching mode attribute value will fail. */
    val mode: Mode,

    /**
     * Forum Datenaustausch message types related directories. In case that the files come from different source directories or received files need to be
     * stored in different target directories, depending on the message type
     */
    val docTypeFolders: Map<DocumentType, DocTypeFolders>? = null,
) : PropertyNameAware {

    val effectiveDocTypeFolders: Map<DocumentType, DocTypeFolders>
        @JsonIgnore
        get() = docTypeFolders ?: emptyMap()

    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = ""

        @JvmStatic
        val TEMP_DIR_PATH: Path = Path.of(System.getProperty("java.io.tmpdir"))
    }

    /**
     * If [sourceArchiveEnabled] is set to `true` returns the archive directory resolved against the source directory with a subdirectory
     * for the current date. The directories will be created if they do not exist. If [sourceArchiveEnabled] is `false` returns an
     * empty path.
     *
     * @see sourceArchiveEnabled
     * @see sourceArchiveFolder
     * @see sourceFolder
     */
    @JsonIgnore
    fun getEffectiveSourceArchiveFolder(path: Path): Path? =
        if (sourceArchiveEnabled) {
            if (path.isDirectory()) {
                path
            } else {
                path.parent
            }.resolve((sourceArchiveFolder ?: TEMP_DIR_PATH).resolve(getDateNow()))
                .also { createDirectoryIfMissing(it) }
        } else {
            null
        }

    /**
     * Convenience property to get the connector archive directory that is used in all cases where no message type related directories are defined.
     * @see getEffectiveSourceArchiveFolder
     */
    val effectiveConnectorSourceArchiveFolder: Path?
        @JsonIgnore
        get() =
            if (sourceArchiveEnabled)
                sourceFolder.resolve((sourceArchiveFolder ?: TEMP_DIR_PATH).resolve(getDateNow()))
                    .also { createDirectoryIfMissing(it) }
            else
                null

    /**
     * Returns all source directories for all document types of this connector. If a [DocTypeFolders.sourceFolder] is not set, the entry is omitted.
     */
    @JsonIgnore
    fun getAllSourceDocTypeFolders(): List<Path> = this.effectiveDocTypeFolders.values.mapNotNull { this.effectiveSourceFolder(it) }

    /**
     * Returns the effective source directory for a given [DocTypeFolders] instance. If the [DocTypeFolders.sourceFolder] is not set, returns `null`.
     * @see DocTypeFolders
     */
    @JsonIgnore
    fun effectiveSourceFolder(docTypeFolders: DocTypeFolders): Path? = docTypeFolders.sourceFolder?.let { this.sourceFolder.resolve(it) }

    /**
     * Returns the effective target directory for a given [DocTypeFolders] instance. If the [DocTypeFolders.targetFolder] is not set, returns `null`.
     * @see DocTypeFolders
     */
    @JsonIgnore
    fun effectiveTargetFolder(docTypeFolders: DocTypeFolders): Path? = docTypeFolders.targetFolder?.let { this.targetFolder.resolve(it) }

    @Suppress("TooGenericExceptionCaught")
    private fun createDirectoryIfMissing(path: Path): Path = try {
        path.createDirectories()
    } catch (t: Throwable) {
        logger.error { "Failed to create directory '$path' for connector '$connectorId': $t" }
        throw t
    }

    private fun getDateNow(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

    /**
     * Returns the error directory resolved against the source directory with a subdirectory for the current date. The directories will be
     * created if they do not exist.
     *
     * @see sourceErrorFolder
     * @see sourceFolder
     */
    @JsonIgnore
    fun getEffectiveSourceErrorFolder(path: Path): Path =
        (sourceErrorFolder ?: Path.of(EMPTY_STRING)).let { errorDir ->
            if (path.isDirectory()) {
                path
            } else {
                path.parent
            }.resolve(errorDir.resolve(getDateNow()))
                .also { createDirectoryIfMissing(it) }
        }


    /**
     * Convenience property to get the connector error directory that is used in all cases where no message type related directories are defined.
     * @see getEffectiveSourceErrorFolder
     */
    val effectiveConnectorSourceErrorFolder: Path
        @JsonIgnore
        get() = (sourceErrorFolder ?: Path.of(EMPTY_STRING)).let { errorDir ->
            sourceFolder.resolve(errorDir.resolve(getDateNow()))
                .also { createDirectoryIfMissing(it) }
        }

    override fun toString(): String {
        return "Connector(connectorId='$connectorId', targetFolder=$targetFolder, sourceFolder=$sourceFolder, " +
                if (effectiveDocTypeFolders.isNotEmpty())
                    "additionalDocTypeFolders=[${
                        effectiveDocTypeFolders.entries.joinToString("; ") { "${it.key}=source=${it.value.sourceFolder},target=${it.value.targetFolder}" }
                    }], "
                else {
                    EMPTY_STRING
                } +
                "contentType=$contentType, uploadArchiveEnabled=$sourceArchiveEnabled, sourceArchiveFolder=$sourceArchiveFolder, " +
                "effectiveSourceArchiveFolder=${effectiveConnectorSourceArchiveFolder}, " +
                if (sourceArchiveEnabled && effectiveDocTypeFolders.isNotEmpty())
                    "additionalEffectiveSourceArchiveFolders=[${
                        effectiveDocTypeFolders.entries.joinToString("; ") { "${it.key}=${getEffectiveSourceArchiveFolder(it.value.sourceFolder!!)}" }
                    }], "
                else {
                    EMPTY_STRING
                } +
                "sourceErrorFolder=$sourceErrorFolder, effectiveSourceErrorFolder=${effectiveConnectorSourceErrorFolder} " +
                if (effectiveDocTypeFolders.isNotEmpty())
                    "additionalEffectiveSourceErrorFolders=[${
                        effectiveDocTypeFolders.entries.filter { it.value.sourceFolder != null }
                            .joinToString(", ") { "${it.key}=${getEffectiveSourceErrorFolder(it.value.sourceFolder!!)}" }
                    }], "
                else {
                    EMPTY_STRING
                } +
                "mode=$mode)"
    }

    /**
     * Specified directories for a specific document type (e.g., Invoice). Can be absolute or relative (to the [Connector.sourceFolder]) paths.
     */
    data class DocTypeFolders(
        val sourceFolder: Path? = null,
        val targetFolder: Path? = null,
    )
}

@JvmInline
internal value class ConnectorId(val id: String) : PropertyNameAware {

    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "connector-id"
    }
}

internal interface Endpoint {
    /** Protocol scheme of the endpoint; either `http` or `https`. */
    val scheme: String

    /** Hostname/FQDN of the endpoint. */
    val host: Host

    /** Port to connect to. */
    val port: Int

    /** Base path of the endpoint, e.g. `/documents` */
    val basePath: String
}

// Spring fails to assign the endpoint instances with an "object is not of declared type" error if the classes are declared inside the Endpoint interface
internal data class CdrApi(
    override val scheme: String,
    override val host: Host,
    override val port: Int,
    override val basePath: String,
) : Endpoint, PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "cdr-api"
    }
}

internal data class CredentialApi(
    override val scheme: String,
    override val host: Host,
    override val port: Int,
    override val basePath: String,
) : Endpoint, PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "credential-api"
    }
}

@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
internal data class Customer(
    private val customer: MutableList<Connector>
) : PropertyNameAware, MutableList<Connector> by customer {

    // required by SpringBoot
    constructor() : this(mutableListOf())

    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "customer"
    }
}

@JvmInline
internal value class Scope(val scope: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "scope"
    }
}

@JvmInline
internal value class FileBusyTestStrategyProperty(val strategy: CdrClientConfig.FileBusyTestStrategy) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "file-busy-test-strategy"

        @JvmStatic
        fun valueOf(value: String): FileBusyTestStrategyProperty =
            FileBusyTestStrategyProperty(CdrClientConfig.FileBusyTestStrategy.valueOf(value))
    }
}

@JvmInline
internal value class FileSynchronization private constructor(val value: Boolean) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        @JvmStatic
        val ENABLED = FileSynchronization(true)

        @JvmStatic
        val DISABLED = FileSynchronization(false)

        private const val PROPERTY_NAME = "file-synchronization-enabled"
    }
}

/**
 * Client OAuth credentials
 *
 * Has to be a top-level class to be able to wrap some of its properties in value classes. See this
 * [SO question](https://stackoverflow.com/questions/79658165/springboot-configurationproperties-class-with-kotlin-value-class-member-raises)
 * for more details.
 */
internal data class IdpCredentials(
    /** Tenant ID of the OAuth identity provider. */
    val tenantId: TenantId,

    /** Client ID used to authenticate against the OAuth identity provider. Log into CDR web app to look up or create your client ID. */
    val clientId: ClientId,

    /** Client secret used to authenticate against the OAuth identity provider. Log into the CDR web app to look up or re-issue a client secret. */
    val clientSecret: ClientSecret,

    /**
     * Access scope to request from the OAuth identity provider. Currently only ```https://[tst.]identity.health.swisscom.ch/CdrApi/.default```
     * is supported.
     * */
    val scope: Scope,

    /**
     * Whether to attempt to automatically renew the client secret every [maxCredentialAge] days.
     *
     * BEWARE: For automatic renewal to succeed, the client secret must be stored in a configuration property named `client.idp-credentials.client-secret`,
     * either in a properties or YAML file, and the client process owner must have read/write permissions on that file. Other configuration sources like
     * system properties, environment variables, etc., are not supported.
     * */
    val renewCredential: RenewCredential,

    /**
     * Maximum age of the client secret before it is automatically renewed. `Now` is compared against [lastCredentialRenewalTime] to determine whether the
     * age limit has been reached.
     */
    val maxCredentialAge: Duration = DEFAULT_MAX_CREDENTIAL_AGE,

    /**
     * Time when the client last renewed its secret.
     */
    val lastCredentialRenewalTime: LastCredentialRenewalTime,
) : PropertyNameAware {

    override val propertyName: String
        get() = PROPERTY_NAME

    /**
     * The number of milliseconds until the next credential renewal is due. Negative values will trigger an immediate renewal.
     */
    val millisUntilNextCredentialRenewal: Long
        @JsonIgnore
        get() = maxCredentialAge.toMillis() - ChronoUnit.MILLIS.between(lastCredentialRenewalTime.instant, Instant.now())

    companion object {
        const val PROPERTY_NAME = "idp-credentials"

        @JvmStatic
        val DEFAULT_MAX_CREDENTIAL_AGE: Duration = Duration.ofDays(365L)
    }
}

@JvmInline
internal value class TempDownloadDir(val path: Path) : PropertyNameAware {
    constructor(path: String) : this(Paths.get(path))

    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "local-folder"
    }
}

//
// BEGIN - Value classes for IdpCredentials properties
//

@JvmInline
internal value class RenewCredential(val value: Boolean) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        @JvmStatic
        val ENABLED = RenewCredential(true)

        @JvmStatic
        val DISABLED = RenewCredential(false)

        private const val PROPERTY_NAME = "renew-credential"
    }
}

@JvmInline
internal value class TenantId(val id: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "tenant-id"
    }
}

@JvmInline
internal value class ClientId(val id: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "client-id"
    }
}

@JvmInline
internal value class ClientSecret(val value: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    override fun toString(): String = "********"

    companion object {
        const val PROPERTY_NAME = "client-secret"
    }
}

@JvmInline
internal value class LastCredentialRenewalTime(val instant: Instant) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "last-credential-renewal-time"

        @JvmStatic
        val BEGINNING_OF_TIME: LastCredentialRenewalTime = LastCredentialRenewalTime(Instant.ofEpochSecond(0L))
    }
}

//
// END - Value classes for IdpCredentials properties
//

//
// BEGIN - Value classes for Endpoint properties
//

@JvmInline
internal value class Host(val fqdn: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        const val PROPERTY_NAME = "host"
    }
}

//
// END - Value classes for Endpoint properties
//

internal fun List<Connector>.getConnectorForSourceFile(file: Path): Connector =
    this.first { it.sourceFolder == file.parent || it.getAllSourceDocTypeFolders().contains(file.parent) }
