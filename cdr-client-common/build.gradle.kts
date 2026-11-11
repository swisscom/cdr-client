group = "com.swisscom.health.des.cdr.client.common"

plugins {
    kotlin("jvm").version(libs.versions.kotlin.lang)
    alias(libs.plugins.kotlinx.serialization)
    `maven-publish`
    idea
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.jackson.annotations)
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.version.get().toInt())
        vendor = JvmVendorSpec.of(libs.versions.jdk.vendor.get())
    }

    compilerOptions {
        progressiveMode = true
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jdk.version.get())
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}
