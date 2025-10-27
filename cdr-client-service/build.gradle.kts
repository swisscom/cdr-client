import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.net.URI
import java.time.Duration
import java.util.Date

group = "com.swisscom.health.des.cdr.client.service"

val outputDir: Provider<Directory> = layout.buildDirectory.dir(".")

plugins {
    alias(libs.plugins.docker.compose)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    jacoco
    application
    kotlin("jvm").version(libs.versions.kotlin.lang)
    kotlin("plugin.spring").version(libs.versions.kotlin.lang)
    // https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.kotlin.configuration-properties
    // KAPT is end of life, but KSP is not supported yet: https://github.com/spring-projects/spring-boot/issues/28046
    kotlin("kapt").version(libs.versions.kotlin.lang)
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
    mainClass = "com.swisscom.health.des.cdr.client.CdrClientApplicationKt"
}

gradle.taskGraph.whenReady(
    closureOf<TaskExecutionGraph> {
        application {
            applicationDefaultJvmArgs =
                if (gradle.taskGraph.hasTask(":bootRun")) {
                    // when running the client locally via the `bootRun` target
                    listOf("-Dspring.profiles.active=client,dev")
                } else {
                    // gets picked up by application plugin when building the distribution as the
                    // value of `DEFAULT_JVM_OPTS` in the generated start script
                    listOf("-Dspring.profiles.active=client")
                }
        }
    }
)

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.spring.cloud.dependencies))
    implementation(libs.kache)
    implementation(libs.nimbusOAuth2Sdk)
    implementation(libs.okhttp)
    implementation(libs.kfswatch)
    implementation(libs.kotlin.logging)
    implementation(libs.micrometer.tracing)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.logstash.encoder)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor) // to enable @Scheduled on Kotlin suspending functions
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.retry)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    implementation(projects.cdrClientCommon)

    // Note: At the time of writing the configuration processor seems to be broken; might be related to the upgrade to Kotlin 2.x
    // Enable annotation processing via menu File | Settings | Build, Execution, Deployment
    // | Compiler | Annotation Processors | Enable annotation processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.jacocoCore)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mock.webserver)
    testImplementation(libs.mock.webserver.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.micrometer.tracing.test)
    testImplementation(libs.spring.mockk)
    testImplementation(libs.awaitility)

}

configurations.configureEach {
    exclude(group = "junit", module = "junit")
}

kapt {
    correctErrorTypes = true
    mapDiagnosticLocations = true
    includeCompileClasspath = false
}

springBoot {
    buildInfo {
        excludes.set(setOf("artifact", "group", "time"))
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags(Constants.INTEGRATION_TEST_TAG)
    }
}

