import io.gitlab.arturbosch.detekt.Detekt
import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.net.URI
import java.time.Duration

group = "com.swisscom.health.des.cdr"
version = "3.2.3-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

val awaitilityVersion: String by project
val jacocoVersion: String by project
val kacheVersion: String by project
val kfsWatchVersion: String by project
val kotlinCoroutinesVersion: String by project
val kotlinLoggingVersion: String by project
val logstashEncoderVersion: String by project
val micrometerTracingVersion: String by project
val mockkVersion: String by project
val msal4jVersion: String by project
val springCloudVersion: String by project
val springMockkVersion: String by project
val okHttpVersion: String by project
val jvmVersion: String by project

val outputDir: Provider<Directory> = layout.buildDirectory.dir(".")

plugins {
    id("com.avast.gradle.docker-compose") version "0.17.12"
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("io.gitlab.arturbosch.detekt")
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

tasks.bootRun {
    environment["JDK_JAVA_OPTIONS"] =
        listOfNotNull(
            environment["JDK_JAVA_OPTIONS"],
            "-Djavax.net.ssl.trustStore=src/main/resources/caddy_truststore.p12",
            "-Djavax.net.ssl.trustStorePassword=changeit",
            "-Djdk.net.hosts.file=src/main/resources/msal4j_hosts"
        ).joinToString(" ")
}

application {
    mainClass = "com.swisscom.health.des.cdr.client.CdrClientApplicationKt"
}

gradle.taskGraph.whenReady(
    closureOf<TaskExecutionGraph> {
        println("Tasks to be executed: ${this.allTasks}")
        application {
            applicationDefaultJvmArgs =
                if (gradle.taskGraph.hasTask(":bootRun")) {
                    // when running the client locally via the `bootRun` target
                    listOf("-Dspring.profiles.active=client,dev")
                } else {
                    // gets picked up by application plugin when building the distribution as the
                    // value of `DEFAULT_JVM_OPTS` in the generated start script
                    listOf("-Dspring.profiles.active=client,customer")
                }
        }
    }
)

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}")
    }
    // https://github.com/detekt/detekt/issues/6198#issuecomment-2265183695
    configurations.matching { it.name == "detekt" }.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
            }
        }
    }
}

dependencies {
    implementation("com.mayakapps.kache:kache:$kacheVersion")
    implementation("com.microsoft.azure:msal4j:$msal4jVersion")
    implementation("com.squareup.okhttp3:okhttp:$okHttpVersion")
    implementation("io.github.irgaly.kfswatch:kfswatch:$kfsWatchVersion")
    implementation("io.github.oshai:kotlin-logging:$kotlinLoggingVersion")
    implementation("io.micrometer:micrometer-tracing:$micrometerTracingVersion")
    implementation("io.micrometer:micrometer-tracing-bridge-otel:$micrometerTracingVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$kotlinCoroutinesVersion") // to enable @Scheduled on Kotlin suspending functions
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.cloud:spring-cloud-context")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // Note: At the time of writing the configuration processor seems to be broken; might be related to the upgrade to Kotlin 2.x
    // Enable annotation processing via menu File | Settings | Build, Execution, Deployment
    // | Compiler | Annotation Processors | Enable annotation processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.jacoco:org.jacoco.core:${jacocoVersion}")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okHttpVersion") {
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

kapt {
    correctErrorTypes = true
    mapDiagnosticLocations = true
    includeCompileClasspath = false
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

    jvmArgs(
        // tests_hosts is used to redirect msal4j, which insists on talking to the Mothership, to our docker compose setup
        "-Djdk.net.hosts.file=${layout.projectDirectory.file("src/test/resources/test_hosts").asFile.absolutePath}"
    )
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

tasks.withType<Detekt> {
    reports {
        xml {
            required.set(true)
            outputLocation.set(File("${outputDir.get().asFile.absolutePath}/reports/detekt.xml"))
        }
        html.required.set(false)
        sarif.required.set(false)
        txt.required.set(false)
    }
}

/**
 * Detekt
 * See https://docs.sonarqube.org/latest/analysis/scan/sonarscanner-for-gradle/
 */
detekt {
    buildUponDefaultConfig = false // preconfigure defaults
    allRules = true
    parallel = true
    config.setFrom(files("$rootDir/config/detekt.yml")) // Global Detekt rule set.
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
    environment["JDK_JAVA_OPTIONS"] =
        listOfNotNull(
            environment["JDK_JAVA_OPTIONS"],
            "-Djavax.net.ssl.trustStore=src/main/resources/caddy_truststore.p12",
            "-Djavax.net.ssl.trustStorePassword=changeit",
            "-Djdk.net.hosts.file=src/main/resources/msal4j_hosts"
        ).joinToString(" ")
    shouldRunAfter(tasks.test)
    // Ensure latest images get pulled
    dependsOn(tasks.composePull)
}

dockerCompose {
    dockerComposeWorkingDirectory.set(File("${rootProject.projectDir}/docker-compose"))
    dockerComposeStopTimeout.set(Duration.ofSeconds(5))  // time before docker-compose sends SIGTERM to the running containers after the composeDown task has been started
    ignorePullFailure.set(true)
    isRequiredBy(tasks.getByName("integrationTest"))
}
/***************************
 * END Integration Testing *
 ***************************/
