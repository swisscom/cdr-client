package com.swisscom.health.des.cdr.client

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinNT
import com.swisscom.health.des.cdr.client.common.escalatingFind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Copies the default Logback configuration file to its destination, if it does not already exist.
 */
object ConfigInit {
    private const val DEFAULT_CUSTOMER_CONFIG_FILE = "default-application-customer.yaml"
    private const val SERVICE_LOGBACK_FILE = "logback-service.xml"

    /**
     * Checks if a configuration file already exists at [configPath]. If it does not exist, the default
     * customer configuration file is copied to that location. If the property value is a relative path,
     * then it is resolved against the user's home directory. This should only be the case under macOS
     * where configuration, logs, etc., should go into `$HOME/Library/...`. The system property is then
     * updated with the absolute path to the customer configuration file.
     *
     * @param configPath path to external SpringBoot configuration file; either absolute or relative
     * @return the absolute path of the SpringBoot configuration file
     */
    fun initSpringBootConfig(configPath: Path): Path =
        if (!configPath.isAbsolute) {
            // should only be relevant for macOS where configuration, logs, etc., should go into `$HOME/Library/...`
            // on other platforms use absolute paths!
            val userHome: Path = requireNotNull(System.getProperty("user.home")) {
                "User home directory is not set but is required to resolve the relative configuration path '$configPath'"
            }.run(Path::of)
            userHome.resolve(configPath)
        } else {
            configPath
        }
            .absolute()
            .let { customerConfigFile: Path ->
                if (customerConfigFile.exists()) {
                    // TODO: add check if the file is writable as soon as the Debian package installs the service with its own run-user
                    //  and changes the ownership of the configuration files to that user
                    check(customerConfigFile.isRegularFile() && customerConfigFile.isReadable()) {
                        "The customer configuration file path '$customerConfigFile' exists but does not point to a readable, regular file."
                    }
                    customerConfigFile.removeWorldAccess()
                    logMsg { "customer application config file '$customerConfigFile' exists, skipping creation of default configuration file" }
                } else {
                    logMsg { "config file '$customerConfigFile' does not exist, creating default customer configuration file" }
                    val pwd: Path = ProcessHandle.current().info().command().get().let { cdrServiceCmd: String ->
                        Path.of(cdrServiceCmd).parent.absolute()
                    }
                    val defaultCustomerConfigFile: List<Path> = if (Path.of("cdr-client-service", "conf", DEFAULT_CUSTOMER_CONFIG_FILE).exists()) {
                        listOf(Path.of("cdr-client-service", "conf", DEFAULT_CUSTOMER_CONFIG_FILE))
                    } else {
                        escalatingFind(DEFAULT_CUSTOMER_CONFIG_FILE, pwd)
                    }
                    check(defaultCustomerConfigFile.size == 1) {
                        "Expected exactly one default customer configuration file with name '$DEFAULT_CUSTOMER_CONFIG_FILE', but found " +
                                "'${defaultCustomerConfigFile.size}' files: '$defaultCustomerConfigFile'; search started in '$pwd'"
                    }
                    logMsg { "found customer configuration template at: '${defaultCustomerConfigFile.first().absolute()}'" }
                    defaultCustomerConfigFile
                        .first()
                        .readText()
                        .also { defaultConfigContents: String ->
                            customerConfigFile.createParentDirectories()
                            customerConfigFile.writeText(text = defaultConfigContents)
                            customerConfigFile.removeWorldAccess()
                        }
                    logMsg { "default customer configuration file created at '${customerConfigFile}'" }
                }
                customerConfigFile
            }

