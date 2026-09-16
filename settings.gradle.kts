rootProject.name = "cdr-client"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    val foojayResolverPluginVersion = providers.gradleProperty("foojayResolverPluginVersion").get()
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version foojayResolverPluginVersion
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

include(":cdr-client-ui")
include(":cdr-client-service")
include("cdr-client-common")
