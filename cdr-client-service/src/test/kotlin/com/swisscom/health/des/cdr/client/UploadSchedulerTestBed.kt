package com.swisscom.health.des.cdr.client

import com.ninjasquad.springmockk.MockkSpyBean
import com.swisscom.health.des.cdr.client.common.Constants.UPLOAD_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService
import com.swisscom.health.des.cdr.client.config.auth.AuthNResponse
import com.swisscom.health.des.cdr.client.config.auth.AuthStateSnapshot
import com.swisscom.health.des.cdr.client.handler.ConfigValidationService
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter
import com.swisscom.health.des.cdr.client.handler.SchedulingValidationService
import io.mockk.every
import io.mockk.mockk
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.scheduling.config.TaskExecutionOutcome.Status.STARTED
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * Shared Spring Boot test bed for integration tests of the document upload flow.
 *
 * The client configuration is injected via [DynamicPropertySource] (instead of `@MockkSpyBean` stubbing), so the
 * upload schedulers see the test configuration right from their first execution; no race against the schedulers'
 * initial delay. The cdr-api and the IdP are simulated with [MockWebServer]s; all source, archive and error
 * directories live in a JUnit managed temporary directory that gets deleted after the test class has finished.
 *
 * Concrete subclasses select the upload trigger(s) under test with the `noEventTriggerUploadScheduler` and
 * `noPollingUploadScheduler` profiles and declare the scheduled tasks to wait for in [uploadTasks].
 */
@SpringBootTest(
    classes = [CdrClientApplication::class, UploadSchedulerTestBed.UploadSchedulerTestBeans::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.jmx.enabled=false",
        "client.idp-credentials.renew-credential=false",
        "client.connection-timeout-ms=5000",
        // the mock cdr-api holds requests to simulate long-running uploads; must not run into a read timeout
        "client.read-timeout-ms=60000",
        "client.file-busy-test-strategy=never_busy",
        "client.schedule-delay=200ms",
    ]
)
@ActiveProfiles("test", "noFileMonitoringScheduler", "noDownloadScheduler")
internal abstract class UploadSchedulerTestBed {

    @MockkSpyBean
    private lateinit var authNService: OAuth2AuthNService

    @Autowired
    private lateinit var scheduledTaskHolder: ScheduledTaskHolder

    /** Names of the `@Scheduled` upload tasks that are expected to be active, e.g. `launchFileWatcher`, `launchFilePoller`. */
    protected abstract val uploadTasks: List<String>

    @BeforeEach
    fun setUpTestBed() {
        // auth is not what we are exercising here; always hand out a valid token
        every { authNService.currentStateSnapshot() } returns AuthStateSnapshot(
            response = AuthNResponse.Success(
                response = mockk(relaxed = true) { every { tokens.accessToken.value } returns "test-token" },
                expiresAtEpochSecond = Long.MAX_VALUE,
            )
        )

        val tasks = scheduledTaskHolder.scheduledTasks.filter { task -> uploadTasks.any { task.task.toString().endsWith(it) } }
        check(tasks.size == uploadTasks.size) { "expected scheduled upload tasks '$uploadTasks', but found '$tasks'" }
        await().atMost(Duration.ofSeconds(10)).until { tasks.all { it.task.lastExecutionOutcome.status == STARTED } }
        // give the file watcher time to register its watched directories, and the poller time for its first poll
        Thread.sleep(SCHEDULER_SETTLE_MILLIS)
    }

