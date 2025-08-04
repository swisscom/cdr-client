package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.common.escalatingFind
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import org.springframework.boot.actuate.autoconfigure.scheduling.ScheduledTasksObservabilityAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val DEFAULT_CUSTOMER_CONFIG_FILE = "default-application-customer.yaml"
private const val SERVICE_LOGBACK_FILE = "logback-service.xml"
private const val SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY = "spring.config.additional-location"
private const val LOGBACK_CONFIGURATION_FILE_PROPERTY = "logback.configurationFile"
private const val SPRING_BOOT_LOGBACK_CONFIG_LOCATION_PROPERTY = "logging.config"

/**
 * Spring Boot entry point
 */
@SpringBootApplication(exclude = [ScheduledTasksObservabilityAutoConfiguration::class])
@EnableConfigurationProperties(CdrClientConfig::class)
@EnableScheduling
internal class CdrClientApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    initConfig()
    runApplication<CdrClientApplication>(*args)
}

private fun initConfig() {
    initLogbackConfig()
    initSpringBootConfig()
}

/**
 * Checks whether the system property `spring.config.additional-location` is set and if so, checks if
 * the file exists. If it does not exist, the default customer configuration file is copied to that
 * location. If the property value is a relative path, then it is resolved against the user's home
 * directory. This should only be the case under macOS where configuration, logs, etc., should go into
 * `$HOME/Library/...`. The system property is then updated with the absolute path to the customer
 * configuration file.
 */
private fun initSpringBootConfig() =
    // create a default customer configuration file if we are told to do so and if it does not exist yet
    System.getProperty(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY)
        ?.let { additionalConfigLocation: String ->
            val additionalConfigPath = Path.of(additionalConfigLocation)
            if (!additionalConfigPath.isAbsolute) {
                // should only be relevant for macOS where configuration, logs, etc., should go into `$HOME/Library/...`
                // on other platforms use absolute paths!
                val userHome: Path = requireNotNull(System.getProperty("user.home")) {
                    "User home directory is not set but is required to resolve the relative configuration path '$additionalConfigLocation'"
                }.run(Path::of)
                userHome.resolve(Path.of(additionalConfigLocation))
            } else {
                additionalConfigPath
            }
        }
        ?.absolute()
        ?.let { customerConfigFile: Path ->
            if (customerConfigFile.exists()) {
                // TODO: add check if the file is writable as soon as the Debian package installs the service with its own run-user
                //  and changes the ownership of the configuration files to that user
                check(customerConfigFile.isRegularFile() && customerConfigFile.isReadable()) {
                    "The customer configuration file path '$customerConfigFile' exists but does not point to a readable regular file."
                }
                println("customer application config file '$customerConfigFile' exists, skipping creation of default configuration file")
            } else {
                println("config file '$customerConfigFile' does not exist, creating default customer configuration file")
                val pwd: Path = ProcessHandle.current().info().command().get().let { cdrServiceCmd: String ->
                    Path.of(cdrServiceCmd).parent.absolute()
                }
                val defaultCustomerConfigFile: List<Path> = escalatingFind(DEFAULT_CUSTOMER_CONFIG_FILE, pwd)
                check(defaultCustomerConfigFile.size == 1) {
                    "Expected exactly one default customer configuration file with name '$DEFAULT_CUSTOMER_CONFIG_FILE', but found " +
                            "'${defaultCustomerConfigFile.size}' files: '$defaultCustomerConfigFile'; search started in '$pwd'"
                }
                println("found customer configuration template at: '${defaultCustomerConfigFile.first()}'")
                defaultCustomerConfigFile
                    .first()
                    .readText()
                    .also { defaultConfigContents: String ->
                        customerConfigFile.createParentDirectories()
                        customerConfigFile.writeText(defaultConfigContents)
                    }
                println("default customer configuration file created at")
            }
            customerConfigFile
        }
        ?.let { customerConfigFile: Path ->
            // update property with the absolute path to the customer configuration file
            System.setProperty(SPRING_BOOT_ADDITIONAL_CONFIG_FILE_LOCATION_PROPERTY, customerConfigFile.toString())
        }

