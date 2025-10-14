package com.swisscom.health.des.cdr.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.aad.msal4j.ClientCredentialFactory
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.ConfidentialClientApplication
import com.microsoft.aad.msal4j.IAuthenticationResult
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
import com.swisscom.health.des.cdr.client.config.RenewCredential
import com.swisscom.health.des.cdr.client.config.Scope
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
import com.swisscom.health.des.cdr.client.config.toDto
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter
import com.swisscom.health.des.cdr.client.handler.ShutdownService
import com.swisscom.health.des.cdr.client.handler.ConfigValidationService
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_BROKEN
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_ERROR
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.CONFIG_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_INDICATOR_NAME
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_DISABLED
import com.swisscom.health.des.cdr.client.http.HealthIndicators.Companion.FILE_SYNCHRONIZATION_STATUS_ENABLED
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

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
            retryIOExceptionsAndServerErrors = retryIOExceptionsAndServerErrors
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

    @ParameterizedTest
    @CsvSource(
        "ENABLED, SYNCHRONIZING",
        "DISABLED, DISABLED",
        "ERROR, ERROR",
        "BROKEN, BROKEN",
        "INVALID_CREDENTIALS, LOGIN_FAILED",
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
            else -> healthStatusString
        }
        val responseStatus = when (responseStatusString) {
            "SYNCHRONIZING" -> DTOs.StatusResponse.StatusCode.SYNCHRONIZING
            "DISABLED" -> DTOs.StatusResponse.StatusCode.DISABLED
            "BROKEN" -> DTOs.StatusResponse.StatusCode.BROKEN
            "ERROR" -> DTOs.StatusResponse.StatusCode.ERROR
            "LOGIN_FAILED" -> DTOs.StatusResponse.StatusCode.LOGIN_FAILED
            else -> DTOs.StatusResponse.StatusCode.UNKNOWN
        }
        val systemHealth = mockk<SystemHealth>()
        every { healthEndpoint.health() } returns systemHealth
        every { systemHealth.toString() } returns "fake health status"
        every { systemHealth.components[FILE_SYNCHRONIZATION_INDICATOR_NAME]?.status?.code } returns fileSyncStatus
        every { systemHealth.components[CONFIG_INDICATOR_NAME]?.status?.code } returns configStatus

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
        // Create a real RetryTemplate instead of mocking it
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
            retryIOExceptionsAndServerErrors = realRetryTemplate
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

        // Mock the MSAL4J static methods and objects
        mockkStatic(ConfidentialClientApplication::class)
        mockkStatic(ClientCredentialFactory::class)

        val mockAuthResult = mockk<IAuthenticationResult>()
        val mockConfidentialApp = mockk<ConfidentialClientApplication>()
        val mockBuilder = mockk<ConfidentialClientApplication.Builder>()
        val mockCompletableFuture = CompletableFuture.completedFuture(mockAuthResult)

        every { mockAuthResult.accessToken() } returns "valid-access-token"
        every { mockConfidentialApp.acquireToken(any<ClientCredentialParameters>()) } returns mockCompletableFuture
        every { mockBuilder.authority(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockConfidentialApp
        every { ConfidentialClientApplication.builder(any<String>(), any()) } returns mockBuilder
        every { ClientCredentialFactory.createFromSecret(any()) } returns mockk()

        try {
            val response = webOperationsWithRealRetry.validateCredentials(idpCredentials)

            assertEquals(HttpStatus.OK, response.statusCode)
            val validationResult = assertInstanceOf<DTOs.ValidationResult>(response.body)
            assertEquals(DTOs.ValidationResult.Success, validationResult)
        } finally {
            unmockkStatic(ConfidentialClientApplication::class)
            unmockkStatic(ClientCredentialFactory::class)
        }
    }

    @Test
    fun `test validateCredentials - failure with blank access token`() = runTest {
        // Create a real RetryTemplate instead of mocking it
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
            retryIOExceptionsAndServerErrors = realRetryTemplate
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

        // Mock the MSAL4J static methods and objects to return blank token
        mockkStatic(ConfidentialClientApplication::class)
        mockkStatic(ClientCredentialFactory::class)

        val mockAuthResult = mockk<IAuthenticationResult>()
        val mockConfidentialApp = mockk<ConfidentialClientApplication>()
        val mockBuilder = mockk<ConfidentialClientApplication.Builder>()
        val mockCompletableFuture = CompletableFuture.completedFuture(mockAuthResult)

        every { mockAuthResult.accessToken() } returns ""  // Blank token
        every { mockConfidentialApp.acquireToken(any<ClientCredentialParameters>()) } returns mockCompletableFuture
        every { mockBuilder.authority(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockConfidentialApp
        every { ConfidentialClientApplication.builder(any<String>(), any()) } returns mockBuilder
        every { ClientCredentialFactory.createFromSecret(any()) } returns mockk()

        try {
            val response = webOperationsWithRealRetry.validateCredentials(idpCredentials)

            assertEquals(HttpStatus.OK, response.statusCode)
            val validationResult = assertInstanceOf<DTOs.ValidationResult.Failure>(response.body)
            assertEquals(1, validationResult.validationDetails.size)
            assertEquals(DTOs.ValidationMessageKey.CREDENTIAL_VALIDATION_FAILED, validationResult.validationDetails[0].messageKey)
        } finally {
            unmockkStatic(ConfidentialClientApplication::class)
            unmockkStatic(ClientCredentialFactory::class)
        }
    }

    @Test
    fun `test validateCredentials - failure with MSAL exception`() = runTest {
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
            retryIOExceptionsAndServerErrors = realRetryTemplate
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

        // Mock the MSAL4J static methods to throw exception
        mockkStatic(ConfidentialClientApplication::class)
        mockkStatic(ClientCredentialFactory::class)

        val mockConfidentialApp = mockk<ConfidentialClientApplication>()
        val mockBuilder = mockk<ConfidentialClientApplication.Builder>()

        every { mockBuilder.authority(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockConfidentialApp
        every { ConfidentialClientApplication.builder(any<String>(), any()) } returns mockBuilder
        every { ClientCredentialFactory.createFromSecret(any()) } returns mockk()
        every { mockConfidentialApp.acquireToken(any<ClientCredentialParameters>()) } throws RuntimeException("Authentication failed")

        try {
            val response = webOperationsWithRealRetry.validateCredentials(idpCredentials)

            assertEquals(HttpStatus.OK, response.statusCode)
            val validationResult = assertInstanceOf<DTOs.ValidationResult.Failure>(response.body)
            assertEquals(1, validationResult.validationDetails.size)
            assertEquals(DTOs.ValidationMessageKey.CREDENTIAL_VALIDATION_FAILED, validationResult.validationDetails[0].messageKey)
        } finally {
            unmockkStatic(ConfidentialClientApplication::class)
            unmockkStatic(ClientCredentialFactory::class)
        }
    }

    @Test
    fun `test validateCredentials - verifies correct endpoint correction logic`() = runTest {
        // Create a real RetryTemplate instead of mocking it
        val realRetryTemplate = RetryTemplate.builder()
            .maxAttempts(3)
            .build()

        // Create a config with the original endpoint that needs correction
        val configWithOriginalEndpoint = DEFAULT_CDR_CONFIG.copy(
            idpEndpoint = URI.create("https://login.microsoftonline.com/original-tenant-id/").toURL()
        )

        // Create WebOperations with real RetryTemplate and corrected config
        val webOperationsWithRealRetry = WebOperations(
            shutdownService = shutdownService,
            configWriter = configWriter,
            healthEndpoint = healthEndpoint,
            objectMapper = objectMapper,
            config = configWithOriginalEndpoint,
            configValidationService = configValidationService,
            retryIOExceptionsAndServerErrors = realRetryTemplate
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

        // Mock the MSAL4J static methods and objects
        mockkStatic(ConfidentialClientApplication::class)
        mockkStatic(ClientCredentialFactory::class)

        val mockAuthResult = mockk<IAuthenticationResult>()
        val mockConfidentialApp = mockk<ConfidentialClientApplication>()
        val mockBuilder = mockk<ConfidentialClientApplication.Builder>()
        val mockCompletableFuture = CompletableFuture.completedFuture(mockAuthResult)

        every { mockAuthResult.accessToken() } returns "valid-access-token"
        every { mockConfidentialApp.acquireToken(any<ClientCredentialParameters>()) } returns mockCompletableFuture
        // Mock the corrected endpoint that should be generated
        every { mockBuilder.authority("https://login.microsoftonline.com/different-tenant-id/") } returns mockBuilder
        every { mockBuilder.build() } returns mockConfidentialApp
        every { ConfidentialClientApplication.builder(any<String>(), any()) } returns mockBuilder
        every { ClientCredentialFactory.createFromSecret(any()) } returns mockk()

        try {
            val response = webOperationsWithRealRetry.validateCredentials(idpCredentials)

            assertEquals(HttpStatus.OK, response.statusCode)
            val validationResult = assertInstanceOf<DTOs.ValidationResult>(response.body)
            assertEquals(DTOs.ValidationResult.Success, validationResult)

            // Verify that the correct tenant-specific endpoint was used
            io.mockk.verify {
                mockBuilder.authority("https://login.microsoftonline.com/different-tenant-id/")
            }
        } finally {
            unmockkStatic(ConfidentialClientApplication::class)
            unmockkStatic(ClientCredentialFactory::class)
        }
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
                scope = Scope("scope1"),
                renewCredential = RenewCredential.ENABLED,
                maxCredentialAge = Duration.ofDays(30),
                lastCredentialRenewalTime = LastCredentialRenewalTime(Instant.now()),
            ),
            idpEndpoint = URI.create("http://localhost:8080").toURL(),
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
