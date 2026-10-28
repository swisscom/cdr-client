package com.swisscom.health.des.cdr.client

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
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
    private const val WINDOWS_SYSTEM_SID = "S-1-5-18"
    private const val WINDOWS_ADMINISTRATORS_SID = "S-1-5-32-544"

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

    @Suppress("TooGenericExceptionCaught")
    internal fun Path.removeWorldAccess(): Path =
        when {
            Platform.isWindows() -> {
                val serviceUserSid = currentWindowsUserSid()
                if (serviceUserSid == null) {
                    logMsg { "WARNING! Cannot identify the Windows service user; leaving ACLs unchanged for '$this'." }
                } else {
                    logMsg { "Removing all ACLs but for SYSTEM, Administrators, and the service user (SID '$serviceUserSid') from '$this'." }
                    val aclView = Files.getFileAttributeView(this, AclFileAttributeView::class.java)
                    val currentAcl = aclView.acl
                    val retainedAcl = if (serviceUserSid == WINDOWS_SYSTEM_SID) {
                        // service runs as SYSTEM: resolve the two well-known SIDs to their (localized) account names and compare
                        // against the principal names; avoids resolving arbitrary (e.g. domain) accounts
                        val wellKnownNames = listOf(WINDOWS_SYSTEM_SID, WINDOWS_ADMINISTRATORS_SID).map { it.toWindowsAccountName()?.lowercase() }
                        if (wellKnownNames.any { it == null }) null else currentAcl.filter { it.principal().name.lowercase() in wellKnownNames }
                    } else {
                        val retainedSids = setOf(WINDOWS_SYSTEM_SID, WINDOWS_ADMINISTRATORS_SID, serviceUserSid)
                        val sidByPrincipal = currentAcl.map { it.principal().name }.distinct().associateWith { it.toWindowsSid() }
                        if (sidByPrincipal.values.any { it == null }) null else currentAcl.filter { sidByPrincipal[it.principal().name] in retainedSids }
                    }
                    if (retainedAcl == null) {
                        logMsg { "WARNING! Cannot reliably identify all ACL principals; leaving ACLs unchanged for '$this'." }
                    } else {
                        try {
                            aclView.acl = retainedAcl
                        } catch (exception: Exception) {
                            // insufficient rights to modify the ACLs must prevent the startup; the exception is rethrown
                            logMsg { "ERROR! Cannot modify the ACLs of '$this': $exception" }
                            throw exception
                        }
                    }
                }
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

    @Suppress("TooGenericExceptionCaught")
    internal fun currentWindowsUserSid(): String? =
        try {
            val processToken = WinNT.HANDLEByReference()
            val currentProcess = Kernel32.INSTANCE.GetCurrentProcess()
            if (!Advapi32.INSTANCE.OpenProcessToken(currentProcess, WinNT.TOKEN_QUERY, processToken)) {
                throw Win32Exception(Native.getLastError())
            }
            try {
                val tokenUserInformation = WinNT.TOKEN_INFORMATION_CLASS.TokenUser
                val requiredBufferSize = IntByReference()

                // Windows requires two calls: first query the buffer size, then read the token user.
                Advapi32.INSTANCE.GetTokenInformation(processToken.value, tokenUserInformation, null, 0, requiredBufferSize)
                val bufferSizeQueryError = Native.getLastError()
                if (bufferSizeQueryError != WinError.ERROR_INSUFFICIENT_BUFFER || requiredBufferSize.value <= 0) {
                    throw Win32Exception(bufferSizeQueryError)
                }

                val tokenUser = WinNT.TOKEN_USER(requiredBufferSize.value)
                val userInformationRead = Advapi32.INSTANCE.GetTokenInformation(
                    processToken.value,
                    tokenUserInformation,
                    tokenUser,
                    requiredBufferSize.value,
                    requiredBufferSize
                )
                if (!userInformationRead) {
                    throw Win32Exception(Native.getLastError())
                }

                Advapi32Util.convertSidToStringSid(tokenUser.User.Sid)
            } finally {
                if (!Kernel32.INSTANCE.CloseHandle(processToken.value)) {
                    logMsg { "WARNING! Could not close the Windows process token (error ${Native.getLastError()})." }
                }
            }
        } catch (exception: Exception) {
            logMsg { "WARNING! Could not read the Windows process user SID: $exception" }
            null
        } catch (error: LinkageError) {
            logMsg { "WARNING! Native Windows identity APIs are unavailable: $error" }
            null
        }

    @Suppress("TooGenericExceptionCaught")
    private fun String.toWindowsSid(): String? =
        try {
            Advapi32Util.getAccountByName(this).sidString
        } catch (exception: Exception) {
            logMsg { "Could not resolve ACL principal '$this' to a Windows SID; it will not be retained: $exception" }
            null
        }

    @Suppress("TooGenericExceptionCaught")
    private fun String.toWindowsAccountName(): String? =
        try {
            Advapi32Util.getAccountBySid(this).fqn
        } catch (exception: Exception) {
            logMsg { "Could not resolve well-known SID '$this' to an account name: $exception" }
            null
        }
}
