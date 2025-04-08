package com.swisscom.health.des.cdr.client.handler

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.swisscom.health.des.cdr.client.handler.ClientSecretRenewalService.Companion.CLIENT_PROPERTY
import com.swisscom.health.des.cdr.client.handler.ClientSecretRenewalService.Companion.CLIENT_SECRET_PROPERTY
import com.swisscom.health.des.cdr.client.handler.ClientSecretRenewalService.Companion.CLIENT_SECRET_PROPERTY_PATH
import com.swisscom.health.des.cdr.client.handler.ClientSecretRenewalService.Companion.IDP_CREDENTIALS_PROPERTY
import io.micrometer.tracing.Tracer
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.junit5.MockKExtension.CheckUnnecessaryStub
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.env.OriginTrackedMapPropertySource
import org.springframework.boot.origin.Origin
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.FileSystemResource
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

@ExtendWith(MockKExtension::class)
@CheckUnnecessaryStub
class ClientSecretRenewalServiceTest {

    @TempDir
    lateinit var tempConfigDir: Path

    @MockK
    private lateinit var tracer: Tracer

    @MockK
    private lateinit var cdrApiClient: CdrApiClient

    @MockK
    private lateinit var applicationContext: ConfigurableApplicationContext

    private lateinit var clientSecretRenewalService: ClientSecretRenewalService

    @BeforeEach
    fun setup() {
        clientSecretRenewalService = ClientSecretRenewalService(
            context = applicationContext,
            cdrApiClient = cdrApiClient,
            tracer = tracer
        )
    }

    @Test
    fun `no known origin for secret property should yield error`() {
        every { applicationContext.environment.propertySources } returns MutablePropertySources()

        val result: ClientSecretRenewalService.RenewClientSecretResult = clientSecretRenewalService.renewClientSecret()

        assertInstanceOf<ClientSecretRenewalService.RenewClientSecretResult.RenewError>(result) { "Expected RenewError, but got $result" }
        assertEquals("No origin found for client secret `$CLIENT_SECRET_PROPERTY_PATH`", result.cause.message)

        // verify we did not call the credential renewal API
        confirmVerified(cdrApiClient)
    }

    @Test
    fun `multiple known origins for secret property should yield error`() {
        val propOrigin1 = mockk<Origin>()
        val propOrigin2 = mockk<Origin>()
        val propSource1 = mockk<OriginTrackedMapPropertySource>()
        val propSource2 = mockk<OriginTrackedMapPropertySource>()
        every { propSource1.getOrigin(CLIENT_SECRET_PROPERTY_PATH) } returns propOrigin1
        every { propSource2.getOrigin(CLIENT_SECRET_PROPERTY_PATH) } returns propOrigin2
        val propertySources = MutablePropertySources().apply {
            addLast(propSource1)
            addLast(propSource2)
        }
        every { applicationContext.environment.propertySources } returns propertySources

        val result: ClientSecretRenewalService.RenewClientSecretResult = clientSecretRenewalService.renewClientSecret()

        assertInstanceOf<ClientSecretRenewalService.RenewClientSecretResult.RenewError>(result) { "Expected RenewError, but got $result" }
        assertEquals("Multiple origins found for client secret `$CLIENT_SECRET_PROPERTY_PATH`: '${setOf(propOrigin1, propOrigin2)}'", result.cause.message)

        // verify we did not call the credential renewal API
        confirmVerified(cdrApiClient)
    }

    @Test
    fun `a client secret origin that is not a text resource should fail`() {
        val propOrigin = mockk<Origin>()
        val propSource = mockk<OriginTrackedMapPropertySource>()
        every { propSource.getOrigin(CLIENT_SECRET_PROPERTY_PATH) } returns propOrigin
        val propertySources = MutablePropertySources().apply {
            addLast(propSource)
        }
        every { applicationContext.environment.propertySources } returns propertySources

        val result: ClientSecretRenewalService.RenewClientSecretResult = clientSecretRenewalService.renewClientSecret()

        assertInstanceOf<ClientSecretRenewalService.RenewClientSecretResult.RenewError>(result) { "Expected RenewError, but got $result" }
        assertEquals("Don't know how to get file resource for origin type: '${propOrigin::class.qualifiedName}'", result.cause.message)

        // verify we did not call the credential renewal API
        confirmVerified(cdrApiClient)
    }

    @Test
    fun `if the client secret origin resource is not a writable file then renewal should fail`() {
        val propOrigin = mockk<TextResourceOrigin>()
        every { propOrigin.resource } returns FileSystemResource(tempConfigDir) // directory -> fails `isWritable` check that requires resource to be a file
        val propSource = mockk<OriginTrackedMapPropertySource>()
        every { propSource.getOrigin(CLIENT_SECRET_PROPERTY_PATH) } returns propOrigin
        val propertySources = MutablePropertySources().apply {
            addLast(propSource)
        }
        every { applicationContext.environment.propertySources } returns propertySources

        val result: ClientSecretRenewalService.RenewClientSecretResult = clientSecretRenewalService.renewClientSecret()

        assertInstanceOf<ClientSecretRenewalService.RenewClientSecretResult.RenewError>(result) { "Expected RenewError, but got $result" }
        assertEquals("Resource is not writable: '${FileSystemResource(tempConfigDir)}'", result.cause.message)

        // verify we did not call the credential renewal API
        confirmVerified(cdrApiClient)
    }

