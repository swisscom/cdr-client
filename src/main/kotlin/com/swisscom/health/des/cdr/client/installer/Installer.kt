package com.swisscom.health.des.cdr.client.installer

import com.swisscom.health.des.cdr.client.CONFIG_FILE
import com.swisscom.health.des.cdr.client.getInstallDir
import com.swisscom.health.des.cdr.client.osIsWindows
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Path
import java.util.Scanner
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

class Installer(private val scanner: Scanner = Scanner(System.`in`)) {

    fun install() {
        println("###############################################")
        println("###############################################")
        println("###############################################")
        println("")
        println("Configuration was not found. Please provide the following information (obtainable from the CDR customer website): ")
        print("Tenant-ID: ")
        val tenantId = scanner.nextLine()
        print("Client-ID: ")
        val clientId = scanner.nextLine()
        print("Client-Secret: ")
        val clientSecret = scanner.nextLine()
        print("Connector-ID: ")
        val connectorId = scanner.nextLine()
        val createServiceAccount: String? =
            if (osIsWindows()) {
                print("Create Service Account? (y/N): ")
                scanner.nextLine()
            } else {
                null
            }

        updateConfigFile(
            tenantId = tenantId,
            connectorId = connectorId,
            clientId = clientId,
            clientSecret = clientSecret
        )
        if (createServiceAccount?.lowercase() in listOf("y", "yes", "true")) {
            createService(connectorId)
        }

        println("All done! The application is now ready to be started.")
        println("Please start the application again.")
    }

    private fun updateConfigFile(
        tenantId: String,
        connectorId: String,
        clientId: String,
        clientSecret: String
    ) {
        val configFile = getInstallDir().resolve(CONFIG_FILE).toFile()
        val trimmedTenantId = tenantId.trim()
        val trimmedConnectorId = connectorId.trim()
        val trimmedClientId = clientId.trim()
        val trimmedClientSecret = clientSecret.trim()

        val newContent = setBaseConfigAndAddConnector(
            tenantId = trimmedTenantId,
            connectorId = trimmedConnectorId,
            clientId = trimmedClientId,
            clientSecret = trimmedClientSecret,
        )
        logger.info { "write new config file to '$configFile'" }
        configFile.writeText(newContent, Charsets.UTF_8)
    }

    private fun setBaseConfig(
        tenantId: String,
        clientId: String,
        clientSecret: String,
    ): String {
        val folderPath = getInstallDir()
        return ""
            .plus("client.local-folder=$folderPath/download/inflight\n")
            .plus("client.idp-credentials.$CONF_TENANT_ID=$tenantId\n")
            .plus("client.idp-credentials.$CONF_CLIENT_ID=$clientId\n")
            .plus("client.idp-credentials.$CONF_CLIENT_SECRET=$clientSecret\n")
    }

    private fun setBaseConfigAndAddConnector(
        tenantId: String,
        connectorId: String,
        clientId: String,
        clientSecret: String,
    ): String {
        val folderPath = getInstallDir()
        return setBaseConfig(
            tenantId = tenantId,
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
        folderPath: Path,
        isProduction: Boolean,
        entryNumber: Int
    ): String {
        val download = folderPath.resolve("download")
        val downloadProduction = download.resolve(connectorId)
        val downloadTest = download.resolve("test").resolve(connectorId)
        val upload = folderPath.resolve("upload")
        val uploadProduction = upload.resolve(connectorId)
        val uploadTest = upload.resolve("test").resolve(connectorId)
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
        if (osIsWindows()) {
            executeApacheDaemon(connectorId)
        } else {
            logger.info { "Operating system not eligible for service creation" }
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
        val exitCode: Int = runBlocking(Dispatchers.Default) {
            withTimeout(10.seconds) {
                processBuilder.start().waitFor()
            }
        }
        when (exitCode) {
            0 -> {
                logger.info { "Service created successfully" }
            }

            5 -> {
                logger.info {
                    "Failed to create service. Access denied. Did you run the Application as Administrator? " +
                            "Exit code: $exitCode"
                }
            }

            else -> {
                logger.info { "Failed to create service. Exit code: $exitCode" }
            }
        }
    }

    companion object {
        const val CONF_TENANT_ID = "tenant-id"
        const val CONF_CLIENT_ID = "client-id"
        const val CONF_CLIENT_SECRET = "client-secret"

        @JvmStatic
        val RESOURCE_DIR_PART: Path = Path.of("lib", "app")
    }

}
