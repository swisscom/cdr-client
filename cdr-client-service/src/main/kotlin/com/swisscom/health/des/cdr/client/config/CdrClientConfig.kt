package com.swisscom.health.des.cdr.client.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.config.CdrClientConfig.Mode
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

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

    /** Client credentials and tenant info used to authenticate against the OAuth identity provider (credential flow). */
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

    /** Proxy configuration for all HTTP communication (optional). */
    val proxyConfig: ProxyConfig,

    /** Threshold for considering files in the temp directory as "old". */
    val oldFileThreshold: Duration,

    /** Time between checks of the filesystem */
    val fileSystemCheckInterval: Duration,
) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    private companion object {
        private const val PROPERTY_NAME = "client"
    }

    @Suppress("UnusedPrivateMember")
    @PostConstruct
    private fun logConfig() {
        logger.info { "$this" }
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
        PRODUCTION("production"),
        NONE("none")
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
@Suppress("TooManyFunctions")
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
     * Directory to archive uploaded files to.
     * Needs to be an absolute path, the path will be used as is for all archive directories (see [docTypeFolders]).
     *
     * @see sourceArchiveEnabled
     * @see getEffectiveSourceArchiveFolder
     * @see sourceFolder
     */
    val sourceArchiveFolder: Path? = null,

    /**
     * Directory to move documents to for which the upload has failed.
     * Needs to be an absolute path, the path will be used as is for all error directories, such as for all [docTypeFolders].
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
    override val propertyName: String
        get() = PROPERTY_NAME

    override fun toString(): String {
        fun invert(inMap: Map<DocumentType, List<Path>>): Map<Path, List<DocumentType>> = inMap
            .flatMap { (docType, paths) -> paths.map { path -> path to docType } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        return "Connector(connectorId='$connectorId', baseTargetFolder=$targetFolder, baseSourceFolder=$sourceFolder, " +
                "sourceFolders=${invert(effectiveSourceFolders)}, targetFolders=${invert(effectiveTargetFolders)}, " +
                "contentType=$contentType, uploadArchiveEnabled=$sourceArchiveEnabled, sourceArchiveFolder=$sourceArchiveFolder, " +
                "baseSourceArchiveFolder=${baseSourceArchiveFolder}, archiveFolders=${invert(effectiveArchiveFolders)}, " +
                "sourceErrorFolder=$sourceErrorFolder, baseSourceErrorFolder=${baseSourceErrorFolder}, errorFolders=${ invert(effectiveErrorFolders)}, " +
                "mode=$mode)"
    }

    /**
     * Specified directories for a specific document type (e.g., Invoice). Can be absolute or relative (to the [Connector.sourceFolder]) paths.
     */
    data class DocTypeFolders(
        /** Whether separate source/target (upload/download) directories for request and response documents should be used. */
        val requestResponseSplit: Boolean = false,
        /**
         * Document type specific source (upload) directory to be used if request/response split is not enabled.
         * The source directory is optional. If not provided the base source directory is used for this document
         * type. If provided, it may be a relative path and if it is relative, it gets resolved against the base
         * source directory.
         */
        val sourceFolder: Path? = null,
        /**
         * Document type specific source (upload) directory for request documents to be used if request/response
         * split is enabled. For request/response split, this source directory is mandatory, and it has to be an
         * absolute path.
         *
         * Technically the distinction of request and response document upload directories is meaningless as the
         * client uploads any XML document it finds in any source directory. In general, not limited to the
         * document type specific configuration case, the source could be an arbitrarily long list of directories
         * that get scanned for XML documents. But right now it is not implemented this way for simplicity/symmetry
         * reasons.
         */
        val sourceFolderReq: Path? = null,
        /**
         * Document type specific source (upload) directory for response documents to be used if request/response
         * split is enabled. For request/response split, this source directory is mandatory, and it has to be an
         * absolute path.
         *
         * Technically the distinction of request and response document upload directories is meaningless as the
         * client uploads any XML document it finds in any source directory. In general, not limited to the
         * document type specific configuration case, the source could be an arbitrarily long list of directories
         * that get scanned for XML documents. But right now it is not implemented this way for simplicity/symmetry
         * reasons.
         */
        val sourceFolderResp: Path? = null,
        /**
         * Document type specific target (upload) directory to be used if request/response split is not enabled.
         * The target directory is optional. If not provided the base target directory is used for this document
         * type. If provided, it may be a relative path and if it is relative, it gets resolved against the base
         * target directory.
         */
        val targetFolder: Path? = null,
        /**
         * Document type specific target (upload) directory for request documents to be used if request/response
         * split is enabled. For request/response split, this target directory is mandatory, and it has to be an
         * absolute path.
         */
        val targetFolderReq: Path? = null,
        /**
         * Document type specific target (upload) directory for response documents to be used if request/response
         * split is enabled. For request/response split, this target directory is mandatory, and it has to be an
         * absolute path.
         */
        val targetFolderResp: Path? = null,
        /**
         * Document type specific archive directory. Successfully uploaded documents are moved there.
         *
         * If request/response split is not enabled, providing it is optional. If not provided the base archive
         * directory is used for this document type. If provided, it may be a relative path and if it is relative,
         * it gets resolved against the base source directory.
         *
         * If request/response split is enabled, then this archive directory is mandatory, and it has to be an
         * absolute path.
         */
        val archiveFolder: Path? = null,
        /**
         * Document type specific error directory. Documents that failed to upload are moved there.
         *
         * If request/response split is not enabled, providing it is optional. If not provided the base error
         * directory is used for this document type. If provided, it may be a relative path and if it is relative,
         * it gets resolved against the base source directory.
         *
         * If request/response split is enabled, then this archive directory is mandatory, and it has to be an
         * absolute path.
         */
        val errorFolder: Path? = null,
    )

    companion object {
        // blank on purpose; the connector instances are contained in a list and are addressed
        // by their index in the list, not by a property name; at the same time the class has
        // to implement the `PropertyNameAware` interface so the `ConfigurationWriter` keeps
        // traversing the object tree contained in the connector instances
        private const val PROPERTY_NAME = ""
    }
}

