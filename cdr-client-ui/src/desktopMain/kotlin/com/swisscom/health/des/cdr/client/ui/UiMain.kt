package com.swisscom.health.des.cdr.client.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.github.pgreze.process.ProcessResult
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
import com.kdroid.composetray.tray.api.Tray
import com.swisscom.health.des.cdr.client.common.Constants.CONFIG_CHANGE_EXIT_CODE
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.escalatingFind
import com.swisscom.health.des.cdr.client.ui.CdrConfigViewModel.Companion.STATUS_CHECK_DELAY
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Swisscom_Lifeform_Colour_RGB_icon
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.app_name
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_client_status
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_exit
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_open_application_window
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.status_unknown
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi

private const val UI_LOGBACK_FILE = "logback-ui.xml"
private const val LOGBACK_CONFIGURATION_FILE_PROPERTY = "logback.configurationFile"

/*
  A note on the logback configuration (used by `io.github.oshai.kotlinlogging`):
  The location of the external logback configuration file is set with a system property.
  The location it points to is platform-dependent. And in the case of macOS, the initial
  value is a relative path that gets resolved against the executing user's home
  directory, and that resolved path gets stored in the same system property in
  `initialLogbackConfig()`.
  Because the logback configuration file might not exist yet and/or its final location
  may not be known until `initLogbackConfig()` has completed, you cannot use
  kotlin-logging in this kotlin file, as it would initialize Logback before the external
  configuration file may be set.

  So, DO NOT DEFINE A LOGGER LIKE THIS:
  `private val logger = KotlinLogging.logger {}`
 */

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
fun main() = application {
    initLogbackConfig()

    var isWindowVisible: Boolean by remember { mutableStateOf(true) }
    val cdrConfigViewModel: CdrConfigViewModel = remember { CdrConfigViewModel(cdrClientApiClient = CdrClientApiClient()) }

    // I have found no way to push this down into the CdrConfigScreen composable;
    // the client status query runs in a different coroutine scope, so it won't be canceled automatically when the main window is closed
    var statusQueryJob: Job by remember { mutableStateOf(Job().apply { complete() }) }
    LaunchedEffect(isWindowVisible) {
        statusQueryJob.cancelAndJoin()
        while (isWindowVisible) {
            statusQueryJob = cdrConfigViewModel.queryClientServiceStatus(CdrClientApiClient.RetryStrategy.EXPONENTIAL)
            statusQueryJob.join()
            delay(STATUS_CHECK_DELAY)
        }
    }

    Window(
        onCloseRequest = { isWindowVisible = false },
        visible = isWindowVisible,
        title = stringResource(Res.string.app_name),
        icon = painterResource(Res.drawable.Swisscom_Lifeform_Colour_RGB_icon), // not rendered on Ubuntu
    ) {
        CdrConfigScreen(
            viewModel = cdrConfigViewModel,
            remoteViewValidations = CdrConfigViewRemoteValidations(
                cdrClientApiClient = CdrClientApiClient()
            )
        )
    }

    // I am not using JetBrains' default Tray() implementation:
    // The AWT-based tray looks ugly (on Linux, others unknown at the time of writing), but it works;
    // notifications sent via tray state pop up but are horribly ugly
    // Do not use `com.dorkbox:SystemTray`, it crashes the desktop session on Ubuntu 24.04.!
    // We use https://github.com/kdroidFilter/ComposeNativeTray, appears to work nicely (on Linux, others unknown at the time of writing)
    // FIXME: client service status must be updated from model! But cannot figure out how to update the model on demand on opening the tray menu?
    //   We need on-demand update because status polling is suspended while the main window is closed.
    CdrSystemTray(
        primaryAction = { isWindowVisible = true }
    )

    // start cdr client service if the UI is configured to control the service
    if (uiIsServiceController()) {
        registerCdrClientServiceShutdownHook()
        LaunchCdrClientService()
    }
}

private fun registerCdrClientServiceShutdownHook() =
    // Needed for macOS only: If "Quit" is selected from the dock icon's context menu, the main
    // process is killed, but not its children. I have no idea why and how. If the UI process
    // receives a SIGTERM, then the child process gets terminated as well as expected.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            ProcessHandle.current().children()
                .forEach { childProcess: ProcessHandle ->
                    logInfo { "Terminating child process '${childProcess.info().commandLine()}'" }
                    childProcess.destroy()
                }
        }
    )

