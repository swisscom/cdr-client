package com.swisscom.health.des.cdr.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.config.CdrApi
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.ClientId
import com.swisscom.health.des.cdr.client.config.ClientSecret
import com.swisscom.health.des.cdr.client.config.CredentialApi
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.config.FileBusyTestStrategyProperty
import com.swisscom.health.des.cdr.client.config.FileSynchronization
import com.swisscom.health.des.cdr.client.config.Host
import com.swisscom.health.des.cdr.client.config.IdpCredentials
import com.swisscom.health.des.cdr.client.config.RenewCredential
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
import com.swisscom.health.des.cdr.client.config.toDto
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter
import com.swisscom.health.des.cdr.client.handler.ShutdownService
import com.swisscom.health.des.cdr.client.handler.ValidationService
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_DISABLED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_ENABLED
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.SystemHealth
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

@ExtendWith(MockKExtension::class)
internal class WebOperationsTest {

    @MockK
    private lateinit var shutdownService: ShutdownService

    @MockK
    private lateinit var configWriter: ConfigurationWriter

    @MockK
    private lateinit var healthEndpoint: HealthEndpoint

    @MockK
    private lateinit var validationService: ValidationService

    private var objectMapper: ObjectMapper = ObjectMapper()

    private lateinit var webOperations: WebOperations

    @BeforeEach
    fun setUp() {
        webOperations = WebOperations(
            shutdownService = shutdownService,
            configWriter = configWriter,
            healthEndpoint = healthEndpoint,
            objectMapper = objectMapper,
            config = DEFAULT_CDR_CONFIG,
            validationService = validationService,
        )
    }

    @Test
    @Disabled(
        "triggers the creation of a coroutine in global scope that does a `System.exit()` " +
                "after a delay which may kill the VM running the test before it can finish, which in turn fails the build"
    )
    fun `test shutdown with valid reason`() = runTest {
        val response = webOperations.shutdown(ShutdownService.ShutdownTrigger.CONFIG_CHANGE.reason)
        val shutdownResponse = assertInstanceOf<DTOs.ShutdownResponse>(response.body)
        assertEquals(ShutdownService.ShutdownTrigger.CONFIG_CHANGE.name, shutdownResponse.trigger)
        assertEquals(ShutdownService.ShutdownTrigger.CONFIG_CHANGE.exitCode, shutdownResponse.exitCode)
    }

    @Test
    fun `test shutdown with empty reason`() = runTest {
        val response = webOperations.shutdown(EMPTY_STRING)
        val shutdownResponse = assertInstanceOf<ProblemDetail>(response.body)
        assertEquals(HttpStatus.BAD_REQUEST.value(), shutdownResponse.status)
    }

    @Test
    fun `test shutdown with unknown reason`() = runTest {
        val response = webOperations.shutdown("go-figure")
        val shutdownResponse = assertInstanceOf<ProblemDetail>(response.body)
        assertEquals(HttpStatus.BAD_REQUEST.value(), shutdownResponse.status)
    }