    /**
     * Creates `<name>.xml` in the source directory. The file is written under a temporary name first and then atomically
     * renamed to its final name, so that neither trigger can pick up a partially written file.
     */
    protected fun dropDocument(name: String, content: String = name): Path {
        val tmpFile = sourceDir.resolve("$name.xml.tmp").apply { writeText(content) }
        return Files.move(tmpFile, sourceDir.resolve("$name.xml"), ATOMIC_MOVE)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class UploadSchedulerTestBeans {
        /**
         * The real configuration validation rejects the mock cdr-api endpoint (random port), which would prevent the upload
         * schedulers from starting. Overriding the validation result in a subclass is free of races, unlike stubbing a spy.
         */
        @Bean
        @Primary
        fun alwaysAllowingSchedulingValidationService(
            configValidationService: ConfigValidationService,
            environment: Environment,
            configurationWriter: ConfigurationWriter,
            config: CdrClientConfig,
        ): SchedulingValidationService =
            object : SchedulingValidationService(configValidationService, environment, configurationWriter, config) {
                override val isSchedulingAllowed: Boolean = true
            }
    }

    companion object {
        private const val SCHEDULER_SETTLE_MILLIS = 1_000L
        const val CONTENT_TYPE = "application/forumdatenaustausch+xml;charset=UTF-8"

        @TempDir
        @JvmStatic
        lateinit var testRoot: Path

        @TempDir(factory = AlwaysSameTempDirFactory::class)
        @JvmStatic
        @Suppress("unused")
        private lateinit var inflightDirInApplicationTestYaml: Path

        @JvmStatic
        lateinit var cdrApiMock: MockWebServer

        @JvmStatic
        private lateinit var idpMock: MockWebServer

        val sourceDir: Path get() = testRoot.resolve("source")
        val archiveDir: Path get() = testRoot.resolve("archive")
        val errorDir: Path get() = testRoot.resolve("error")
        private val targetDir: Path get() = testRoot.resolve("target")
        private val localDir: Path get() = testRoot.resolve("inflight")

        fun filesIn(dir: Path, extension: String): List<Path> =
            if (dir.exists()) dir.listDirectoryEntries().filter { it.isRegularFile() && it.extension == extension } else emptyList()

        fun filesInTree(dir: Path, extension: String): List<Path> =
            dir.walk().filter { it.isRegularFile() && it.extension == extension }.toList()

        fun allUploadFiles(): List<Path> = filesInTree(testRoot, UPLOAD_FILE_EXTENSION)

        @JvmStatic
        @BeforeAll
        fun startMockServersAndCreateDirectories() {
            listOf(sourceDir, archiveDir, errorDir, targetDir, localDir).forEach { it.createDirectories() }

            cdrApiMock = MockWebServer().apply { start() }
            idpMock = MockWebServer().apply {
                dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse.Builder()
                            .code(HttpStatus.OK.value())
                            .headers(Headers.Builder().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build())
                            .body("""{"token_type":"Bearer","access_token":"test-token","expires_in":3599,"scope":"https://localhost/CdrApi/.default"}""")
                            .build()
                }
                start()
            }
        }

        @JvmStatic
        @AfterAll
        fun stopMockServers() {
            cdrApiMock.close()
            idpMock.close()
        }

        @Suppress("unused")
        @JvmStatic
        @DynamicPropertySource
        fun clientProperties(registry: DynamicPropertyRegistry) {
            registry.add("client.cdr-api.scheme") { "http" }
            registry.add("client.cdr-api.host") { cdrApiMock.hostName }
            registry.add("client.cdr-api.port") { cdrApiMock.port }
            registry.add("client.cdr-api.base-path") { "documents" }
            registry.add("client.idp-endpoint") { "http://${idpMock.hostName}:${idpMock.port}/oauth2/v2.0/token" }
            registry.add("client.local-folder") { localDir.toString() }
            // replaces the whole `client.customer` list of `application-test.yaml`
            registry.add("client.customer[0].connector-id") { "2345" }
            registry.add("client.customer[0].content-type") { CONTENT_TYPE }
            registry.add("client.customer[0].mode") { "test" }
            registry.add("client.customer[0].target-folder") { targetDir.toString() }
            registry.add("client.customer[0].source-folder") { sourceDir.toString() }
            registry.add("client.customer[0].source-archive-enabled") { true }
            registry.add("client.customer[0].source-archive-folder") { archiveDir.toString() }
            registry.add("client.customer[0].source-error-folder") { errorDir.toString() }
        }
    }

}