    /**
     * Checks if a configuration file already exists at [configPath]. If it does not exist, a default
     * logback configuration file is created at that location. If the property value is a relative path,
     * then it is resolved against the user's home directory. This should only be the case under macOS
     * where configuration, logs, etc., should go into `$HOME/Library/...`.The system property is then
     * updated with the absolute path to the customer configuration file.
     *
     * @param configPath path to external Logback configuration file; either absolute or relative
     * @return the absolute path of the Logback configuration file
     */
    @Suppress("NestedBlockDepth", "LongMethod")
    fun initLogbackConfig(configPath: Path): Path =
        if (!configPath.isAbsolute) {
            // should only be relevant for macOS where configuration, logs, etc., should go into `$HOME/Library/Application Support/...`
            // on other platforms use absolute paths!
            val userHome: Path = requireNotNull(System.getProperty("user.home")) {
                "User home directory is not set but is required to resolve the relative logback configuration path '$configPath'"
            }.run(Path::of)
            userHome.resolve(configPath)
        } else {
            configPath
        }
            .absolute()
            .let { logbackConfigFile: Path ->
                if (logbackConfigFile.exists()) {
                    check(logbackConfigFile.isRegularFile() && logbackConfigFile.isReadable()) {
                        "The logback configuration file path '$logbackConfigFile' exists but does not point to a readable regular file."
                    }
                    logMsg { "logback config file '$logbackConfigFile' exists, skipping creation of default configuration file" }
                } else {
                    logMsg { "logback config file '$logbackConfigFile' does not exist, creating default logback configuration file" }
                    val pwd: Path = ProcessHandle.current().info().command().get().let { cdrServiceCmd: String ->
                        Path.of(cdrServiceCmd).parent.absolute()
                    }
                    val defaultLogbackConfigFile: List<Path> = escalatingFind(SERVICE_LOGBACK_FILE, pwd)
                    check(defaultLogbackConfigFile.size == 1) {
                        "Expected exactly one default logback configuration file with name '$SERVICE_LOGBACK_FILE', but found " +
                                "'${defaultLogbackConfigFile.size}' files: '$defaultLogbackConfigFile'; search started in '$pwd'"
                    }
                    logMsg { "found logback configuration template at: '${defaultLogbackConfigFile.first()}'" }
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
                    logMsg { "default logback configuration file created at: '$logbackConfigFile'" }
                }
                logbackConfigFile
            }

    private fun Path.removeWorldAccess(): Path =
        when {
            Platform.isWindows() -> {
                val serviceUserSid = currentWindowsUserSid()
                logMsg { "Removing all ACLs but for SYSTEM, Administrators, and the service user from '$this'." }
                val aclView = Files.getFileAttributeView(this, AclFileAttributeView::class.java)
                aclView.acl = aclView.acl.filter { aclEntry ->
                    val principalName = aclEntry.principal().name
                    principalName.lowercase().matches("""^.*(system|administrators|administratoren|administrateurs|amministratori)$""".toRegex()) ||
                            principalName.toWindowsSid() == serviceUserSid
                }
                logMsg { "Remaining ACL entries: ${aclView.acl}" }
            }

            Platform.isLinux() -> {
                logMsg { "Removing world permissions from '$this'." }
                val worldPermissions = setOf(
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE
                )

                Files.setPosixFilePermissions(this, Files.getPosixFilePermissions(this) - worldPermissions)
            }

            Platform.isMac() -> {
                logMsg { "macOS detected; permissions of '$this' stay at their defaults." }
                // NOOP; all resources are stored under the user's home directory on macOS -> no need for extra protection
            }

            else -> logMsg { "WARNING! Unsupported platform '${Platform.getOSType()}' - cannot set file permissions for file at '$this'" }
        }.let { _ ->
            this
        }

    private fun currentWindowsUserSid(): String {
        val token = WinNT.HANDLEByReference()
        check(Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            "Could not open the current process token to identify the Windows service user (error ${Kernel32.INSTANCE.GetLastError()})."
        }
        return try {
            Advapi32Util.getTokenAccount(token.value).sidString
        } finally {
            Kernel32.INSTANCE.CloseHandle(token.value)
        }
    }

    private fun String.toWindowsSid(): String? =
        try {
            Advapi32Util.getAccountByName(this).sidString
        } catch (exception: Win32Exception) {
            logMsg { "Could not resolve ACL principal '$this' to a Windows SID; it will not be retained: $exception" }
            null
        }

}
