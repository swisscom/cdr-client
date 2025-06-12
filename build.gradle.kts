import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.kotlin.dsl.withType

allprojects {
    version = "3.4.2-SNAPSHOT"
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
    // KAPT is end of life, but KSP is not supported yet: https://github.com/spring-projects/spring-boot/issues/28046
    kotlin("kapt").version(libs.versions.kotlin.lang) apply false

    // but we actually want to run detekt in all subprojects
    alias(libs.plugins.detekt)
}

subprojects {
    apply {
        plugin("io.gitlab.arturbosch.detekt")
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
    jvmVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
}