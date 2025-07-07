package com.swisscom.health.des.cdr.client.handler

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
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
import com.swisscom.health.des.cdr.client.config.LastCredentialRenewalTime
import com.swisscom.health.des.cdr.client.config.RenewCredential
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
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
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.FileSystemResource
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createFile
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

@ExtendWith(MockKExtension::class)
@CheckUnnecessaryStub
class ConfigurationWriterTest {

    @TempDir
    lateinit var tempConfigDir: Path

    @MockK
    private lateinit var applicationContext: ConfigurableApplicationContext

    private lateinit var config: CdrClientConfig

    private lateinit var configurationWriter: ConfigurationWriter


    @Suppress("LongMethod")
    @BeforeEach
    fun setup() {
        config = CdrClientConfig(
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
                lastCredentialRenewalTime = LastCredentialRenewalTime(Instant.now()),
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

        configurationWriter = ConfigurationWriter(
            currentConfig = config,
            context = applicationContext,
        )
    }

    @Test
    fun `no known origin for property should succeed - to be changed once rollback strategy is implemented`() {
        every { applicationContext.environment.propertySources } returns MutablePropertySources()

        val result = configurationWriter.updateClientServiceConfiguration(config.copy(fileSynchronizationEnabled = FileSynchronization.DISABLED))

        assertInstanceOf<ConfigurationWriter.Result.Success>(result) { "Expected failure, but got $result" }
    }

    @Test
    fun `if the client secret origin resource is not a writable file then renewal should succeed - to be changed once rollback strategy is implemented`() {
        val propOrigin = mockk<TextResourceOrigin>()
        every { propOrigin.resource } returns FileSystemResource(tempConfigDir) // directory -> fails `isWritable` check that requires resource to be a file
        val propSource = mockk<OriginTrackedMapPropertySource>()
        every { propSource.getOrigin("client.file-synchronization-enabled") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.renew-credential") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.client-secret") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.tenant-id") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.client-id") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.last-credential-renewal-time") } returns propOrigin
        every { propSource.getOrigin("client.customer") } returns propOrigin
        every { propSource.getOrigin("client.local-folder") } returns propOrigin
        every { propSource.getOrigin("client.file-busy-test-strategy") } returns propOrigin
        every { propSource.getOrigin("client.customer") } returns propOrigin
        every { propSource.getOrigin("client.credential-api.host") } returns propOrigin
        every { propSource.getOrigin("client.cdr-api.host") } returns propOrigin
        val propertySources = MutablePropertySources().apply {
            addLast(propSource)
        }
        every { applicationContext.environment.propertySources } returns propertySources

        val result = configurationWriter.updateClientServiceConfiguration(config.copy(fileSynchronizationEnabled = FileSynchronization.DISABLED))

        assertInstanceOf<ConfigurationWriter.Result.Success>(result) { "Expected Success, but got $result" }
    }

    @Test
    fun `if the client secret origin resource is of an unknown file type then renewal should succeed - to be changed once rollback strategy is implemented`() {
        val configFile = tempConfigDir.resolve("unknown_config_format.toml").apply { createFile() }
        val propOrigin = mockk<TextResourceOrigin>()
        val fileSystemResource = FileSystemResource(configFile)
        every { propOrigin.resource } returns fileSystemResource
        val propSource = mockk<OriginTrackedMapPropertySource>()
        every { propSource.getOrigin("client.file-synchronization-enabled") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.renew-credential") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.client-secret") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.tenant-id") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.client-id") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.last-credential-renewal-time") } returns propOrigin
        every { propSource.getOrigin("client.local-folder") } returns propOrigin
        every { propSource.getOrigin("client.file-busy-test-strategy") } returns propOrigin
        every { propSource.getOrigin("client.customer") } returns propOrigin
        every { propSource.getOrigin("client.credential-api.host") } returns propOrigin
        every { propSource.getOrigin("client.cdr-api.host") } returns propOrigin
        val propertySources = MutablePropertySources().apply {
            addLast(propSource)
        }
        every { applicationContext.environment.propertySources } returns propertySources