    @Test
    fun `if the client secret origin resource is of an unknown file type then renewal should fail`() {
        val configFile = tempConfigDir.resolve("unknown_config_format.toml").apply { createFile() }
        val propOrigin = mockk<TextResourceOrigin>()
        val fileSystemResource = FileSystemResource(configFile)
        every { propOrigin.resource } returns fileSystemResource
        val propSource = mockk<OriginTrackedMapPropertySource>()
        every { propSource.getOrigin(CLIENT_SECRET_PROPERTY_PATH) } returns propOrigin
        val propertySources = MutablePropertySources().apply {
            addLast(propSource)
        }
        every { applicationContext.environment.propertySources } returns propertySources

        val result: ClientSecretRenewalService.RenewClientSecretResult = clientSecretRenewalService.renewClientSecret()

        assertInstanceOf<ClientSecretRenewalService.RenewClientSecretResult.RenewError>(result) { "Expected RenewError, but got $result" }
        assertEquals("Don't know file type for extension: '${fileSystemResource.file.extension}'; resource: '$fileSystemResource'", result.cause.message)

        // verify we did not call the credential renewal API
        confirmVerified(cdrApiClient)
    }

    @Test
    fun `successful renewal of client secret in a YAML file`() {
        val configFile = tempConfigDir.resolve("unknown_config_format.yaml").apply {
            createFile()
            writeText(CLIENT_SECRET_YAML)
        }
        val propOrigin = mockk<TextResourceOrigin>()
        val fileSystemResource = FileSystemResource(configFile)
        every { propOrigin.resource } returns fileSystemResource
        val propSource = mockk<OriginTrackedMapPropertySource>()
        every { propSource.getOrigin(CLIENT_SECRET_PROPERTY_PATH) } returns propOrigin
        val propertySources = MutablePropertySources().apply {
            addLast(propSource)
        }
        every { applicationContext.environment.propertySources } returns propertySources
        every { tracer.currentSpan() } returns null
        every { cdrApiClient.renewClientCredential(any<String>()) } returns CdrApiClient.RenewClientSecretResult.Success(clientId = "foo", clientSecret = "bar")

        val result: ClientSecretRenewalService.RenewClientSecretResult = clientSecretRenewalService.renewClientSecret()

        assertInstanceOf<ClientSecretRenewalService.RenewClientSecretResult.Success>(result) { "Expected Success, but got $result" }
        val newPassword: String = YAMLMapper().run {
            readTree(result.secretSource.inputStream).run {
                get(CLIENT_PROPERTY)
                    .get(IDP_CREDENTIALS_PROPERTY)
                    .get(CLIENT_SECRET_PROPERTY)
                    .textValue()
            }
        }
        assertEquals("bar", newPassword)

        // verify we called the credential renewal API
        verify(exactly = 1) { cdrApiClient.renewClientCredential(any<String>()) }
        confirmVerified(cdrApiClient)
    }

    @Test
    fun `failing call to credential API should leave config file unchanged`() {
        val configFile = tempConfigDir.resolve("unknown_config_format.yaml").apply {
            createFile()
            writeText(CLIENT_SECRET_YAML)
        }
        val propOrigin = mockk<TextResourceOrigin>()
        val fileSystemResource = FileSystemResource(configFile)
        every { propOrigin.resource } returns fileSystemResource
        val propSource = mockk<OriginTrackedMapPropertySource>()
        every { propSource.getOrigin(CLIENT_SECRET_PROPERTY_PATH) } returns propOrigin
        val propertySources = MutablePropertySources().apply {
            addLast(propSource)
        }
        every { applicationContext.environment.propertySources } returns propertySources
        every { tracer.currentSpan() } returns null
        every { cdrApiClient.renewClientCredential(any<String>()) } returns CdrApiClient.RenewClientSecretResult.RenewHttpErrorResponse(500, "API call failed")

        val result: ClientSecretRenewalService.RenewClientSecretResult = clientSecretRenewalService.renewClientSecret()

        assertInstanceOf<ClientSecretRenewalService.RenewClientSecretResult.RenewError>(result) { "Expected RenewError, but got $result" }
        assertEquals("http error; status code: 500", result.cause.message)

        // verify we called the credential renewal API
        verify(exactly = 1) { cdrApiClient.renewClientCredential(any<String>()) }
        confirmVerified(cdrApiClient)
        // config file contents must not be changed
        assertEquals(CLIENT_SECRET_YAML, configFile.readText())
    }

    private companion object {
        @JvmStatic
        private val CLIENT_SECRET_YAML = """
            $CLIENT_PROPERTY:
              $IDP_CREDENTIALS_PROPERTY:
                $CLIENT_SECRET_PROPERTY: "Placeholder_secret"
        """.trimIndent()
    }
}