val jacocoTestCoverageVerification = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        /**
         * Ensure tests cover at least 75% of the LoC.
         */
        rule {
            classDirectories.setFrom(files(classDirectories.files.map {
                fileTree(it) {
                    setExcludes(
                        // CdrApiClient is tested with integration tests; need to find out how to merge the jacoco reports of the two test types
                        listOf(
                            "**/com/swisscom/health/des/cdr/client/handler/CdrApiClient*"
                        )
                    )
                }
            }))
            limit {
                minimum = "0.65".toBigDecimal()
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
    // show log output produced by tests in console
    testLogging.showStandardStreams = false

    useJUnitPlatform {
        includeEngines("junit-jupiter")
    }
    finalizedBy(jacocoTestReport)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.withType<Jar> {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}

tasks.named<BootJar>("bootJar") {
    manifest {
        // Required so application name and version get rendered in the banner.txt; see
        // https://stackoverflow.com/questions/34519759/application-version-does-not-show-up-in-spring-boot-banner-txt
        attributes("Implementation-Title" to rootProject.name)
        attributes("Implementation-Version" to archiveVersion)
        attributes("Build-Timestamp" to Date())
        attributes("Implementation-Vendor" to "Swisscom (Schweiz) AG")
    }
}

// https://docs.spring.io/spring-boot/docs/current/reference/html/howto.html#howto.properties-and-configuration.expand-properties.gradle
// and https://stackoverflow.com/questions/40096007/how-to-configure-the-processresources-task-in-a-gradle-kotlin-build
tasks.processResources {
    filesMatching("**/application.yaml") {
        expand(project.properties)
    }
}

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

/***********************
 * Integration Testing *
 ***********************/
object Constants {
    const val TASK_GROUP_VERIFICATION = "verification"
    const val INTEGRATION_TEST_TAG = "integration-test"
}

tasks.register<Test>("integrationTest") {
    group = Constants.TASK_GROUP_VERIFICATION
    useJUnitPlatform {
        includeTags(Constants.INTEGRATION_TEST_TAG)
    }
    shouldRunAfter(tasks.test)
    // Ensure latest images get pulled
    dependsOn(tasks.composePull)
}

dockerCompose {
    dockerComposeWorkingDirectory.set(rootProject.file("docker-compose"))
    dockerComposeStopTimeout.set(Duration.ofSeconds(5))  // time before docker-compose sends SIGTERM to the running containers after the composeDown task has been started
    ignorePullFailure.set(true)
    isRequiredBy(tasks.getByName("integrationTest"))
}
/***************************
 * END Integration Testing *
 ***************************/
val packagePrepare = "jpackage-prepare"

tasks.register<Delete>("clearPackagePrepare") {
    delete(file("${outputDir.get().asFile.absolutePath}/$packagePrepare"))
}

tasks.register<Exec>("jpackageAppPrepareDebian") {
    dependsOn("clearPackagePrepare")
    executable = "jpackage"
    args(
        "--type", "app-image",
        "--name", project.name,
        "--input", "${outputDir.get().asFile.absolutePath}/libs",
        "--main-jar", "${project.name}-${project.version}.jar",
        "--app-version", project.version.toString(),
        "--vendor", "Swisscom (Schweiz) AG",
        "--copyright", "Copyright 2025, All rights reserved",
        "--icon", "resources/icon.png",
        "--dest", "${outputDir.get().asFile.absolutePath}/$packagePrepare",
        "--java-options", "-Dfile.encoding=UTF-8",
    )
    doLast {
        copy {
            from(".") {
                include("LICENSE")
            }
            into("${outputDir.get().asFile.absolutePath}/$packagePrepare/${project.name}/lib/app")
        }
    }
}

tasks.register<Exec>("jpackageAppFinishDebian") {
    dependsOn("jpackageAppPrepareDebian")
    executable = "jpackage"
    args(
        "--app-image", "${outputDir.get().asFile.absolutePath}/$packagePrepare/${project.name}",
        "--dest", "${outputDir.get().asFile.absolutePath}/jpackage",
        "--license-file", "${outputDir.get().asFile.absolutePath}/$packagePrepare/${project.name}/lib/app/LICENSE",
        "--copyright", "Copyright 2025, All rights reserved",
        "--app-version", project.version.toString(),
        "--verbose"
    )
}

tasks.register<Exec>("jpackageAppPrepareWindows") {
    dependsOn("clearPackagePrepare")
    executable = "jpackage"
    args(
        "--type", "app-image",
        "--name", project.name,
        "--input", "${outputDir.get().asFile.absolutePath}/libs",
        "--main-jar", "${project.name}-${project.version}.jar",
        "--app-version", project.version.toString(),
        "--vendor", "Swisscom (Schweiz) AG",
        "--copyright", "Copyright 2025, All rights reserved",
        "--icon", "resources/windows/icon.ico",
        "--win-console",
        "--dest", "${outputDir.get().asFile.absolutePath}/$packagePrepare",
        "--java-options", "-Dfile.encoding=UTF-8",
    )
    doLast {
        copy {
            from("resources/windows") {
                include("cdrClient.exe")
                include("cdrClientw.exe")
                include("icon.ico")
                include("stop.bat")
            }
            from(".") {
                include("LICENSE")
            }
            into("${outputDir.get().asFile.absolutePath}/$packagePrepare/${project.name}/lib/app")
        }
    }
}

tasks.register<Exec>("jpackageAppFinishWindows") {
    dependsOn("jpackageAppPrepareWindows")
    executable = "jpackage"
    args(
        "--app-image", "${outputDir.get().asFile.absolutePath}/$packagePrepare/${project.name}",
        "--win-dir-chooser",
        "--license-file", "${outputDir.get().asFile.absolutePath}/$packagePrepare/${project.name}/lib/app/LICENSE",
        "--copyright", "Copyright 2025, All rights reserved",
        "--dest", "${outputDir.get().asFile.absolutePath}/jpackage",
        "--app-version", project.version.toString(),
        "--verbose"
    )
}
