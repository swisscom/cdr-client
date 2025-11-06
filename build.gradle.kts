import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.kotlin.dsl.withType
import org.gradle.api.GradleException

allprojects {
    version = "4.3.2-SNAPSHOT"
}

plugins {
    // 'apply false' is necessary to avoid the plugins to be loaded multiple times in each subproject's classloader
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.docker.compose) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    kotlin("jvm").version(libs.versions.kotlin.lang) apply false
    kotlin("plugin.spring").version(libs.versions.kotlin.lang) apply false
    // https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.kotlin.configuration-properties
    // KAPT is end-of-life, but KSP is not supported yet: https://github.com/spring-projects/spring-boot/issues/28046
    kotlin("kapt").version(libs.versions.kotlin.lang) apply false

    // but we actually want to run detekt in all subprojects
    alias(libs.plugins.detekt)
}

val detektPluginId = libs.plugins.detekt.get().pluginId

subprojects {
    apply {
        plugin(detektPluginId)
    }

    detekt {
        config.from(rootProject.files("config/detekt.yml"))
        buildUponDefaultConfig = false // preconfigure defaults
        allRules = true
        parallel = true
    }

    tasks.withType<Detekt> {
        reports {
            xml.required.set(true)
            html.required.set(false)
            sarif.required.set(false)
            txt.required.set(false)
        }
    }

    project.afterEvaluate {
        // https://github.com/detekt/detekt/issues/6198#issuecomment-2265183695
        configurations.matching { it.name == "detekt" }.all {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
                }
            }
        }
    }
}

// NOTE: you should to run this target or manually update `gradle/gradle-daemon-jvm.properties` if we change the Java version!
tasks.updateDaemonJvm {
    languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
}

// =============================================================================
// .NET 8 SDK Management and Watchdog Build Tasks
// =============================================================================

val dotnetVersion = "8.0.413"
val dotnetInstallDir = layout.buildDirectory.dir("dotnet-sdk").get().asFile
val dotnetExecutable = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
    File(dotnetInstallDir, "dotnet.exe")
} else {
    File(dotnetInstallDir, "dotnet")
}

/**
 * Checks if .NET 8 SDK is available on the system or in our local installation
 */
