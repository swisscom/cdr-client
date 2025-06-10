package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import io.micrometer.tracing.Tracer
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.junit5.MockKExtension.CheckUnnecessaryStub
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
@CheckUnnecessaryStub
internal class ClientSecretRenewalServiceTest {

    @MockK
    private lateinit var tracer: Tracer

    @MockK
    private lateinit var cdrApiClient: CdrApiClient

    @MockK
    private lateinit var config: CdrClientConfig

    @MockK
    private lateinit var configurationWriter: ConfigurationWriter

    private lateinit var clientSecretRenewalService: ClientSecretRenewalService

    @BeforeEach
    fun setup() {
        clientSecretRenewalService = ClientSecretRenewalService(
            config = config,
            configurationWriter = configurationWriter,
            cdrApiClient = cdrApiClient,
            tracer = tracer
        )
    }

    @Test
    fun `no known origin for secret property should yield error`() {
        every {configurationWriter.isWritableConfigurationItem(any<String>())} returns ConfigurationWriter.ConfigLookupResult.NotFound

        val result: ClientSecretRenewalService.RenewClientSecretResult = clientSecretRenewalService.renewClientSecret()

        assertInstanceOf<ClientSecretRenewalService.RenewClientSecretResult.Failure>(result) { "Expected RenewError, but got $result" }

        // verify we did not call the credential renewal API
        confirmVerified(cdrApiClient)
    }

    @Test
    fun `failing call to credential API should leave config file unchanged`() {
        every { configurationWriter.isWritableConfigurationItem(any<String>()) } returns ConfigurationWriter.ConfigLookupResult.Writable
        every { tracer.currentSpan() } returns null
        every { cdrApiClient.renewClientCredential(any<String>()) } returns CdrApiClient.RenewClientSecretResult.RenewHttpErrorResponse(500, "API call failed")

        val result: ClientSecretRenewalService.RenewClientSecretResult = clientSecretRenewalService.renewClientSecret()

        assertInstanceOf<ClientSecretRenewalService.RenewClientSecretResult.Failure>(result) { "Expected RenewError, but got $result" }

        // verify we called the credential renewal API
        verify(exactly = 1) { cdrApiClient.renewClientCredential(any<String>()) }
        confirmVerified(cdrApiClient)
        // verify we did not call the configuration writer's update method
        verify(exactly = 0) { configurationWriter.updateClientServiceConfiguration(any<CdrClientConfig>()) }
    }

}
