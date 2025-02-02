package com.swisscom.health.des.cdr.client.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isWritable


private val logger = KotlinLogging.logger {}

/**
 * CDR client specific configuration
 *
 * Note on the `lateinit var` properties: Unfortunately we cannot use an immutable configuration,
 * using data class constructor injection with `val` properties, due to the way spring-cloud-context
 * works, which we use to reload the configuration and configuration-dependent beans/components
 * at runtime after a configuration change. Currently, the only configuration change that triggers
 * a refresh of the context is the automatic renewal of the client secret.
 */
@ConfigurationProperties("client")
class CdrClientConfig {
    /** Customer specific list of [Connectors][Connector]. */
    lateinit var customer: List<Connector>

    /** Endpoint coordinates (protocol scheme, host, port, basePath) of the CDR API. */
    lateinit var cdrApi: Endpoint

    /** Maximum data size of the cache for files in progress. The cache holds filenames, not the files themselves. */
    lateinit var filesInProgressCacheSize: DataSize

    /** Client credentials and tenant info used to authenticate against the OAUth identity provider (credential flow). */
    lateinit var idpCredentials: IdpCredentials

    /** OAuth IdP URL. */
    lateinit var idpEndpoint: URL

    /** Directory to temporarily store downloaded documents that are pending download acknowledgement. */
    lateinit var localFolder: Path

    /** Maximum number of concurrent document downloads. */
    var pullThreadPoolSize: Int = DEFAULT_SCHEDULER_POOL_SIZE

    /** Maximum number of concurrent document uploads. */
    var pushThreadPoolSize: Int = DEFAULT_SCHEDULER_POOL_SIZE

    /** The delay between upload/download retries; the number of entries also defines how often a retry is attempted. */
    lateinit var retryDelay: List<Duration>

    /** The delay between scheduled document uploads and downloads. */
    lateinit var scheduleDelay: Duration

    /** Endpoint coordinates (protocol scheme, host, port, basePath) of the credential API. */
    lateinit var credentialApi: Endpoint

    /** Retry template configuration; retries http calls if IOExceptions or server errors are raised. */
    lateinit var retryTemplate: RetryTemplateConfig

    /** Wait time between two tests whether a file scheduled for upload is still busy (written into). */
    lateinit var fileBusyTestInterval: Duration

    /** Maximum time to wait for a file to become available for upload before skipping it. */
    lateinit var fileBusyTestTimeout: Duration

    /** Strategy to test whether a file is still busy (written into) before attempting to upload it. */
    lateinit var fileBusyTestStrategy: FileBusyTestStrategy

    /**
     * Clients identified by their customer id
     */
    class Connector {
        /** Unique identifier for the connector; log into CDR web app to look up your connector ID(s). */
        lateinit var connectorId: String

        /** Target folder where the CDR client will download files to. */
        lateinit var targetFolder: Path

        /** Source folder where the CDR client will upload files from. */
        lateinit var sourceFolder: Path

        /** Media type to set for file uploads; currently only `application/forumdatenaustausch+xml;charset=UTF-8` is supported. */
        lateinit var contentType: MediaType

        /** Mode of the connector; either `test` or `production`; attempting to upload documents with a mismatching mode attribute value will fail. */
        lateinit var mode: Mode

        override fun toString(): String {
            return "Connector(connectorId='$connectorId', targetFolder=$targetFolder, sourceFolder=$sourceFolder, contentType=$contentType, mode=$mode)"
        }
    }

    /**
     * CDR API definition
     */
    class Endpoint {
        /** Protocol scheme of the endpoint; either `http` or `https`. */
        lateinit var scheme: String

        /** Hostname/FQDN of the endpoint. */
        lateinit var host: String

        /** Port to connect to. */
        var port: Int = DEFAULT_ENDPOINT_PORT

        /** Base path of the endpoint, e.g. `/documents` */
        lateinit var basePath: String

        override fun toString(): String {
            return "Endpoint(scheme='$scheme', host='$host', port=$port, basePath='$basePath')"
        }
    }

    /**
     * Client OAuth credentials
     */
    class IdpCredentials {
        /** Tenant ID of the OAuth identity provider. */
        lateinit var tenantId: String

        /** Client ID used to authenticate against the OAuth identity provider. Log into CDR web app to look up or create your client ID. */
        lateinit var clientId: String

        /** Client secret used to authenticate against the OAuth identity provider. Log into CDR web app to look up or re-issue a client secret. */
        lateinit var clientSecret: String

        /**
         * Access scopes to request from the OAuth identity provider. Currently only ```https://[stg.]identity.health.swisscom.ch/CdrApi/.default```
         * is supported.
         * */
        lateinit var scopes: List<String>

