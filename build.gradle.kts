import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.net.URI

group = "com.swisscom.health.des.cdr"
version = "3.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

val jvmVersion: String by project
val kotlinCoroutinesVersion: String by project
val springCloudVersion: String by project
val jacocoVersion: String by project
val kotlinLoggingVersion: String by project
val mockkVersion: String by project
val logstashEncoderVersion: String by project
val micrometerTracingVersion: String by project
val detektKotlinVersion: String by project
val kfsWatchVersion: String by project
val kacheVersion: String by project
val springMockkVersion: String by project
val awaitilityVersion: String by project

val outputDir: Provider<Directory> = layout.buildDirectory.dir(".")

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
//    id("io.gitlab.arturbosch.detekt")
    jacoco
    application
    kotlin("jvm")
    kotlin("plugin.spring")
    // https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.kotlin.configuration-properties
    // KAPT is end of life, but KSP is not supported yet: https://github.com/spring-projects/spring-boot/issues/28046
    kotlin("kapt")
    `maven-publish`
    idea
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

application {
    mainClass = "com.swisscom.health.des.cdr.clientvm.CdrClientVmApplicationKt"
    applicationDefaultJvmArgs = listOf("-Dspring.profiles.active=client,customer")
}
dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.squareup.okhttp3:okhttp")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${kotlinCoroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${kotlinCoroutinesVersion}") // to enable @Scheduled on Kotlin suspending functions
    implementation("io.github.oshai:kotlin-logging:${kotlinLoggingVersion}")
    implementation("net.logstash.logback:logstash-logback-encoder:${logstashEncoderVersion}")
    implementation("io.micrometer:micrometer-tracing:${micrometerTracingVersion}")
    implementation("io.micrometer:micrometer-tracing-bridge-otel:${micrometerTracingVersion}")
    implementation("io.github.irgaly.kfswatch:kfswatch:$kfsWatchVersion")
    implementation("com.mayakapps.kache:kache:$kacheVersion")

    kapt("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.jacoco:org.jacoco.core:${jacocoVersion}")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("com.squareup.okhttp3:mockwebserver") {
        // Unfortunately we cannot exclude JUnit 4 as MockWebServer implements interfaces from that version
//        exclude(group = "junit", config = "junit")
    }
    testImplementation("io.mockk:mockk:${mockkVersion}") {
        exclude(group = "junit", module = "junit")
    }
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.micrometer:micrometer-tracing-test")
    testImplementation("com.ninja-squad:springmockk:${springMockkVersion}")
    testImplementation("org.awaitility:awaitility:${awaitilityVersion}")

}

springBoot {
    buildInfo()
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(jvmVersion.toInt()))
    }
    compilerOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}

val jacocoTestCoverageVerification = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        /**
         * Ensure tests cover at least 75% of the LoC.
         */
        rule {
            limit {
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

val jacocoTestReport = tasks.named<JacocoReport>("jacocoTestReport") {
    finalizedBy(jacocoTestCoverageVerification) // Verify after generating the report.
    group = "Reporting"

    reports {
        xml.required.set(true)
        xml.outputLocation.set(File("${outputDir.get().asFile.absolutePath}/reports/jacoco.xml"))
        csv.required.set(false)
        html.required.set(true)
        html.outputLocation.set(File("${outputDir.get().asFile.absolutePath}/reports/coverage"))
    }
}

tasks.withType<Test> {
    useJUnitPlatform {
        includeEngines("junit-jupiter")
    }
    finalizedBy(jacocoTestReport)
}

jacoco {
    toolVersion = jacocoVersion
}

tasks.named<BootJar>("bootJar") {
    manifest {
        // Required so application name and version get rendered in the banner.txt; see
        // https://stackoverflow.com/questions/34519759/application-version-does-not-show-up-in-spring-boot-banner-txt
        attributes("Implementation-Title" to rootProject.name)
        attributes("Implementation-Version" to archiveVersion)
    }
}

// https://docs.spring.io/spring-boot/docs/current/reference/html/howto.html#howto.properties-and-configuration.expand-properties.gradle
// and https://stackoverflow.com/questions/40096007/how-to-configure-the-processresources-task-in-a-gradle-kotlin-build
tasks.processResources {
    filesMatching("**/application.yaml") {
        expand(project.properties)
    }
}

//tasks.withType<Detekt> {
//    baseline.set(File("${projectDir}/detekt_baseline.xml"))
//    reports {
//        xml {
//            required.set(true)
//            outputLocation.set(File("${outputDir.get().asFile.absolutePath}/reports/detekt.xml"))
//        }
//        html.required.set(false)
//        sarif.required.set(false)
//        txt.required.set(false)
//    }
//}

/**
 * Detekt
 * See https://docs.sonarqube.org/latest/analysis/scan/sonarscanner-for-gradle/
 */
//detekt {
//    buildUponDefaultConfig = false // preconfigure defaults
//    allRules = true
//    parallel = true
//    config.setFrom(files("$rootDir/config/detekt.yml")) // Global Detekt rule set.
//    baseline = file("$projectDir/detekt_baseline.xml") // Module specific suppression list.
//}
//
//// https://github.com/detekt/detekt/issues/6198
//project.afterEvaluate {
//    configurations["detekt"].resolutionStrategy.eachDependency {
//        if (requested.group == "org.jetbrains.kotlin") {
//            useVersion(detektKotlinVersion)
//        }
//    }
//}

tasks.register("publishVersion") {
    group = "publishing"
    description = "Publishes boot jar"
    dependsOn(tasks.withType<PublishToMavenRepository>().matching {
        it.repository == publishing.repositories["GitHubPackages"] && it.publication == publishing.publications["bootJava"]
    })
}


publishing {
    publications {
        create<MavenPublication>("bootJava") {
            artifact(tasks.named("bootJar"))
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = URI("https://maven.pkg.github.com/swisscom/cdr-client")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