    @Test
    fun `test shutdown with exception`() = runTest {
        every { shutdownService.scheduleShutdown(any()) } throws IllegalStateException("BANG!")

        val response = webOperations.shutdown(ShutdownService.ShutdownTrigger.CONFIG_CHANGE.reason)
        val shutdownResponse = assertInstanceOf<ProblemDetail>(response.body)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), shutdownResponse.status)
    }

    @Test
    fun `test service configuration endpoint`() = runTest {
        val response = webOperations.getServiceConfiguration()
        assertEquals(DEFAULT_CDR_CONFIG.toDto(), response.body)
    }

    @Test
    fun `test update service configuration - configuration writer success`() = runTest {
        every { configWriter.updateClientServiceConfiguration(any()) } returns ConfigurationWriter.Result.Success

        val response = webOperations.updateServiceConfiguration(DEFAULT_CDR_CONFIG.toDto())
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(DEFAULT_CDR_CONFIG.toDto(), response.body)
    }

    @Test
    fun `test update service configuration - configuration writer fail`() = runTest {
        every { configWriter.updateClientServiceConfiguration(any()) } returns ConfigurationWriter.Result.Failure(mapOf("error" to "Invalid configuration"))

        val response = webOperations.updateServiceConfiguration(DEFAULT_CDR_CONFIG.toDto())
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(listOf(MediaType.APPLICATION_PROBLEM_JSON_VALUE), response.headers[HttpHeaders.CONTENT_TYPE])

        val body: ProblemDetail = assertInstanceOf<ProblemDetail>(response.body)
        assertEquals(mapOf("error" to "Invalid configuration"), body.properties)
    }

    @Test
    fun `test update service configuration - exception`() = runTest {
        every { configWriter.updateClientServiceConfiguration(any()) } throws IllegalStateException("BANG!")

        val response = webOperations.updateServiceConfiguration(DEFAULT_CDR_CONFIG.toDto())
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals(listOf(MediaType.APPLICATION_PROBLEM_JSON_VALUE), response.headers[HttpHeaders.CONTENT_TYPE])

        val body: ProblemDetail = assertInstanceOf<ProblemDetail>(response.body)
        assertEquals("Failed to update service configuration: java.lang.IllegalStateException: BANG!", body.detail)
    }

    @ParameterizedTest
    @CsvSource(
        "ENABLED, SYNCHRONIZING",
        "DISABLED, DISABLED",
        "FOO, UNKNOWN"
    )
    fun `test status endpoint`(healthStatusString: String, responseStatusString: String) = runTest {
        val healthStatus = when (healthStatusString) {
            "ENABLED" -> FILE_SYNCHRONIZATION_STATUS_ENABLED
            "DISABLED" -> FILE_SYNCHRONIZATION_STATUS_DISABLED
            else -> healthStatusString
        }
        val responseStatus = when (responseStatusString) {
            "SYNCHRONIZING" -> DTOs.StatusResponse.StatusCode.SYNCHRONIZING
            "DISABLED" -> DTOs.StatusResponse.StatusCode.DISABLED
            else -> DTOs.StatusResponse.StatusCode.UNKNOWN
        }

        val systemHealth = mockk<SystemHealth>()
        every { healthEndpoint.health() } returns systemHealth
        every { systemHealth.toString() } returns "fake health status"
        every { systemHealth.components[FILE_SYNCHRONIZATION_INDICATOR_NAME]?.status?.code } returns healthStatus

        val response = webOperations.status()
        assertEquals(HttpStatus.OK, response.statusCode)
        val statusResponse = assertInstanceOf<DTOs.StatusResponse>(response.body)
        assertEquals(responseStatus, statusResponse.statusCode)
    }

    @Test
    fun `test status endpoint with exception`() = runTest {
        every { healthEndpoint.health() } throws IllegalStateException("BANG!")

        val response = webOperations.status()
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals(listOf(MediaType.APPLICATION_PROBLEM_JSON_VALUE), response.headers[HttpHeaders.CONTENT_TYPE])

        val body: ProblemDetail = assertInstanceOf<ProblemDetail>(response.body)
        assertEquals("Failed to retrieve service status: java.lang.IllegalStateException: BANG!", body.detail)
    }


    companion object {
        @JvmStatic
        val CURRENT_WORKING_DIR: Path = Path.of(EMPTY_STRING)

        @JvmStatic
        val DEFAULT_CDR_CONFIG = CdrClientConfig(
            fileSynchronizationEnabled = FileSynchronization.ENABLED,
            customer = Customer(
                listOf(
                    CdrClientConfig.Connector(
                        connectorId = "1",
                        targetFolder = CURRENT_WORKING_DIR,
                        sourceFolder = CURRENT_WORKING_DIR,
                        contentType = MediaType.APPLICATION_OCTET_STREAM.toString(),
                        sourceArchiveEnabled = false,
                        sourceArchiveFolder = CURRENT_WORKING_DIR,
                        sourceErrorFolder = CURRENT_WORKING_DIR,
                        mode = CdrClientConfig.Mode.PRODUCTION,
                        docTypeFolders = emptyMap()
                    )
                )
            ),
            cdrApi = CdrApi(
                scheme = "https",
                host = Host("localhost"),
                port = 8080,
                basePath = "/"
            ),
            filesInProgressCacheSize = DataSize.ofMegabytes(1L),
            idpCredentials = IdpCredentials(
                tenantId = TenantId("fake-tenant-id"),
                clientId = ClientId("fake-client-id"),
                clientSecret = ClientSecret("fake-client-secret"),
                scopes = emptyList(),
                renewCredential = RenewCredential.ENABLED,
                maxCredentialAge = Duration.ofDays(30),
                lastCredentialRenewalTime = Instant.now(),
            ),
            idpEndpoint = URL("http://localhost:8080"),
            localFolder = TempDownloadDir(CURRENT_WORKING_DIR),
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
            retryDelay = emptyList(),
            scheduleDelay = Duration.ofSeconds(1L),
            credentialApi = CredentialApi(
                scheme = "https",
                host = Host("localhost"),
                port = 8080,
                basePath = "/"
            ),
            retryTemplate = CdrClientConfig.RetryTemplateConfig(
                retries = 1,
                initialDelay = Duration.ofSeconds(1L),
                maxDelay = Duration.ofSeconds(1L),
                multiplier = 2.0
            ),
            fileBusyTestInterval = Duration.ofSeconds(1L),
            fileBusyTestTimeout = Duration.ofSeconds(1L),
            fileBusyTestStrategy = FileBusyTestStrategyProperty(CdrClientConfig.FileBusyTestStrategy.NEVER_BUSY)
        )
    }
}
