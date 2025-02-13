package com.swisscom.health.des.cdr.client.installer

import com.swisscom.health.des.cdr.client.getInstallDir
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Scanner

private val logger = KotlinLogging.logger {}

class Installer(private val scanner: Scanner = Scanner(System.`in`)) {

    fun install() {
        println("###############################################")
        println("###############################################")
        println("###############################################")
        println("")
        println("Configuration was not found. Please provide the following information: ")
        print("Tenant-ID: ")
        val tenantId = scanner.nextLine()
        print("Client-ID: ")
        val clientId = scanner.nextLine()
        print("Client-Secret: ")
        val clientSecret = scanner.nextLine()
        print("Connector-ID: ")
        val connectorId = scanner.nextLine()
        print("Create Service Account? (y/N): ")
        val createServiceAccount = scanner.nextLine()

        updateConfigFile(
            tenantId = tenantId,
            connectorId = connectorId,
            clientId = clientId,
            clientSecret = clientSecret
        )
        if (createServiceAccount == "Y" || createServiceAccount.lowercase() == "y") {
            createService(connectorId)
        }

        println("All done! The application is now ready to be started.")
        println("Please close the window and start the application again.")
    }

    private fun updateConfigFile(
        tenantId: String,
        connectorId: String,
        clientId: String,
        clientSecret: String
    ): Pair<Boolean, String> {
        val configFile = File(getInstallDir() + File.separator + CONFIG_FILE)
        val trimmedTenantId = tenantId.trim()
        val trimmedConnectorId = connectorId.trim()
        val trimmedClientId = clientId.trim()
        val trimmedClientSecret = clientSecret.trim()

        runBlocking {
            val newContent = setBaseConfigAndAddConnector(
                tenantId = trimmedTenantId,
                connectorId = trimmedConnectorId,
                clientId = trimmedClientId,
                clientSecret = trimmedClientSecret,
            )
            logger.info { "write new config file to '$configFile'" }
            configFile.writeText(newContent, Charsets.UTF_8)
        }
        return true to "Configuration updated successfully\nPlease restart the application"
    }

    private fun setBaseConfig(
        tenantId: String,
        connectorId: String,
        clientId: String,
        clientSecret: String,
    ): String {
        val folderPath = getInstallDir().replace("\\", "/")
        return ""
            .plus("local-folder=$folderPath/download/inflight\n")
            .plus("$CONF_TENANT_ID=$tenantId\n")
            .plus("$CONF_CONNECTOR_ID=$connectorId\n")
            .plus("$CONF_CLIENT_ID=$clientId\n")
            .plus("$CONF_CLIENT_SECRET=$clientSecret\n")
    }

    private fun setBaseConfigAndAddConnector(
        tenantId: String,
        connectorId: String,
        clientId: String,
        clientSecret: String,
    ): String {
        val folderPath = getInstallDir().replace("\\", "/")
        return setBaseConfig(
            tenantId = tenantId,
            connectorId = connectorId,
            clientId = clientId,
            clientSecret = clientSecret
        )
            .plus(
                createConnector(
                    connectorId = connectorId,
                    folderPath = folderPath,
                    isProduction = true,
                    entryNumber = 0
                )
            )
            .plus(
                createConnector(
                    connectorId = connectorId,
                    folderPath = folderPath,
                    isProduction = false,
                    entryNumber = 1
                )
            )
    }

    private fun createConnector(
        connectorId: String,
        folderPath: String,
        isProduction: Boolean,
        entryNumber: Int
    ): String {
        val download = "$folderPath/download"
        val downloadProduction = "$download/$connectorId"
        val downloadTest = "$download/test/$connectorId"
        val upload = "$folderPath/upload"
        val uploadProduction = "$upload/$connectorId"
        val uploadTest = "$upload/test/$connectorId"
        StringBuilder().apply {
            append("client.customer[$entryNumber].connector-id=$connectorId\n")
            append("client.customer[$entryNumber].content-type=application/forumdatenaustausch+xml;charset=UTF-8\n")
            append("client.customer[$entryNumber].target-folder=${if (isProduction) downloadProduction else downloadTest}\n")
            append("client.customer[$entryNumber].source-folder=${if (isProduction) uploadProduction else uploadTest}\n")
            append("client.customer[$entryNumber].mode=${if (isProduction) "production" else "test"}\n")
            return toString()
        }
    }

    private fun createService(connectorId: String) {
        val osName = System.getProperty("os.name").lowercase()
        when {
            osName.contains("win") -> {
                logger.info { "Running on Windows" }
                executeApacheDaemon(connectorId)
            }

            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> {
                logger.info { "Running on Linux/Unix - please create a daemon service for this application" }
            }

            osName.contains("mac") -> {
                logger.info { "Running on Mac OS - please create a daemon service for this application" }
            }

            else -> {
                logger.info { "Unknown operating system" }
            }
        }
    }


    @Suppress("MagicNumber")
    private fun executeApacheDaemon(connectorId: String) {
        val processBuilder = ProcessBuilder(
            "${getInstallDir()}$RESOURCE_DIR_PART${File.separator}cdrClient.exe",
            "//IS/cdrClient-$connectorId",
            "--Description=Swisscom AG CDR Client",
            "--StartPath=\"${getInstallDir()}\"",
            "--StartMode=exe",
            "--StartImage=\"${getInstallDir()}${File.separator}cdr-client.exe\"",
            "--Startup=auto",
            "--Jvm=auto",
            "--LogPath=\"${getInstallDir()}${File.separator}logs\""
        )
        processBuilder.inheritIO()
        logger.info { "Create service command: ${processBuilder.command()}" }
        val process = processBuilder.start()
        when (val exitValue = process.waitFor()) {
            0 -> {
                logger.info { "Service created successfully" }
            }

            5 -> {
                logger.info {
                    "Failed to create service. Access denied. Did you run the Application as Administrator? " +
                            "Exit value: $exitValue"
                }
            }

            else -> {
                logger.info { "Failed to create service. Exit value: $exitValue" }
            }
        }
    }

    companion object {
        const val CONFIG_FILE = "application-customer.properties"
        const val CONF_TENANT_ID = "tenant-id"
        const val CONF_CONNECTOR_ID = "connector-id"
        const val CONF_CLIENT_ID = "client-id"
        const val CONF_CLIENT_SECRET = "client-secret"

        @JvmStatic
        val RESOURCE_DIR_PART = File.separator + "lib" + File.separator + "app"
    }

}