@Composable
private fun LaunchCdrClientService() =
    findClientServiceExecutable()?.let { cdrServiceExecutable: Path ->
        LaunchedEffect(Unit) {
            do {
                logInfo { "(Re-)start ${clientServiceExecutableForPlatform()}" }
                val processResult: ProcessResult =
                    process(
                        cdrServiceExecutable.toString(),
                        stdout = Redirect.PRINT,
                        stderr = Redirect.PRINT,
                    )
                logInfo { "${clientServiceExecutableForPlatform()} exited with code ${processResult.resultCode}" }
            } while (processResult.resultCode == CONFIG_CHANGE_EXIT_CODE)
            logInfo { "${clientServiceExecutableForPlatform()} stopped with non-restartable exit code." }
        }
    }

@Composable
private fun ApplicationScope.CdrSystemTray(
    toolTip: String = stringResource(Res.string.app_name),
    labelPrimaryAction: String = stringResource(Res.string.label_open_application_window),
    labelStatusItem: String = "${stringResource(Res.string.label_client_status)}: ${stringResource(Res.string.status_unknown)}",
    labelExit: String = stringResource(Res.string.label_exit),
    primaryAction: (() -> Unit)? = null,
) =
    Tray(
        iconContent = {
            Icon(
                painter = painterResource(Res.drawable.Swisscom_Lifeform_Colour_RGB_icon),
                contentDescription = EMPTY_STRING,
                tint = Color.Unspecified,
                modifier = Modifier.fillMaxSize()
                    // neither of these options works (on linux, at least); intention was to update the client service status when the tray icon is clicked
//                    .onClick(onClick = { logger.info { "updating client service status because tray icon was clicked" } })
//                    .clickable(onClick = { logger.info { "updating client service status because tray icon was clicked" } })
            )
        },
        tooltip = toolTip,
        primaryAction = primaryAction,
        primaryActionLabel = labelPrimaryAction
    ) {
        Item(
            label = labelStatusItem,
            isEnabled = false,
            onClick = {}
        )
        Item(
            label = labelExit,
            onClick = ::exitApplication
        )
    }

/**
 * Checks whether the system property `logback.configurationFile` is set and if so, checks if the
 * file exists. If it does not exist, a default logback configuration file is created at that
 * location. If the property value is a relative path, then it is resolved against the user's home
 * directory. This should only be the case under macOS where configuration, logs, etc., should go
 * into `$HOME/Library/...`.The system property is then updated with the absolute path to the
 * customer configuration file.
 */
@Suppress("NestedBlockDepth", "LongMethod")
private fun initLogbackConfig() =
    System.getProperty(LOGBACK_CONFIGURATION_FILE_PROPERTY)
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

                val configContent = logbackConfigFile.readText()
                val logsDirectoryEnvVar = System.getenv("LOGS_DIRECTORY")
                // should only be relevant for LINUX installations, as the logback-ui.xml file is copied as is to the destination by conveyor
                if (configContent.contains("@@LOG_DIR@@") && logsDirectoryEnvVar == null) {
                    println("logback config file exists but contains unreplaced @@LOG_DIR@@ placeholder and LOGS_DIRECTORY is not set")
                    val (logDir, _) = determineLogDirectoryAndDefaultLogbackConfigFile()
                    println("Using log directory: $logDir")
                    val updatedContent = configContent.replace("@@LOG_DIR@@", logDir.toString())
                    logbackConfigFile.writeText(updatedContent)
                    println("Updated logback configuration file at: '$logbackConfigFile'")
                }
            } else {
                println("logback config file '$logbackConfigFile' does not exist, creating default logback configuration file")
                val (logDir: Path, defaultLogbackConfigFile: List<Path>) = determineLogDirectoryAndDefaultLogbackConfigFile()
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
            println("Setting system property '$LOGBACK_CONFIGURATION_FILE_PROPERTY' to '$logbackConfigFile'")
            System.setProperty(LOGBACK_CONFIGURATION_FILE_PROPERTY, logbackConfigFile.toString())
        }

private fun determineLogDirectoryAndDefaultLogbackConfigFile(): Pair<Path, List<Path>> {
    val pwd: Path = ProcessHandle.current().info().command().get().let { cdrServiceCmd: String ->
        Path.of(cdrServiceCmd).parent.absolute()
    }
    val defaultLogbackConfigFile: List<Path> = escalatingFind(UI_LOGBACK_FILE, pwd)
    check(defaultLogbackConfigFile.size == 1) {
        "Expected exactly one default logback configuration file with name '$UI_LOGBACK_FILE', but found " +
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
    return logDir to defaultLogbackConfigFile
}