/**
 * Checks whether the system property `logging.config` is set and if so, checks if the file exists.
 * If it does not exist, a default logback configuration file is created at that location. If the
 * property value is a relative path, then it is resolved against the user's home directory. This
 * should only be the case under macOS where configuration, logs, etc., should go into
 * `$HOME/Library/...`.The system property is then updated with the absolute path to the customer
 * configuration file.
 */
@Suppress("NestedBlockDepth", "LongMethod")
private fun initLogbackConfig() =
    System.getProperty(SPRING_BOOT_LOGBACK_CONFIG_LOCATION_PROPERTY)
        ?.let { logbackConfigLocation: String ->
            val logbackConfigPath = Path.of(logbackConfigLocation)
            if (!logbackConfigPath.isAbsolute) {
                // should only be relevant for macOS where configuration, logs, etc., should go into `$HOME/Library/Application Support/...`
                // on other platforms use absolute paths!
                val userHome: Path = requireNotNull(System.getProperty("user.home")) {
                    "User home directory is not set but is required to resolve the relative logback configuration path '$logbackConfigLocation'"
                }.run(Path::of)
                userHome.resolve(Path.of(logbackConfigLocation))
            } else {
                logbackConfigPath
            }

        }
        ?.absolute()
        ?.let { logbackConfigFile: Path ->
            if (logbackConfigFile.exists()) {
                check(logbackConfigFile.isRegularFile() && logbackConfigFile.isReadable()) {
                    "The logback configuration file path '$logbackConfigFile' exists but does not point to a readable regular file."
                }
                println("logback config file '$logbackConfigFile' exists, skipping creation of default configuration file")
            } else {
                println("logback config file '$logbackConfigFile' does not exist, creating default logback configuration file")
                val pwd: Path = ProcessHandle.current().info().command().get().let { cdrServiceCmd: String ->
                    Path.of(cdrServiceCmd).parent.absolute()
                }
                val defaultLogbackConfigFile: List<Path> = escalatingFind(SERVICE_LOGBACK_FILE, pwd)
                check(defaultLogbackConfigFile.size == 1) {
                    "Expected exactly one default logback configuration file with name '$SERVICE_LOGBACK_FILE', but found " +
                            "'${defaultLogbackConfigFile.size}' files: '$defaultLogbackConfigFile'; search started in '$pwd'"
                }
                println("found logback configuration template at: '${defaultLogbackConfigFile.first()}'")
                val logDir: Path =
                    requireNotNull(System.getProperty("cdr.client.log.directory")) {
                        "log directory system property 'cdr.client.log.directory' is not set"
                    }
                        .run(Path::of)
                        .run {
                            if (isAbsolute) {
                                this
                            } else {
                                // should only be relevant for macOS where configuration, logs, etc., should go into `$HOME/Library/Application Support/...`
                                // on other platforms use absolute paths!
                                requireNotNull(System.getProperty("user.home")) {
                                    "User home directory is not set but is required to resolve the relative log directory '$this'"
                                }.run(Path::of).resolve(this).absolute()
                            }
                        }
                logDir.createDirectories()
                defaultLogbackConfigFile
                    .first()
                    .readText()
                    .replace("@@LOG_DIR@@", logDir.toString())
                    .also { defaultConfigContents: String ->
                        logbackConfigFile.createParentDirectories()
                        logbackConfigFile.writeText(defaultConfigContents)
                    }
                println("default logback configuration file created at: '$logbackConfigFile'")
            }
            logbackConfigFile
        }
        ?.let { logbackConfigFile: Path ->
            // update property with the absolute path to the logback configuration file
            System.setProperty(SPRING_BOOT_LOGBACK_CONFIG_LOCATION_PROPERTY, logbackConfigFile.toString())
            // overwrite the logback-ui config that is set because of the conveyor.conf settings
            System.setProperty(LOGBACK_CONFIGURATION_FILE_PROPERTY, logbackConfigFile.toString())
        }
