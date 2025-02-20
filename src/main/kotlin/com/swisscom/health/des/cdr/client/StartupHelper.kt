package com.swisscom.health.des.cdr.client

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists


private val logger = KotlinLogging.logger {}

private val configEnvironmentVars = listOf("CDR_B2C_TENANT_ID", "CDR_CLIENT_ID", "client.idp-credentials.client-secret")
private val skipInstallerProfiles = listOf("dev", "test")

/**
 * Get the installation directory of the application.
 * As we don't know where the application is installed, we need to find out where the jar is located.
 * With the installer created by jpackage the jar is located in a subfolder of the installation directory.
 * * Windows: install-dir\app\cdr-client.jar
 * * Unix: install-dir\lib\app\cdr-client.jar
 *
 * If we deem that the application is not running from the jpackage installation (because of rules defined by us) we will take the codeSource.location of our
 * main class and use that as the installation directory.
 */
internal fun getInstallDir(): Path {
    val codeSourceLocation = CdrClientApplication::class.java.protectionDomain.codeSource.location.toURI().toString()
    val installDir: Path = if (codeSourceLocation.startsWith("jar:")) {
        val substringBefore = codeSourceLocation.removePrefix("jar:").removePrefix("nested:").substringBefore("/!")
        val uriString = if (substringBefore.startsWith("/")) "file:$substringBefore" else substringBefore
        Paths.get(URI(uriString))
    } else {
        Paths.get(URI(codeSourceLocation))
    }
        .let { jarFile ->
            if (!isRunningFromJpackageInstallation()) {
                jarFile
            } else if (osIsWindows()) {
                jarFile.parent.parent // Windows: 'install-dir\app\cdr-client.jar'
            } else {
                jarFile.parent.parent.parent // Unix: 'install-dir/lib/app/cdr-client.jar'
            }
        }

    logger.debug { "Install dir: $installDir" }
    return installDir
}

internal fun osIsWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

internal fun isUiAvailable(): Boolean {
    return if (osIsWindows()) {
        val sessionName = System.getenv("SESSIONNAME")
        !sessionName.isNullOrEmpty() && sessionName.equals("Console", ignoreCase = true)
    } else {
        val display = System.getenv("DISPLAY")
        !display.isNullOrEmpty()
    }
}

/**
 * The installation is not required if:
 * * skipInstaller is set or a specific profile is active
 * * the env variable "SPRING_CONFIG_ADDITIONAL_LOCATION", the property "spring.config.additional-location" or the file "application-customer.properties"
 *   exists in the installation directory
 *     * if one of them is set, the file also needs to exist
 *     * the "application-customer.properties" file is a special case, as we create that one with our installer if needed
 * * all required environment variables are set
 *
 * If none of the above bullet points apply, then an installation is required.
 */
internal fun checkIfInstallationIsRequired(args: Array<String>): Boolean =
    if (isSkipInstaller(args)) {
        logger.info { "skipped installer because it was requested" }
        false
    } else {
        val systemAdditionalLocations = getSystemAdditionalLocations()
        logger.debug { "AdditionalLocations when checking: '$systemAdditionalLocations'" }
        val additionalLocation = systemAdditionalLocations ?: (getInstallDir().resolve(CONFIG_FILE))

        if (!additionalLocation.exists()) {
            logger.info { "No configuration file does exist at any given location: '$additionalLocation'" }

            val missingEnvVars = configEnvironmentVars.filter { System.getenv(it).isNullOrEmpty() }
            if (missingEnvVars.isNotEmpty()) {
                logger.info { "Missing required environment variables: ${missingEnvVars.joinToString(", ")}" }
                logger.info { "Installation is required" }
                true
            } else {
                false
            }
        } else {
            false
        }
    }

private fun getSystemAdditionalLocations() = (System.getProperty("spring.config.additional-location")?.let { Path.of(it) }
    ?: System.getenv("SPRING_CONFIG_ADDITIONAL_LOCATION")?.let { Path.of(it) })

/**
 * Checks if the application is running from a jpackage installation.
 * This is the best guess, as we can't be sure if the jar is started from the jpackage installation or not.
 * In the jpackage installation we don't set the "spring.config.additional-location" property, and we don't have the minimal set of environment variables set.
 */
internal fun isRunningFromJpackageInstallation(): Boolean =
    getSystemAdditionalLocations() == null && configEnvironmentVars.any { System.getenv(it).isNullOrEmpty() }

internal fun isSkipInstaller(args: Array<String>): Boolean {
    val activeProfile = System.getProperty("spring.profiles.active")
    return args.contains("--skip-installer") || activeProfile != null && activeProfile.split(",").any { skipInstallerProfiles.contains(it) }
}
