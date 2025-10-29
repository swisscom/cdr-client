package com.swisscom.health.des.cdr.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.config.CdrApi
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.ClientId
import com.swisscom.health.des.cdr.client.config.ClientSecret
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.ConnectorId
import com.swisscom.health.des.cdr.client.config.CredentialApi
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.config.FileBusyTestStrategyProperty
import com.swisscom.health.des.cdr.client.config.FileSynchronization
import com.swisscom.health.des.cdr.client.config.Host
import com.swisscom.health.des.cdr.client.config.IdpCredentials
import com.swisscom.health.des.cdr.client.config.LastCredentialRenewalTime
import com.swisscom.health.des.cdr.client.config.OAuth2AuthNService
import com.swisscom.health.des.cdr.client.config.RenewCredential
import com.swisscom.health.des.cdr.client.config.Scope
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
import com.swisscom.health.des.cdr.client.config.toDto
import com.swisscom.health.des.cdr.client.handler.ConfigValidationService
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter
import com.swisscom.health.des.cdr.client.handler.ShutdownService
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_AUTHENTICATED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_COMMUNICATION_ERROR
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_DENIED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.AUTHN_UNKNOWN_ERROR
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_BROKEN
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_ERROR
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_OK
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_DISABLED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_ENABLED
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.SystemHealth
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.retry.support.RetryTemplate
import org.springframework.util.unit.DataSize
import java.net.URI
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
    private lateinit var configValidationService: ConfigValidationService

    @MockK
    private lateinit var retryIOExceptionsAndServerErrors: RetryTemplate

    @MockK
    private lateinit var authNService: OAuth2AuthNService

    private var objectMapper: ObjectMapper = ObjectMapper()

    private lateinit var webOperations: WebOperations

    private lateinit var webOperationsAdvice: WebOperationsAdvice

    @BeforeEach
    fun setUp() {
        webOperationsAdvice = WebOperationsAdvice()

        webOperations = WebOperations(
            shutdownService = shutdownService,
            configWriter = configWriter,
            healthEndpoint = healthEndpoint,
            objectMapper = objectMapper,
            config = DEFAULT_CDR_CONFIG,
            configValidationService = configValidationService,
            retryIOExceptionsAndServerErrors = retryIOExceptionsAndServerErrors,
            authService = authNService
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
        val exception = assertThrows<WebOperationsAdvice.BadRequest> { webOperations.shutdown(EMPTY_STRING) }
        val probDetail = webOperationsAdvice.handleError(exception)
        assertEquals(HttpStatus.BAD_REQUEST.value(), probDetail.status)
    }

    @Test
    fun `test shutdown with unknown reason`() = runTest {
        val exception = assertThrows<WebOperationsAdvice.BadRequest> { webOperations.shutdown("go-figure") }
        val probDetail = webOperationsAdvice.handleError(exception)
        assertEquals(HttpStatus.BAD_REQUEST.value(), probDetail.status)
    }

    @Test
    fun `test shutdown with exception`() = runTest {
        every { shutdownService.scheduleShutdown(any()) } throws IllegalStateException("BANG!")

        val exception = assertThrows<WebOperationsAdvice.ServerError> { webOperations.shutdown(ShutdownService.ShutdownTrigger.CONFIG_CHANGE.reason) }
        val probDetail = webOperationsAdvice.handleError(exception)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), probDetail.status)
    }

    @Test
    fun `test service configuration endpoint`() = runTest {
        val response = webOperations.getServiceConfiguration()
        assertEquals(DEFAULT_CDR_CONFIG.toDto(), response.body)
    }

    @Test
    fun `test update service configuration - configuration writer success`() = runTest {
        every { configWriter.updateClientServiceConfiguration(any()) } returns ConfigurationWriter.UpdateResult.Success

        val response = webOperations.updateServiceConfiguration(DEFAULT_CDR_CONFIG.toDto())
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(DEFAULT_CDR_CONFIG.toDto(), response.body)
    }

    @Test
    fun `test update service configuration - configuration writer fail`() = runTest {
        every { configWriter.updateClientServiceConfiguration(any()) } returns ConfigurationWriter.UpdateResult.Failure(
            mapOf("error" to "Invalid configuration")
        )

        val exception = assertThrows<WebOperationsAdvice.BadRequest> { webOperations.updateServiceConfiguration(DEFAULT_CDR_CONFIG.toDto()) }
        val probDetail = webOperationsAdvice.handleError(exception)

        assertEquals(mapOf("error" to "Invalid configuration"), probDetail.properties)
    }

    @Test
    fun `test update service configuration - exception`() = runTest {
        every { configWriter.updateClientServiceConfiguration(any()) } throws IllegalStateException("BANG!")

        val exception = assertThrows<WebOperationsAdvice.ServerError> { webOperations.updateServiceConfiguration(DEFAULT_CDR_CONFIG.toDto()) }
        val probDetail = webOperationsAdvice.handleError(exception)

        assertEquals("Failed to update service configuration: java.lang.IllegalStateException: BANG!", probDetail.detail)
    }

    @Suppress("CyclomaticComplexMethod")
    @ParameterizedTest
    @CsvSource(
        "ENABLED, SYNCHRONIZING",
        "DISABLED, DISABLED",
        "ERROR, ERROR",
        "BROKEN, BROKEN",
        "DENIED, AUTHN_DENIED",
        "AUTHN_FAILED_RETRY, AUTHN_ERROR",
        "AUTHN_FAILED_PERMANENT, AUTHN_ERROR",
        "FOO, UNKNOWN"
    )
    fun `test status endpoint`(healthStatusString: String, responseStatusString: String) = runTest {
        val fileSyncStatus = when (healthStatusString) {
            "ENABLED" -> FILE_SYNCHRONIZATION_STATUS_ENABLED
            "DISABLED" -> FILE_SYNCHRONIZATION_STATUS_DISABLED
            else -> healthStatusString
        }
        val configStatus = when (healthStatusString) {
            "BROKEN" -> CONFIG_BROKEN
            "ERROR" -> CONFIG_ERROR
            else -> CONFIG_OK
        }
        val authNStatus = when (healthStatusString) {
            "DENIED" -> AUTHN_DENIED
            "AUTHN_COMMUNICATION_ERROR" -> AUTHN_COMMUNICATION_ERROR
            "AUTHN_UNKNOWN_ERROR" -> AUTHN_UNKNOWN_ERROR
            else -> AUTHN_AUTHENTICATED
        }
        val responseStatus = when (responseStatusString) {
            "SYNCHRONIZING" -> DTOs.StatusResponse.StatusCode.SYNCHRONIZING
            "DISABLED" -> DTOs.StatusResponse.StatusCode.DISABLED
            "BROKEN" -> DTOs.StatusResponse.StatusCode.BROKEN
            "ERROR" -> DTOs.StatusResponse.StatusCode.ERROR
            "AUTHN_COMMUNICATION_ERROR" -> DTOs.StatusResponse.StatusCode.AUTHN_COMMUNICATION_ERROR
            "AUTHN_UNKNOWN_ERROR" -> DTOs.StatusResponse.StatusCode.AUTHN_UNKNOWN_ERROR
            "AUTHN_DENIED" -> DTOs.StatusResponse.StatusCode.AUTHN_DENIED
            else -> DTOs.StatusResponse.StatusCode.UNKNOWN
        }
        val systemHealth = mockk<SystemHealth>()
        every { healthEndpoint.health() } returns systemHealth
        every { systemHealth.toString() } returns "fake health status"
        every { systemHealth.components[FILE_SYNCHRONIZATION_INDICATOR_NAME]?.status?.code } returns fileSyncStatus
        every { systemHealth.components[CONFIG_INDICATOR_NAME]?.status?.code } returns configStatus
        every { systemHealth.components[AUTHN_INDICATOR_NAME]?.status?.code } returns authNStatus

        val response = webOperations.status()
        assertEquals(HttpStatus.OK, response.statusCode)
        val statusResponse = assertInstanceOf<DTOs.StatusResponse>(response.body)
        assertEquals(responseStatus, statusResponse.statusCode)
    }

    @Test
    fun `test status endpoint with exception`() = runTest {
        every { healthEndpoint.health() } throws IllegalStateException("BANG!")

        val exception = assertThrows<WebOperationsAdvice.ServerError> { webOperations.status() }
        val probDetail = webOperationsAdvice.handleError(exception)

        assertEquals("Failed to retrieve service status: java.lang.IllegalStateException: BANG!", probDetail.detail)
    }

    @Test
    fun `test validateCredentials - success with valid access token`() = runTest {
        val realRetryTemplate = RetryTemplate.builder()
            .maxAttempts(3)
            .build()

        // Create WebOperations with real RetryTemplate
        val webOperationsWithRealRetry = WebOperations(
            shutdownService = shutdownService,
            configWriter = configWriter,
            healthEndpoint = healthEndpoint,
            objectMapper = objectMapper,
            config = DEFAULT_CDR_CONFIG,
            configValidationService = configValidationService,
            retryIOExceptionsAndServerErrors = realRetryTemplate,
            authService = authNService
        )

        val idpCredentials = DTOs.CdrClientConfig.IdpCredentials(
            tenantId = "test-tenant-id",
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            scope = "scope",
            renewCredential = true,
            maxCredentialAge = Duration.ofDays(1L),
            lastCredentialRenewalTime = Instant.now()
        )


        every { authNService.getNewAccessToken(any(), any(), false) } returns OAuth2AuthNService.AuthNResponse.Success(mockk())

        val response = webOperationsWithRealRetry.validateCredentials(idpCredentials)

        assertEquals(HttpStatus.OK, response.statusCode)
        val validationResult = assertInstanceOf<DTOs.ValidationResult>(response.body)
        assertEquals(DTOs.ValidationResult.Success, validationResult)
    }

    @Test
    fun `test validateCredentials - failure authN response`() = runTest {
        // Create a real RetryTemplate instead of mocking it
        val realRetryTemplate = RetryTemplate.builder()
            .maxAttempts(2)
            .build()

        // Create WebOperations with real RetryTemplate
        val webOperationsWithRealRetry = WebOperations(
            shutdownService = shutdownService,
            configWriter = configWriter,
            healthEndpoint = healthEndpoint,
            objectMapper = objectMapper,
            config = DEFAULT_CDR_CONFIG,
            configValidationService = configValidationService,
            retryIOExceptionsAndServerErrors = realRetryTemplate,
            authService = authNService
        )

        val idpCredentials = DTOs.CdrClientConfig.IdpCredentials(
            tenantId = "test-tenant-id",
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            scope = "scope",
            renewCredential = true,
            maxCredentialAge = Duration.ofDays(1L),
            lastCredentialRenewalTime = Instant.now()
        )

        every { authNService.getNewAccessToken(any(), any(), false) } returns OAuth2AuthNService.AuthNResponse.Failed(mockk())

        val response = webOperationsWithRealRetry.validateCredentials(idpCredentials)

        assertEquals(HttpStatus.OK, response.statusCode)
        val validationResult = assertInstanceOf<DTOs.ValidationResult.Failure>(response.body)
        assertEquals(1, validationResult.validationDetails.size)
        assertEquals(DTOs.ValidationMessageKey.CREDENTIAL_VALIDATION_FAILED, validationResult.validationDetails[0].messageKey)
    }

    @Test
    fun `test validateCredentials - verifies correct endpoint correction logic`() = runTest {
        // Create a real RetryTemplate instead of mocking it
        val realRetryTemplate = RetryTemplate.builder()
            .maxAttempts(3)
            .build()

        // Create a config with the original endpoint that needs correction
        val configWithOriginalEndpoint = DEFAULT_CDR_CONFIG.copy(
            idpEndpoint = URI.create("https://login.microsoftonline.com/original-tenant-id/oauth2/v2.0/token").toURL()
        )

        // Create WebOperations with real RetryTemplate and corrected config
        val webOperationsWithRealRetry = WebOperations(
            shutdownService = shutdownService,
            configWriter = configWriter,
            healthEndpoint = healthEndpoint,
            objectMapper = objectMapper,
            config = configWithOriginalEndpoint,
            configValidationService = configValidationService,
            retryIOExceptionsAndServerErrors = realRetryTemplate,
            authService = authNService
        )

        val idpCredentials = DTOs.CdrClientConfig.IdpCredentials(
            tenantId = "different-tenant-id",
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            scope = "scope",
            renewCredential = true,
            maxCredentialAge = Duration.ofDays(1L),
            lastCredentialRenewalTime = Instant.now()
        )

        val idpEndpointSlot = slot<URL>()
        every { authNService.getNewAccessToken(any(), capture(idpEndpointSlot), false) } returns OAuth2AuthNService.AuthNResponse.Success(mockk())

        val response = webOperationsWithRealRetry.validateCredentials(idpCredentials)

        assertEquals(HttpStatus.OK, response.statusCode)
        val validationResult = assertInstanceOf<DTOs.ValidationResult>(response.body)
        assertEquals(DTOs.ValidationResult.Success, validationResult)
        assertEquals(configWithOriginalEndpoint.idpEndpoint.path.replace("original-tenant-id", idpCredentials.tenantId), idpEndpointSlot.captured.path)
    }

    companion object {
        @JvmStatic
        val CURRENT_WORKING_DIR: Path = Path.of(EMPTY_STRING)

        @JvmStatic
        val DEFAULT_CDR_CONFIG = CdrClientConfig(
            fileSynchronizationEnabled = FileSynchronization.ENABLED,
            customer = Customer(
                mutableListOf(
                    Connector(
                        connectorId = ConnectorId("1"),
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
                scheme = "http",
                host = Host("localhost"),
                port = 80,
                basePath = "/"
            ),
            filesInProgressCacheSize = DataSize.ofMegabytes(1L),
            idpCredentials = IdpCredentials(
                tenantId = TenantId("fake-tenant-id"),
                clientId = ClientId("fake-client-id"),
                clientSecret = ClientSecret("fake-client-secret"),
                scope = Scope("scope1"),
                renewCredential = RenewCredential.ENABLED,
                maxCredentialAge = Duration.ofDays(30),
                lastCredentialRenewalTime = LastCredentialRenewalTime(Instant.now()),
            ),
            idpEndpoint = URI("http://localhost").toURL(),
            localFolder = TempDownloadDir(CURRENT_WORKING_DIR),
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
            retryDelay = emptyList(),
            scheduleDelay = Duration.ofSeconds(1L),
            credentialApi = CredentialApi(
                scheme = "http",
                host = Host("localhost"),
                port = 80,
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