@JvmInline
internal value class ConnectorId(val id: String) : PropertyNameAware {

    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "connector-id"
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

// Spring fails to assign instances with an "object is not of declared type" error if the classes are declared inside an interface
internal data class CdrApi(
    override val scheme: String,
    override val host: Host,
    override val port: Int,
    override val basePath: String,
) : Endpoint, PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "cdr-api"
    }
}

// Spring fails to assign instances with an "object is not of declared type" error if the classes are declared inside an interface
internal data class CredentialApi(
    override val scheme: String,
    override val host: Host,
    override val port: Int,
    override val basePath: String,
) : Endpoint, PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "credential-api"
    }
}

/**
 * Proxy configuration for HTTP clients.
 */
internal data class ProxyConfig(
    val url: ProxyUrl,
    val username: ProxyUsername,
    val password: ProxyPassword,
) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "proxy-config"
    }
}

@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
internal data class Customer(
    private val customer: MutableList<Connector>,
) : PropertyNameAware, MutableList<Connector> by customer {

    // required by SpringBoot
    constructor() : this(mutableListOf())

    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "customer"
    }
}

@JvmInline
internal value class Scope(val scope: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "scope"
    }
}

@JvmInline
internal value class FileBusyTestStrategyProperty(val strategy: CdrClientConfig.FileBusyTestStrategy) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "file-busy-test-strategy"

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
        private const val PROPERTY_NAME = "idp-credentials"

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
        private const val PROPERTY_NAME = "local-folder"
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
        private const val PROPERTY_NAME = "tenant-id"
    }
}

@JvmInline
internal value class ClientId(val id: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "client-id"
    }
}

@JvmInline
internal value class ClientSecret private constructor(val value: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    override fun toString(): String = MASKED_VALUE

    companion object {
        private const val PROPERTY_NAME = "client-secret"

        @JvmStatic
        val NO_SECRET = ClientSecret(value = EMPTY_STRING)

        @JvmStatic
        val MASKED_SECRET = ClientSecret(value = MASKED_VALUE)

        operator fun invoke(value: String): ClientSecret =
            when {
                value.isBlank() -> NO_SECRET
                value.isAllAsterisks() -> MASKED_SECRET
                else -> ClientSecret(value)
            }
    }
}

@JvmInline
internal value class LastCredentialRenewalTime(val instant: Instant) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "last-credential-renewal-time"

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
        private const val PROPERTY_NAME = "host"
    }
}

//
// END - Value classes for Endpoint properties
//

//
// BEGIN - Value classes for ProxyConfig properties
//

@JvmInline
internal value class ProxyUrl(val value: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "url"
    }
}

@JvmInline
internal value class ProxyUsername(val value: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    companion object {
        private const val PROPERTY_NAME = "username"
    }
}

@JvmInline
internal value class ProxyPassword private constructor(val value: String) : PropertyNameAware {
    override val propertyName: String
        get() = PROPERTY_NAME

    override fun toString(): String = MASKED_VALUE

    companion object {
        private const val PROPERTY_NAME = "password"

        @JvmStatic
        val NO_PASSWORD = ProxyPassword(value = EMPTY_STRING)

        @JvmStatic
        val MASKED_PASSWORD = ProxyPassword(value = MASKED_VALUE)

        operator fun invoke(value: String): ProxyPassword =
            when {
                value.isBlank() -> NO_PASSWORD
                value.isAllAsterisks() -> MASKED_PASSWORD
                else -> ProxyPassword(value)
            }
    }
}

//
// END - Value classes for ProxyConfig properties
//

private const val MASKED_VALUE = "*********"
private const val MASK_CHAR = '*'

private fun String.isAllAsterisks(): Boolean = isNotEmpty() && all { it == MASK_CHAR }
