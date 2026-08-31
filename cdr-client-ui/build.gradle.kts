group = "com.swisscom.health.des.cdr.client.ui"


plugins {
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.conveyor)
}

kotlin {
    jvm("desktop")

    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }

    compilerOptions {
        progressiveMode = true
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }

    sourceSets {
        val desktopMain = getByName("desktopMain")
        val desktopTest = getByName("desktopTest")

        commonMain.dependencies {
            // Dependency aliases are deprecated, but the BOM is not available yet; keeping the aliases until the BOM arrives
            // https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html#deprecated-dependency-aliases
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            // https://github.com/alorma/Compose-Settings
            implementation(libs.uitiles)
            implementation(libs.okhttp)
            implementation(libs.jna)
            implementation(libs.pgreze.kotlin.process)
            implementation(projects.cdrClientCommon)
            runtimeOnly(projects.cdrClientService) {
                isTransitive = true
                because("So Conveyor includes the service (plain) jar and its dependencies into the desktop application build")
            }
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            // Conveyor auto update control
            implementation(libs.conveyor.control)
            implementation(libs.compose.native.tray)
        }

        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.mockk)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    // Dependency aliases are deprecated, but the BOM is not available yet; keeping the aliases until the BOM arrives
    // https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html#deprecated-dependency-aliases
    //
    // Use the configurations created by the Conveyor plugin to tell Gradle/Conveyor where to find the artifacts for each platform.
    linuxAmd64(compose.desktop.linux_x64)
    linuxAarch64(compose.desktop.linux_arm64)
    macAmd64(compose.desktop.macos_x64)
    macAarch64(compose.desktop.macos_arm64)
    windowsAmd64(compose.desktop.windows_x64)
    windowsAarch64(compose.desktop.windows_arm64)
}

compose.desktop {
    application {
        mainClass = "com.swisscom.health.des.cdr.client.ui.UiMainKt"

        nativeDistributions {
            packageName = project.name
            packageVersion = project.version.toString()
        }
    }
}

tasks.withType<Jar> {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}
