package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.config.CdrClientConfig.Connector
import com.swisscom.health.des.cdr.client.config.CdrClientConfig.Connector.Companion.effectiveSourceFolder
import com.swisscom.health.des.cdr.client.config.CdrClientConfig.Connector.Companion.effectiveTargetFolder
import com.swisscom.health.des.cdr.client.xml.MessageType
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.io.IOException
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.fileStore
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isWritable
import kotlin.io.path.listDirectoryEntries


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

        companion object {
            /**
             * Returns the effective source folder for a given [TypeFolders] instance. If the [TypeFolders.sourceFolder] is not set, returns `null`.
             * @see TypeFolders
             */
            fun Connector.effectiveSourceFolder(typeFolder: TypeFolders): Path? = typeFolder.sourceFolder?.let { this.sourceFolder.resolve(it) }

            /**
             * Returns the effective target folder for a given [TypeFolders] instance. If the [TypeFolders.targetFolder] is not set, returns `null`.
             * @see TypeFolders
             */
            fun Connector.effectiveTargetFolder(typeFolder: TypeFolders): Path? = typeFolder.targetFolder?.let { this.targetFolder.resolve(it) }
        }

        /** Unique identifier for the connector; log into CDR web app to look up your connector ID(s). */
        lateinit var connectorId: String

        /** Target folder where the CDR client will download files to. */
        lateinit var targetFolder: Path

        /** Source folder where the CDR client will upload files from. */
        lateinit var sourceFolder: Path

        /** Media type to set for file uploads; currently only `application/forumdatenaustausch+xml;charset=UTF-8` is supported. */
        lateinit var contentType: MediaType

        /**
         * Whether to enable the archiving of successfully uploaded files. If not enabled files that have been uploaded get deleted.
         * If you enable archiving, beware that the client does not perform any housekeeping of the archive. Housekeeping is the
         * responsibility of the system's administrator.
         *
         * Defaults to `false`
         *
         * @see sourceArchiveFolder
         * @see getEffectiveSourceArchiveFolder
         */
        var sourceArchiveEnabled: Boolean = false

        /**
         * Folder to archive uploaded files to. The folder will be created for you if it does not exist yet if [sourceArchiveEnabled]
         * is set to `true`. If you specify a relative path it will be resolved relative to the source folder. If you specify an absolute path,
         * the path will be used as is for all archive folders, such as for all [typeFolders].
         *
         * Beware: On Linux both `.` and `./` resolve to the current working directory, while `./archive` (and just `archive`) resolve
         * to `<source_dir>/archive`.
         *
         * Default is an empty path, which resolves to the source folder itself.
         *
         * @see sourceArchiveEnabled
         * @see getEffectiveSourceArchiveFolder
         * @see sourceFolder
         */
        var sourceArchiveFolder: Path = EMPTY_PATH

        /**
         * Folder to move documents to for which the upload has failed. If you specify a relative path it will be resolved relative
         * to the source folder. If you specify an absolute path, the path will be used as is for all error folders, such as for all [typeFolders].
         *
         * Beware: On Linux both `.` and `./` resolve to the current working directory, while `./error` (and just `error`) resolve
         * to `<source_dir>/error`.
         *
         * Default is an empty path, which resolves to the source folder itself.
         *
         * @see getEffectiveSourceErrorFolder
         * @see sourceFolder
         */
        var sourceErrorFolder: Path = EMPTY_PATH

        /** Mode of the connector; either `test` or `production`; attempting to upload documents with a mismatching mode attribute value will fail. */
        lateinit var mode: Mode

        private var _typeFolders: Map<MessageType, TypeFolders>? = null

        /**
         * Forum Datenaustausch message types related folders. In case that the files come from different source folders or received files need to be stored
         * to different target folders, depending on the message type
         */
        var typeFolders: Map<MessageType, TypeFolders>
            get() = _typeFolders ?: emptyMap()
            set(value) {
                _typeFolders = value
            }

        /**
         * If [sourceArchiveEnabled] is set to `true` returns the archive folder resolved against the source folder with a subdirectory
         * for the current date. The directories will be created if they do not exist. If [sourceArchiveEnabled] is `false` returns an
         * empty path.
         *
         * @see sourceArchiveEnabled
         * @see sourceArchiveFolder
         * @see sourceFolder
         */
        fun getEffectiveSourceArchiveFolder(path: Path): Path =
            if (sourceArchiveEnabled) {
                if (path.isDirectory()) {
                    path
                } else {
                    path.parent
                }.resolve(sourceArchiveFolder.resolve(getDateNow()))
            } else {
                EMPTY_PATH
            }.also { createDirectoryIfNeeded(it) }

        /**
         * Convenience property to get the connector archive folder that is used in all cases where no message type related folders are defined.
         * @see getEffectiveSourceArchiveFolder
         */
        val effectiveConnectorSourceArchiveFolder: Path
            get() = if (sourceArchiveEnabled) sourceFolder.resolve(sourceArchiveFolder.resolve(getDateNow()))
                .also { createDirectoryIfNeeded(it) } else EMPTY_PATH

        private fun createDirectoryIfNeeded(path: Path): Path = try {
            path.createDirectories()
        } catch (ex: IOException) {
            logger.error { ex }
            error("Failed to create directory '$path' for connector '$connectorId' - Is the path reachable and are there sufficient access rights?")
        }

        private fun getDateNow(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

        /**
         * Returns the error folder resolved against the source folder with a subdirectory for the current date. The directories will be
         * created if they do not exist.
         *
         * @see sourceErrorFolder
         * @see sourceFolder
         */
        fun getEffectiveSourceErrorFolder(path: Path): Path =
            if (path.isDirectory()) {
                path
            } else {
                path.parent
            }.resolve(sourceErrorFolder.resolve(getDateNow()))
                .also { createDirectoryIfNeeded(it) }

        /**
         * Convenience property to get the connector error folder that is used in all cases where no message type related folders are defined.
         * @see getEffectiveSourceErrorFolder
         */
        val effectiveConnectorSourceErrorFolder: Path
            get() = sourceFolder.resolve(sourceErrorFolder.resolve(getDateNow())).also { createDirectoryIfNeeded(it) }

        override fun toString(): String {
            return "Connector(connectorId='$connectorId', targetFolder=$targetFolder, sourceFolder=$sourceFolder, contentType=$contentType, " +
                    "uploadArchiveEnabled=$sourceArchiveEnabled, sourceArchiveFolder=$sourceArchiveFolder, " +
                    "effectiveSourceArchiveFolder=${effectiveConnectorSourceArchiveFolder}, " +
                    if (sourceArchiveEnabled && typeFolders.isNotEmpty() && typeFolders.entries.any { it.value.sourceFolder != null })
                        "additionalEffectiveSourceArchiveFolders=[${
                            typeFolders.entries.filter { it.value.sourceFolder != null }
                                .joinToString { "${it.key}=${getEffectiveSourceArchiveFolder(it.value.sourceFolder!!)}" }
                        }], "
                    else "" +
                            "sourceErrorFolder=$sourceErrorFolder, effectiveSourceErrorFolder=${effectiveConnectorSourceErrorFolder} " +
                            if (typeFolders.isNotEmpty() && typeFolders.entries.any { it.value.sourceFolder != null })
                                "additionalEffectiveSourceErrorFolders=[${
                                    typeFolders.entries.filter { it.value.sourceFolder != null }
                                        .joinToString { "${it.key}=${getEffectiveSourceErrorFolder(it.value.sourceFolder!!)}" }
                                }], "
                            else "" +
                                    "mode=$mode)"
        }

        /**
         * Specified folders for a specific message type (e.g. Invoice). Can be absolute or relative (to the [Connector.sourceFolder]) paths.
         */
        class TypeFolders {
            var sourceFolder: Path? = null
            var targetFolder: Path? = null
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
        localFolderIsUnique()
        if (localFolder.exists() && !localFolder.isDirectory()) {
            error("Local folder is not a directory: '$localFolder'")
        }

        if (!localFolder.exists()) {
            try {
                localFolder.createDirectories()
            } catch (ex: IOException) {
                logger.error { ex }
                error("Failed to create directory '$localFolder' - Is the path reachable and are there sufficient access rights?")
            }
        }

        sourceTargetFolderOverlap()
        // we don't check target folder for duplicate as this can be configured deliberately by customers
        duplicateSourceFolders()
        checkNoConnectorIdHasTheSameModeDefinedTwice()
        allFoldersAreReadWriteable()

        if (fileBusyTestTimeout <= fileBusyTestInterval) {
            error("fileBusyTestTimeout must be greater than fileBusyTestInterval")
        }

        // throws exception if any lateinit var (used in toString()) has not been initialized -- we have no optional configuration items
        val asString = toString()

        logger.info { "Client configuration: $asString" }

        // the error folders have been created automatically when we checked the configuration --> delete them again, if they are empty,
        // to not pollute the filesystem with directories, that hopefully stay empty, on every (re)start of the client
        customer.forEach {
            if (it.effectiveConnectorSourceErrorFolder.listDirectoryEntries().isEmpty()) it.effectiveConnectorSourceErrorFolder.deleteExisting()
        }
    }

    private fun localFolderIsUnique() {
        val baseSourceFolders = customer.map { it.sourceFolder }
        val allSourceTypeFolders = getAllSourceTypeFolders()
        val baseTargetFolders = customer.map { it.targetFolder }
        val allTargetTypeFolders = getAllTargetTypeFolders()

        if ((baseSourceFolders + allSourceTypeFolders + baseTargetFolders + allTargetTypeFolders).contains(localFolder)) {
            error("The local folder '$localFolder' is configured as source or target folder for a connector")
        }

    }

    private fun sourceTargetFolderOverlap() {
        val baseSourceFolders = customer.map { it.sourceFolder }
        val allSourceTypeFolders = getAllSourceTypeFolders()
        val baseTargetFolders = customer.map { it.targetFolder }
        val allTargetTypeFolders = getAllTargetTypeFolders()

        val allSourceFolders: List<Path> = baseSourceFolders + allSourceTypeFolders
        val allTargetFolders: Set<Path> = (baseTargetFolders + allTargetTypeFolders).toSet()

        allSourceFolders.intersect(allTargetFolders).let { sourceAsTargetAndViceVersa ->
            if (sourceAsTargetAndViceVersa.isNotEmpty()) {
                error("The following directories are configured as both source and target directories: $sourceAsTargetAndViceVersa")
            }
        }
    }

    private fun getAllSourceTypeFolders(): List<Path> =
        customer.flatMap { connector -> connector.typeFolders.values.mapNotNull { connector.effectiveSourceFolder(it) } }

    private fun getAllTargetTypeFolders(): List<Path> =
        customer.flatMap { connector -> connector.typeFolders.values.mapNotNull { connector.effectiveTargetFolder(it) } }

    private fun duplicateSourceFolders(): Unit =
        customer.flatMap { connector ->
            listOf(connector.sourceFolder) + connector.typeFolders.values.mapNotNull { connector.effectiveSourceFolder(it) }
        }.groupingBy { it }.eachCount().filter { it.value > 1 }.let { duplicateSources ->
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
        val allFolders: List<Pair<String, Path>> = customer.flatMap {
            // we only check the base connector archive folders, as all type error folders are created in a folder where the process already needs to have
            // write access to read/delete files
            val archiveFolder: Pair<String, Path>? =
                if (it.sourceArchiveEnabled) "connector '${it.connectorId}' source archive" to it.effectiveConnectorSourceArchiveFolder else null
            // we only check the base connector error folders, as all type error folders are created in a folder where the process already needs to have
            // write access to read/delete files
            val errorFolder: Pair<String, Path>? =
                if (it.sourceErrorFolder != EMPTY_PATH) "connector '${it.connectorId}' source error" to it.effectiveConnectorSourceErrorFolder else null
            listOfNotNull(
                "connector '${it.connectorId}' source" to it.sourceFolder,
                archiveFolder,
                errorFolder,
                "connector '${it.connectorId}' target" to it.targetFolder,
            ) + it.typeFolders.values.flatMap { typeFolder ->
                listOfNotNull(
                    it.effectiveSourceFolder(typeFolder)?.let { folder -> "connector '${it.connectorId}' type source" to folder },
                    it.effectiveTargetFolder(typeFolder)?.let { folder -> "connector '${it.connectorId}' type target" to folder }
                )
            }
        } + listOf("Global inflight folder (could be changed with the property 'client.local-folder=')" to localFolder)

        allFolders.forEach { (name, folder) ->
            if (!folder.isDirectory()) {
                logger.info { "Creating non existing directory '$folder'" }
                try {
                    folder.createDirectories()
                } catch (ex: IOException) {
                    logger.error { ex }
                    error("Failed to create directory '$folder' - Is the path reachable and are there sufficient access rights?")
                }
                if (!folder.isDirectory()) {
                    error("$name path '$folder' is not a directory or does not exist.")
                }
            }
            if (!folder.isWritable()) {
                error("$name path '$folder' isn't writable by running user.")
            }
            if (!folder.isReadable()) {
                error("$name path '$folder' isn't readable by running user.")
            }
            if (DataSize.ofBytes(folder.fileStore().usableSpace) < FREE_DISK_SPACE_WARNING_THRESHOLD) {
                logger.warn { "Filesystem of $name path '$folder' has less than ${FREE_DISK_SPACE_WARNING_THRESHOLD.toMegabytes()}mb of free space." }
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

        @JvmStatic
        private val FREE_DISK_SPACE_WARNING_THRESHOLD = DataSize.ofMegabytes(100L)

        @JvmStatic
        val EMPTY_PATH: Path = Path.of("")
    }

}

fun Connector.getAllSourceTypeFolders(): List<Path> = this.typeFolders.values.mapNotNull { this.effectiveSourceFolder(it) }
fun List<Connector>.getConnectorForSourceFile(file: Path): Connector =
    this.first { it.sourceFolder == file.parent || it.getAllSourceTypeFolders().contains(file.parent) }
