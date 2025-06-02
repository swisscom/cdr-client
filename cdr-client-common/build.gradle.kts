group = "com.swisscom.health.des.cdr.client.common"

plugins {
    alias(libs.plugins.detekt)
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

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}
