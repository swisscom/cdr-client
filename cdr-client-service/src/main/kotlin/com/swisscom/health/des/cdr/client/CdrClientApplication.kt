package com.swisscom.health.des.cdr.client

import com.sun.jna.Platform
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import org.springframework.boot.actuate.autoconfigure.scheduling.ScheduledTasksObservabilityAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.appendText
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private const val SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY = "spring.config.additional-location"
private const val SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV1 = "SPRING_CONFIG_ADDITIONALLOCATION" // correct
private const val SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV2 = "SPRING_CONFIG_ADDITIONAL_LOCATION" // technically not correct, but supported by SpringBoot
private const val SPRING_BOOT_LOGBACK_CONFIG_LOCATION_PROPERTY = "logging.config"
private const val SPRING_BOOT_LOGBACK_CONFIG_LOCATION_ENV = "LOGGING_CONFIG"
private const val LOGBACK_CONFIGURATION_FILE_PROPERTY = "logback.configurationFile"

/**
 * Spring Boot entry point
 */
@SpringBootApplication(exclude = [ScheduledTasksObservabilityAutoConfiguration::class])
@EnableConfigurationProperties(CdrClientConfig::class)
@EnableScheduling
internal class CdrClientApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>): Unit = runCatching {
    initConfig()
    upgradeConfig()
    runApplication<CdrClientApplication>(*args)
}.fold(
    onSuccess = {},
    onFailure = { t ->
        logMsg { "application exit due to unhandled exception: '$t'" }
        throw t
    }
)

/**
 * Creates application and logging configuration files for new installations (or if either configuration
 * has been deleted, for whatever reason).
 */
private fun initConfig() {
    val serviceConfigLocation: String? =
        // env variables have higher precedence for SpringBoot configuration than system properties
        System.getenv(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV1).takeUnless { it.isNullOrBlank() }
            ?: System.getenv(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV2).takeUnless { it.isNullOrBlank() }
            ?: System.getProperty(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY).takeUnless { it.isNullOrBlank() }
    serviceConfigLocation
        ?.takeIf { it.isNotBlank() }
        ?.let { configLocation: String -> Path.of(configLocation) }
        ?.let { configPath -> ConfigInit.initSpringBootConfig(configPath) }
        ?.let { absoluteConfigPath ->
            // update property with the absolute path to the SpringBoot configuration file
            System.setProperty(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY, absoluteConfigPath.toString())
            Unit
        }
        ?: logMsg {
            "No SpringBoot configuration file location configured via environment variable '$SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV1' " +
                    "or '$SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV2', or system property '$SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY', " +
                    "skipping initialization of SpringBoot configuration."
        }


    val logbackConfigLocation: String? =
        // env variables have higher precedence for SpringBoot configuration than system properties
        System.getProperty(SPRING_BOOT_LOGBACK_CONFIG_LOCATION_PROPERTY).takeUnless { it.isNullOrBlank() }
            ?: System.getenv(SPRING_BOOT_LOGBACK_CONFIG_LOCATION_ENV).takeUnless { it.isNullOrBlank() }
    logbackConfigLocation
        ?.takeIf { it.isNotBlank() }
        ?.let { configLocation: String -> Path.of(configLocation) }
        ?.let { configPath -> ConfigInit.initLogbackConfig(configPath) }
        ?.let { absoluteConfigPath ->
            // update property with the absolute path to the logback configuration file
            System.setProperty(SPRING_BOOT_LOGBACK_CONFIG_LOCATION_PROPERTY, absoluteConfigPath.toString())
            // overwrite the logback-ui config that is set because of the conveyor.conf settings
            System.setProperty(LOGBACK_CONFIGURATION_FILE_PROPERTY, absoluteConfigPath.toString())
            Unit
        }
        ?: logMsg {
            "No Logback configuration file location configured via environment variable '$SPRING_BOOT_LOGBACK_CONFIG_LOCATION_ENV' or " +
                    "system property '$SPRING_BOOT_LOGBACK_CONFIG_LOCATION_PROPERTY', skipping initialization of Logback configuration."
        }
}