fun isDotnetAvailable(): Boolean {
    return try {
        // First check system-wide installation
        val systemCheck = ProcessBuilder("dotnet", "--version")
            .redirectErrorStream(true)
            .start()
        systemCheck.waitFor()
        if (systemCheck.exitValue() == 0) {
            val version = systemCheck.inputStream.bufferedReader().readText().trim()
            logger.info("Found system .NET SDK version: $version")
            return version.startsWith("8.")
        }
        false
    } catch (_: Exception) {
        // Check our local installation
        if (dotnetExecutable.exists()) {
            try {
                val localCheck = ProcessBuilder(dotnetExecutable.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()
                localCheck.waitFor()
                if (localCheck.exitValue() == 0) {
                    val version = localCheck.inputStream.bufferedReader().readText().trim()
                    logger.info("Found local .NET SDK version: $version")
                    return version.startsWith("8.")
                }
            } catch (localE: Exception) {
                logger.debug("Local .NET check failed: ${localE.message}")
            }
        }
        false
    }
}

/**
 * Downloads and installs .NET 8 SDK if not available
 */
tasks.register("ensureDotnetSdk") {
    group = "dotnet"
    description = "Ensures .NET 8 SDK is available, downloads if necessary"
    
    outputs.file(dotnetExecutable)
    
    doLast {
        if (isDotnetAvailable()) {
            logger.info(".NET 8 SDK is already available")
            return@doLast
        }
        
        logger.info("Downloading .NET 8 SDK...")
        dotnetInstallDir.mkdirs()
        
        val os = org.gradle.internal.os.OperatingSystem.current()
        val downloadUrl = when {
            os.isWindows -> "https://builds.dotnet.microsoft.com/dotnet/Sdk/$dotnetVersion/dotnet-sdk-$dotnetVersion-win-x64.zip"
            os.isMacOsX -> "https://builds.dotnet.microsoft.com/dotnet/Sdk/$dotnetVersion/dotnet-sdk-$dotnetVersion-osx-x64.tar.gz"
            os.isLinux -> "https://builds.dotnet.microsoft.com/dotnet/Sdk/$dotnetVersion/dotnet-sdk-$dotnetVersion-linux-x64.tar.gz"
            else -> {
                logger.error("Unsupported operating system: ${os.name}. Supported platforms: Windows, macOS, Linux")
                throw GradleException("Unsupported operating system for automatic .NET SDK download: ${os.name}. Please install .NET 8 SDK manually.")
            }
        }
        
        val downloadFile = File(temporaryDir, "dotnet-sdk.${if (os.isWindows) "zip" else "tar.gz"}")
        
        logger.info("Downloading from: $downloadUrl")
        ant.invokeMethod("get", mapOf(
            "src" to downloadUrl,
            "dest" to downloadFile,
            "verbose" to true
        ))
        
        logger.info("Extracting .NET SDK...")
        if (os.isWindows) {
            copy {
                from(zipTree(downloadFile))
                into(dotnetInstallDir)
            }
        } else if (os.isMacOsX || os.isLinux) {
            ProcessBuilder("tar", "-xzf", downloadFile.absolutePath, "-C", dotnetInstallDir.absolutePath, "--strip-components=0")
                .inheritIO()
                .start()
                .waitFor()
        } else {
            // This should not happen due to the check above, but just in case
            throw GradleException("Unsupported platform for extraction: ${os.name}")
        }
        
        // Make dotnet executable on Unix systems and set proper permissions
        if (!os.isWindows) {
            dotnetExecutable.setExecutable(true)
            // Also make sure all executables in the SDK are executable
            ProcessBuilder("find", dotnetInstallDir.absolutePath, "-name", "dotnet", "-type", "f", "-exec", "chmod", "+x", "{}", ";")
                .inheritIO()
                .start()
                .waitFor()
            ProcessBuilder("find", dotnetInstallDir.absolutePath, "-name", "*.dll", "-type", "f", "-exec", "chmod", "644", "{}", ";")
                .inheritIO()
                .start()
                .waitFor()
        }
        
        logger.info(".NET 8 SDK installed successfully at: ${dotnetInstallDir.absolutePath}")
    }
}

/**
 * Gets the dotnet command to use (prioritizes our local installation)
 */
fun getDotnetCommand(): String {
    // First check if we have a local installation
    if (dotnetExecutable.exists()) {
        try {
            val localCheck = ProcessBuilder(dotnetExecutable.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            localCheck.waitFor()
            if (localCheck.exitValue() == 0) {
                val version = localCheck.inputStream.bufferedReader().readText().trim()
                if (version.startsWith("8.")) {
                    logger.info("Using local .NET SDK: $version at ${dotnetExecutable.absolutePath}")
                    return dotnetExecutable.absolutePath
                }
            }
        } catch (e: Exception) {
            logger.debug("Local .NET check failed: ${e.message}")
        }
    }
    
    // Fall back to system installation only if local doesn't work
    return try {
        val systemCheck = ProcessBuilder("dotnet", "--version").start()
        systemCheck.waitFor()
        if (systemCheck.exitValue() == 0) {
            val version = systemCheck.inputStream.bufferedReader().readText().trim()
            if (version.startsWith("8.")) {
                logger.info("Using system .NET SDK: $version")
                return "dotnet"
            }
        }
        // If system version is not .NET 8, still prefer our local installation
        dotnetExecutable.absolutePath
    } catch (_: Exception) {
        dotnetExecutable.absolutePath
    }
}

/**
 * Restores NuGet packages for the watchdog project
 */
tasks.register("dotnetRestore") {
    group = "dotnet"
    description = "Restores NuGet packages for the CDR Client Watchdog"
    
    dependsOn("ensureDotnetSdk")
    
    inputs.file("cdr-client-watchdog/CdrClientWatchdog.csproj")
    outputs.file("cdr-client-watchdog/obj/project.assets.json")
    
    doLast {
        val dotnetCmd = getDotnetCommand()
        logger.info("Using dotnet command: $dotnetCmd")
        
        val processBuilder = ProcessBuilder(dotnetCmd, "restore")
            .directory(file("cdr-client-watchdog"))
            .inheritIO()
        
        // Set DOTNET_ROOT if we're using our local installation
        if (dotnetCmd == dotnetExecutable.absolutePath) {
            processBuilder.environment().apply {
                put("DOTNET_ROOT", dotnetInstallDir.absolutePath)
                put("DOTNET_CLI_HOME", dotnetInstallDir.absolutePath)
            }
        }
        
        val process = processBuilder.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("dotnet restore failed with exit code $exitCode")
        }
    }
}

/**
 * Builds the CDR Client Watchdog service
 */
tasks.register("buildWatchdog") {
    group = "dotnet"
    description = "Builds the CDR Client Watchdog Windows service"
    
    // Ensure .NET SDK is available if not present on system
    dependsOn("ensureDotnetSdk")
    
    inputs.files(fileTree("cdr-client-watchdog") {
        include("**/*.cs", "**/*.csproj", "**/*.json", "build.sh", "build.bat")
    })
    outputs.dir("cdr-client-watchdog/publish")
    
    doLast {
        logger.info("Building CDR Client Watchdog using build.sh script")
        
        val buildScript = if (System.getProperty("os.name").lowercase().contains("windows")) {
            "build.bat" // Use batch script on Windows
        } else {
            "./build.sh" // Use shell script on Linux/WSL
        }
        
        val processBuilder = ProcessBuilder(buildScript, "true") // Pass "true" for self-contained
            .directory(file("cdr-client-watchdog"))
            .inheritIO()
        
        val process = processBuilder.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Watchdog build script failed with exit code $exitCode")
        }
        
        logger.info("CDR Client Watchdog built and published successfully")
    }
}

/**
 * Cleans the watchdog build artifacts
 */
tasks.register("cleanWatchdog") {
    group = "dotnet"
    description = "Cleans CDR Client Watchdog build artifacts"
    
    doLast {
        delete("cdr-client-watchdog/bin", "cdr-client-watchdog/obj", "cdr-client-watchdog/publish")
    }
}

// Create a comprehensive build task that includes watchdog
tasks.register("buildAll") {
    group = "build"
    description = "Builds all projects including the CDR Client Watchdog"
    dependsOn("buildWatchdog")
    dependsOn(subprojects.map { "${it.path}:build" })
}

// Create a comprehensive clean task that includes watchdog
tasks.register("cleanAll") {
    group = "build"
    description = "Cleans all projects including the CDR Client Watchdog"
    dependsOn("cleanWatchdog")
    dependsOn(subprojects.map { "${it.path}:clean" })
}
