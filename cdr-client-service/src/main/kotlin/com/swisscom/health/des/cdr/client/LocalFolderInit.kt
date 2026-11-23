package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.ConfigInit.removeWorldAccess
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/**
 * Fills in a blank `client.local-folder` in the external SpringBoot configuration file with a default download staging
 * directory, so a fresh installation does not have to be configured manually before downloads can work.
 *
 * The default directory is `$STATE_DIRECTORY/inbound` if the `STATE_DIRECTORY` environment variable is set (systemd
 * services on Linux), otherwise `inbound` next to the configuration file; if the configuration file resides in a
 * directory named `conf`, then `inbound` is placed next to that `conf` directory.
 *
 * Failures never prevent the service from starting: if the directory cannot be created, the path is persisted anyway
 * and the configuration validation reports the missing directory; if the configuration file cannot be updated,
 * `client.local-folder` stays blank and the configuration validation reports it as not configured.
 */
object LocalFolderInit {
    internal const val INBOUND_DIR_NAME = "inbound"
    private const val CONF_DIR_NAME = "conf"
    private const val STATE_DIRECTORY_ENV = "STATE_DIRECTORY"
    private const val PROPERTY_NAME_CLIENT = "client"
    private const val PROPERTY_NAME_LOCAL_FOLDER = "local-folder"

    /**
     * @param configFile the external SpringBoot configuration file
     * @param stateDirectory value of the systemd `STATE_DIRECTORY` environment variable, if any
     */
    @Suppress("TooGenericExceptionCaught")
    fun initLocalFolder(configFile: Path, stateDirectory: String? = System.getenv(STATE_DIRECTORY_ENV)) {
        if (!configFile.isRegularFile()) {
            logMsg { "Configuration file '$configFile' does not exist, skipping initialization of '$PROPERTY_NAME_CLIENT.$PROPERTY_NAME_LOCAL_FOLDER'" }
            return
        }

        try {
            val configRoot: ObjectNode = ConfigYaml.read(configFile)
            val clientNode: ObjectNode? = configRoot[PROPERTY_NAME_CLIENT] as? ObjectNode
            val currentValue: String? = clientNode?.get(PROPERTY_NAME_LOCAL_FOLDER)?.takeUnless { it.isNull }?.asString()

            when {
                clientNode == null -> {
                    logMsg { "WARNING! No '$PROPERTY_NAME_CLIENT' section in '$configFile'; '$PROPERTY_NAME_CLIENT.$PROPERTY_NAME_LOCAL_FOLDER' stays unset" }
                }

                !currentValue.isNullOrBlank() -> Unit

                else -> {
                    val localFolder: Path = defaultLocalFolder(configFile, stateDirectory)
                    createLocalFolder(localFolder)
                    clientNode.put(PROPERTY_NAME_LOCAL_FOLDER, localFolder.toString())
                    ConfigYaml.write(configFile, configRoot)
                    logMsg { "Set '$PROPERTY_NAME_CLIENT.$PROPERTY_NAME_LOCAL_FOLDER' to '$localFolder' in '$configFile'" }
                }
            }
        } catch (e: Exception) {
            logMsg {
                "WARNING! Could not set '$PROPERTY_NAME_CLIENT.$PROPERTY_NAME_LOCAL_FOLDER' in '$configFile', it stays unconfigured " +
                        "and file downloads are disabled until a folder is configured: $e"
            }
        }
    }

    internal fun defaultLocalFolder(configFile: Path, stateDirectory: String?): Path {
        val baseDir: Path = stateDirectory
            // systemd may pass several colon separated directories
            ?.split(File.pathSeparatorChar)
            ?.firstOrNull { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?: configFile.absolute().parent.let { configDir: Path ->
                // moving up if parent folder is called "conf" because of how the manual installation guide suggests to place the configuration file
                if (configDir.fileName?.toString() == CONF_DIR_NAME) configDir.parent ?: configDir else configDir
            }
        return baseDir.resolve(INBOUND_DIR_NAME).absolute()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun createLocalFolder(localFolder: Path) {
        try {
            localFolder.createDirectories()
            localFolder.removeWorldAccess()
        } catch (e: Exception) {
            logMsg {
                "WARNING! Could not create or secure the download folder '$localFolder'; please create it manually and grant the " +
                        "service account read/write access: $e"
            }
        }
    }
}