/**
 * Upgrades existing configuration files to the latest version, adding new configuration items with
 * default values where necessary.
 */
private fun upgradeConfig() {
    val serviceConfigLocation: String? =
        // env variables have higher precedence for SpringBoot configuration than system properties
        System.getenv(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV1).takeUnless { it.isNullOrBlank() }
            ?: System.getenv(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV2).takeUnless { it.isNullOrBlank() }
            ?: System.getProperty(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY).takeUnless { it.isNullOrBlank() }
    serviceConfigLocation
        ?.let { configLocation: String -> configLocation.takeIf { it.isNotBlank() } }
        ?.let { configLocation: String -> Path.of(configLocation) }
        ?.let { configLocation: Path -> configLocation.takeIf { it.isRegularFile() } }
        ?.let { configLocation: Path ->
            when (val upgradeResult = ConfigUpgrade.applyPendingUpgradeSteps(configLocation)) {
                is UpgradeResult.AlreadyAtLatestVersion -> logMsg {
                    "Configuration at '${configLocation.absolute()}' was already at the latest version, no upgrade was performed"
                }

                is UpgradeResult.Success -> logMsg {
                    "Configuration at '${configLocation.absolute()}' successfully upgraded to version '${upgradeResult.version}'"
                }

                is UpgradeResult.Failure -> {
                    logMsg { "Failed to upgrade configuration at '${configLocation.absolute()}' to version '${upgradeResult.version}'" }
                    error("Failed to upgrade configuration at '${configLocation.absolute()}' to version '${upgradeResult.version}'") // causes the JVM to exit
                }
            }
        }
        ?: logMsg {
            "No SpringBoot configuration file location configured via environment variable '$SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV1' or " +
                    "'$SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_ENV2', or system property '$SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY' " +
                    "or no file exists at configured location, skipping upgrade of SpringBoot configuration"
        }
}

private val tmpLogFile: Path? by lazy {
    val fileOrDirStr: String? = System.getProperty(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY)
        .takeUnless { it.isNullOrBlank() }
        ?: System.getProperty("java.io.tmpdir")
            // MS Windows services running as `SYSTEM` don't know where the tmp dir is, nor do they have a user.home
            .takeUnless { it.isNullOrBlank() }
        ?: "C:\\ProgramData"
            // we assume that all Linux/macOS scenarios yield a value for `java.io.tmpdir`
            .takeIf { Platform.isWindows() }

    fileOrDirStr
        ?.let { pathStr: String ->
            runCatching {
                val fileOrDir = Path.of(pathStr)
                if (fileOrDir.isDirectory()) {
                    fileOrDir.resolve("cdr-service-init.log")
                } else {
                    fileOrDir.resolveSibling("cdr-service-init.log")
                }.also { file: Path ->
                    if (!file.exists(LinkOption.NOFOLLOW_LINKS)) {
                        file.createParentDirectories()
                        file.createFile()
                    }
                }
            }.getOrElse { t ->
                System.err.println("WARNING: could not create init log file, falling back to stdout: ${t.message}")
                null
            }
        }
}

fun logMsg(msgProducer: () -> String) {
    fun printlnF(msg: String) {
        if (tmpLogFile != null) {
            tmpLogFile!!.appendText(
                charset = Charsets.UTF_8,
                text = "$msg\n",
            )
        } else {
            // fallback in case we failed to create the logfile
            println(msg)
        }
    }

    when {
        // on Windows, we run as a service; stdout is not captured anywhere, so have to write to a file
        // to be able to identify issues during initialization
        Platform.isWindows() -> printlnF(msgProducer())
        // every other OS (we care about) is sane
        else -> println(msgProducer())
    }
}