        /**
         * Whether to attempt to automatically renew the client secret at startup and then every 365 days should the client instance be running for that long.
         *
         * BEWARE: For automatic renewal to succeed, the client secret must be stored in a configuration property named `client.idp-credentials.client-secret`,
         * either in a properties or YAML file, and the client process owner must have read/write permissions on that file. Other configuration sources like
         * system properties, environment variables, etc., are not supported.
         * */
        var renewCredentialAtStartup: Boolean = false

        override fun toString(): String {
            return "IdpCredentials(tenantId='$tenantId', clientId='$clientId', clientSecret='********', scopes=$scopes, autoRenew='$renewCredentialAtStartup')"
        }
    }

    class RetryTemplateConfig {
        /** The number of retries to attempt (on top of the initial request). */
        var retries: Int = 1

        /** The initial delay before the first retry attempt. */
        lateinit var initialDelay: Duration

        /** The maximum delay between retries. */
        lateinit var maxDelay: Duration

        /** The multiplier to apply to the previous delay to get the next delay. */
        var multiplier: Double = 1.0

        override fun toString(): String {
            return "RetryTemplateConfig(retries=$retries, initialDelay=$initialDelay, maxDelay=$maxDelay, multiplier=$multiplier)"
        }

    }

    enum class Mode(val value: String) {
        TEST("test"),
        PRODUCTION("production")
    }

    enum class FileBusyTestStrategy {
        /** checks for file size changes over a configurable duration.  */
        FILE_SIZE_CHANGED,

        /**
         * always flags the file as 'not busy'; use if all downstream applications
         * create files in a single atomic operation (`move` on same file system).
         */
        NEVER_BUSY,

        /** always flags the file as 'busy'; only useful for test scenarios. */
        ALWAYS_BUSY,
    }

    @PostConstruct
    fun checkAndReport() {
        if (customer.isEmpty()) {
            error("There were no customer entries configured")
        }
        sourceTargetFolderOverlap()
        // we don't check target folder for duplicate as this can be configured deliberately by customers
        duplicateSourceFolders()
        checkNoConnectorIdHasTheSameModeDefinedTwice()
        allFoldersAreReadWriteable()

        if (localFolder.exists() && !localFolder.isDirectory()) {
            error("Local folder is not a directory: '$localFolder'")
        }

        if (!localFolder.exists()) {
            localFolder.createDirectories()
        }

        if (fileBusyTestTimeout <= fileBusyTestInterval) {
            error("fileBusyTestTimeout must be greater than fileBusyTestInterval")
        }

        logger.info { "Client configuration: $this" }
    }

    private fun sourceTargetFolderOverlap(): Unit =
        customer.map { it.sourceFolder }.intersect(customer.map { it.targetFolder }.toSet()).let { sourceAsTargetAndViceVersa ->
            if (sourceAsTargetAndViceVersa.isNotEmpty()) {
                error("The following directories are configured as both source and target directories: $sourceAsTargetAndViceVersa")
            }
        }

    private fun duplicateSourceFolders(): Unit =
        customer.groupingBy { it.sourceFolder }.eachCount().filter { it.value > 1 }.let { duplicateSources ->
            if (duplicateSources.keys.isNotEmpty()) {
                error("Duplicate source folders detected: ${duplicateSources.keys}")
            }
        }

    private fun checkNoConnectorIdHasTheSameModeDefinedTwice(): Unit =
        customer.groupBy { it.connectorId }.filter { cd -> cd.value.size > 1 }.values.forEach { connector ->
            if (connector.groupingBy { cr -> cr.mode }.eachCount().any { it.value > 1 }) {
                error("A connector has `production` or `test` mode defined defined twice: ${connector[0].connectorId}")
            }
        }

    private fun allFoldersAreReadWriteable() {
        val allFolders: List<Path> = customer.flatMap { listOf(it.sourceFolder, it.targetFolder) }
        allFolders.forEach { folder ->
            if (!folder.isDirectory()) {
                error("Configured path '$folder' is not a directory or does not exist.")
            }
            if (!folder.isWritable()) {
                error("Configured path '$folder' isn't writable by running user.")
            }
            if (!folder.isReadable()) {
                error("Configured path '$folder' isn't readable by running user.")
            }
        }
    }

    override fun toString(): String {
        return "CdrClientConfig(idpCredentials='$idpCredentials', idpEndpoint='$idpEndpoint', localFolder='$localFolder', " +
                "customer=$customer, cdrApi=$cdrApi, credentialApi=$credentialApi, scheduleDelay='$scheduleDelay', " +
                "retryDelay='${retryDelay.joinToString { it.toString() }}', retryTemplate='$retryTemplate', fileBusyTestInterval='$fileBusyTestInterval', " +
                "fileBusyTestTimeout='$fileBusyTestTimeout', fileBusyTestStrategy='$fileBusyTestStrategy')"
    }

    private companion object {
        private const val DEFAULT_SCHEDULER_POOL_SIZE = 1
        private const val DEFAULT_ENDPOINT_PORT = 443
    }

}
