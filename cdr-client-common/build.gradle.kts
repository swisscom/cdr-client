group = "com.swisscom.health.des.cdr.client.common"

plugins {
    kotlin("jvm").version(libs.versions.kotlin.lang)
    alias(libs.plugins.kotlinx.serialization)
    `maven-publish`
    idea
}

dependencies {
    implementation(libs.kotlinx.serialization.core)
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}
