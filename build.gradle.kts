allprojects {
    version = "3.4.2-SNAPSHOT"
}

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.dockerCompose) apply false
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.springDependencyManagement) apply false
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