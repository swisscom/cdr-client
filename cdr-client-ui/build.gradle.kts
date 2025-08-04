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

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            // Preview tooling added by template, but actually not needed/wrong for desktop previews:
            // https://youtrack.jetbrains.com/issue/CMP-4869
//            implementation(compose.components.uiToolingPreview)
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
    }
}

dependencies {
    // Use the configurations created by the Conveyor plugin to tell Gradle/Conveyor where to find the artifacts for each platform.
    linuxAmd64(compose.desktop.linux_x64)
    macAmd64(compose.desktop.macos_x64)
    macAarch64(compose.desktop.macos_arm64)
    windowsAmd64(compose.desktop.windows_x64)
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

// region Work around temporary Compose bugs.
configurations.all {
    attributes {
        // https://github.com/JetBrains/compose-jb/issues/1404#issuecomment-1146894731
        attribute(Attribute.of("ui", String::class.java), "awt")
    }
}
// endregion