        val result = configurationWriter.updateClientServiceConfiguration(config.copy(fileSynchronizationEnabled = FileSynchronization.DISABLED))

        assertInstanceOf<ConfigurationWriter.Result.Success>(result) { "Expected Success, but got $result" }
    }

    @Test
    fun `successful renewal of client secret in a YAML file`() {
        val configFile = tempConfigDir.resolve("unknown_config_format.yaml").apply {
            createFile()
            writeText(FILE_SYNC_YAML)
        }
        val propOrigin = mockk<TextResourceOrigin>()
        val fileSystemResource = FileSystemResource(configFile)
        every { propOrigin.resource } returns fileSystemResource
        val propSource = mockk<OriginTrackedMapPropertySource>()
        every { propSource.getOrigin("client.file-synchronization-enabled") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.renew-credential") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.client-secret") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.tenant-id") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.client-id") } returns propOrigin
        every { propSource.getOrigin("client.idp-credentials.last-credential-renewal-time") } returns propOrigin
        every { propSource.getOrigin("client.customer") } returns propOrigin
        every { propSource.getOrigin("client.local-folder") } returns propOrigin
        every { propSource.getOrigin("client.file-busy-test-strategy") } returns propOrigin
        every { propSource.getOrigin("client.customer") } returns propOrigin
        every { propSource.getOrigin("client.credential-api.host") } returns propOrigin
        every { propSource.getOrigin("client.cdr-api.host") } returns propOrigin
        val propertySources = MutablePropertySources().apply {
            addLast(propSource)
        }
        every { applicationContext.environment.propertySources } returns propertySources

        val result = configurationWriter.updateClientServiceConfiguration(config.copy(fileSynchronizationEnabled = FileSynchronization.DISABLED))

        assertInstanceOf<ConfigurationWriter.Result.Success>(result) { "Expected Success, but got $result" }
        val newFileSyncValue = YAMLMapper().run {
            readTree(configFile.inputStream()).run {
                get("client")
                    .get("file-synchronization-enabled")
                    .booleanValue()
            }
        }
        assertEquals(FileSynchronization.DISABLED.value, newFileSyncValue)
    }

    @Test
    fun `multiple known origins for property should update first in list`() {
        val configFile = tempConfigDir.resolve("unknown_config_format.yaml").apply {
            createFile()
            writeText(FILE_SYNC_YAML)
        }

        val propOrigin1 = mockk<TextResourceOrigin>()
        val propOrigin2 = mockk<TextResourceOrigin>()
        val fileSystemResource = FileSystemResource(configFile)
        every { propOrigin1.resource } returns fileSystemResource
        val propSource1 = mockk<OriginTrackedMapPropertySource>()
        val propSource2 = mockk<OriginTrackedMapPropertySource>()
        every { propSource1.getOrigin("client.file-synchronization-enabled") } returns propOrigin1
        every { propSource2.getOrigin("client.file-synchronization-enabled") } returns propOrigin2
        every { propSource1.getOrigin("client.idp-credentials.renew-credential") } returns propOrigin1
        every { propSource2.getOrigin("client.idp-credentials.renew-credential") } returns null
        every { propSource1.getOrigin("client.idp-credentials.client-secret") } returns propOrigin1
        every { propSource2.getOrigin("client.idp-credentials.client-secret") } returns null
        every { propSource1.getOrigin("client.idp-credentials.tenant-id") } returns propOrigin1
        every { propSource2.getOrigin("client.idp-credentials.tenant-id") } returns null
        every { propSource1.getOrigin("client.idp-credentials.client-id") } returns propOrigin1
        every { propSource2.getOrigin("client.idp-credentials.client-id") } returns null
        every { propSource1.getOrigin("client.idp-credentials.last-credential-renewal-time") } returns propOrigin1
        every { propSource2.getOrigin("client.idp-credentials.last-credential-renewal-time") } returns null
        every { propSource1.getOrigin("client.customer") } returns propOrigin1
        every { propSource2.getOrigin("client.customer") } returns null
        every { propSource1.getOrigin("client.local-folder") } returns propOrigin1
        every { propSource2.getOrigin("client.local-folder") } returns null
        every { propSource1.getOrigin("client.file-busy-test-strategy") } returns propOrigin1
        every { propSource2.getOrigin("client.file-busy-test-strategy") } returns null
        every { propSource1.getOrigin("client.customer") } returns propOrigin1
        every { propSource2.getOrigin("client.customer") } returns null
        every { propSource1.getOrigin("client.credential-api.host") } returns propOrigin1
        every { propSource2.getOrigin("client.credential-api.host") } returns null
        every { propSource1.getOrigin("client.cdr-api.host") } returns propOrigin1
        every { propSource2.getOrigin("client.cdr-api.host") } returns null
        val propertySources = MutablePropertySources().apply {
            addLast(propSource1)
            addLast(propSource2)
        }
        every { applicationContext.environment.propertySources } returns propertySources

        val result = configurationWriter.updateClientServiceConfiguration(config.copy(fileSynchronizationEnabled = FileSynchronization.DISABLED))

        assertInstanceOf<ConfigurationWriter.Result.Success>(result) { "Expected Success, but got $result" }
        val newFileSyncValue = YAMLMapper().run {
            readTree(configFile.inputStream()).run {
                get("client")
                    .get("file-synchronization-enabled")
                    .booleanValue()
            }
        }
        assertEquals(FileSynchronization.DISABLED.value, newFileSyncValue)

        // make sure no attempt was made to access the second property source
        verify(exactly = 0) { propOrigin2.resource }
    }

    @Test
    fun `test property source not found, not writable, and found`() {
        val configFile = tempConfigDir.resolve("unknown_config_format.yaml").apply {
            createFile()
        }
        val propOriginWritable = mockk<TextResourceOrigin>()
        val fileSystemResource = FileSystemResource(configFile)
        every { propOriginWritable.resource } returns fileSystemResource
        val propOriginNotWritable = mockk<TextResourceOrigin>()
        val fileSystemResourceNotWritable = FileSystemResource(tempConfigDir)
        every {  propOriginNotWritable.resource } returns fileSystemResourceNotWritable
        val propSource = mockk<OriginTrackedMapPropertySource>()
        every { propSource.getOrigin("client.file-synchronization-enabled") } returns propOriginWritable // writable
        every { propSource.getOrigin("client.idp-credentials.renew-credential") } returns propOriginNotWritable // not writable
        every { propSource.getOrigin("client.idp-credentials.client-secret") } returns null // not writable
        every { propSource.getOrigin("client.idp-credentials.tenant-id") } returns null
        every { propSource.getOrigin("client.idp-credentials.client-id") } returns null
        every { propSource.getOrigin("client.idp-credentials.last-credential-renewal-time") } returns null
        every { propSource.getOrigin("client.customer") } returns null
        every { propSource.getOrigin("client.local-folder") } returns null
        every { propSource.getOrigin("client.file-busy-test-strategy") } returns null
        every { propSource.getOrigin("client.customer") } returns null
        every { propSource.getOrigin("client.credential-api.host") } returns null
        every { propSource.getOrigin("client.cdr-api.host") } returns null
        val propertySources = MutablePropertySources().apply {
            addLast(propSource)
        }
        every { applicationContext.environment.propertySources } returns propertySources

        assertEquals(ConfigurationWriter.ConfigLookupResult.Writable,
            configurationWriter.isWritableConfigurationItem("client.file-synchronization-enabled"))
        assertEquals(ConfigurationWriter.ConfigLookupResult.NotWritable,
            configurationWriter.isWritableConfigurationItem("client.idp-credentials.client-secret"))
        assertEquals(ConfigurationWriter.ConfigLookupResult.NotWritable,
            configurationWriter.isWritableConfigurationItem("client.idp-credentials.renew-credential"))
        assertEquals(ConfigurationWriter.ConfigLookupResult.NotFound,
            configurationWriter.isWritableConfigurationItem("client.unknown-property"))
    }

    companion object {
        @JvmStatic
        private val FILE_SYNC_YAML = """
            client:
               file-synchronization-enabled: true
        """.trimIndent()

        @JvmStatic
        private val CURRENT_WORKING_DIR = Path.of(EMPTY_STRING)
    }

}
