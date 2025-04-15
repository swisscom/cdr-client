allprojects {
    version = "3.4.2-SNAPSHOT"
}

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.docker.compose) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.detekt) apply false
    kotlin("jvm").version(libs.versions.kotlin.lang) apply false
    kotlin("plugin.spring").version(libs.versions.kotlin.lang) apply false
    // https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.kotlin.configuration-properties
    // KAPT is end of life, but KSP is not supported yet: https://github.com/spring-projects/spring-boot/issues/28046
    kotlin("kapt").version(libs.versions.kotlin.lang) apply false
}

// NOTE: you should to run this target or manually update `gradle/gradle-daemon-jvm.properties` if we change the Java version!
tasks.updateDaemonJvm {
    jvmVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
}