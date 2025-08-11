package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
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
import com.swisscom.health.des.cdr.client.config.LastCredentialRenewalTime.Companion.BEGINNING_OF_TIME
import com.swisscom.health.des.cdr.client.config.RenewCredential
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
import com.swisscom.health.des.cdr.client.config.toDto
import com.swisscom.health.des.cdr.client.xml.DocumentType
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@ExtendWith(MockKExtension::class)
internal class ConfigValidationServiceTest {

    @TempDir
    private lateinit var localFolder0: Path

    @TempDir
    private lateinit var sourceFolder0: Path

    @TempDir
    private lateinit var targetFolder0: Path

    @TempDir
    private lateinit var sourceFolder1: Path

    @TempDir
    private lateinit var targetFolder1: Path

    @TempDir
    private lateinit var sourceErrorDir1: Path

    @TempDir
    private lateinit var sourceArchiveDir1: Path

    @TempDir
    private lateinit var sourceFolder2: Path

    @TempDir
    private lateinit var targetFolder2: Path

    @TempDir
    private lateinit var sourceFolder3: Path

    @TempDir
    private lateinit var targetFolder3: Path

    @TempDir
    private lateinit var sourceFolder4: Path

    @TempDir
    private lateinit var targetFolder4: Path

    @TempDir
    private lateinit var sourceErrorDir4: Path

    @TempDir
    private lateinit var sourceArchiveDir4: Path

    @TempDir
    private lateinit var multiPurposeTempDir: Path

    @MockK
    private lateinit var environment: Environment

    private lateinit var allGoodCdrClientConfig: CdrClientConfig

    private lateinit var configValidationService: ConfigValidationService

    @BeforeEach
    fun setUp() {
        every { environment.activeProfiles } returns arrayOf("test")
        allGoodCdrClientConfig = createCdrClientConfig(blueSkyConnectors())
        configValidationService = ConfigValidationService(allGoodCdrClientConfig)
    }

    @Test
    fun `test blue sky configuration`() {
        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(allGoodCdrClientConfig.toDto())
        assertEquals(DTOs.ValidationResult.Success, validationResult)
    }

    @Test
    fun `test validation error if local directory does not exist`() {
        val filePath = localFolder0.resolve("deeper")
        val clientConfig = allGoodCdrClientConfig.copy(localFolder = TempDownloadDir(filePath))
        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(clientConfig.toDto())

        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        assertEquals(1, validationResult.validationDetails.size)
        validationResult.validationDetails.first().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
            assertEquals(filePath.toString(), validationDetail.path)
            assertEquals(DTOs.ValidationMessageKey.DIRECTORY_NOT_FOUND, validationDetail.messageKey)
        }
    }

    @Test
    fun `test validation error if local directory is not a directory`() {
        val filePath = localFolder0.resolve("file.txt")
        Files.createFile(filePath)
        val clientConfig = allGoodCdrClientConfig.copy(localFolder = TempDownloadDir(filePath))
        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(clientConfig.toDto())

        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        assertEquals(1, validationResult.validationDetails.size)
        validationResult.validationDetails.first().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
            assertEquals(filePath.toString(), validationDetail.path)
            assertEquals(DTOs.ValidationMessageKey.NOT_A_DIRECTORY, validationDetail.messageKey)
        }
    }

    @Test
    fun `test validation error if no customer is configured`() {
        val cdrClientConfig = createCdrClientConfig(emptyList())

        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig.toDto())

        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        assertEquals(1, validationResult.validationDetails.size)
        validationResult.validationDetails.first().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.ConfigItemDetail>(validationDetail)
            assertEquals(DomainObjects.ConfigurationItem.CONNECTOR, validationDetail.configItem)
            assertEquals(DTOs.ValidationMessageKey.NO_CONNECTOR_CONFIGURED, validationDetail.messageKey)
        }
    }

    @Test
    fun `test validation error because local directory is overlapping with any another directory`() {
        // not using @ParameterizedTest because I rather not make all the temp directories `static`
        connectorDirsAsLocalDirs().forEach { localDir ->
            val cdrClientConfig = createCdrClientConfig(blueSkyConnectors(), localDir)

            val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig.toDto())

            assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
            assertEquals(1, validationResult.validationDetails.size)
            validationResult.validationDetails.first().let { validationDetail ->
                assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
                assertEquals(localDir.toString(), validationDetail.path)
                assertTrue(
                    listOf(
                        DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_SOURCE_DIRS,
                        DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_TARGET_DIRS
                    ).contains(validationDetail.messageKey)
                )
            }
        }
    }

    @Test
    fun `test validation error because source directories overlap`() {
        val overlappingSourceWithinSameConnector = listOf(
            blueSkyConnectors().first().copy(sourceFolder = sourceFolder1, mode = CdrClientConfig.Mode.PRODUCTION),
            blueSkyConnectors().first().copy(sourceFolder = sourceFolder1, mode = CdrClientConfig.Mode.TEST)
        )
        val overlappingSourceAcrossTwoConnectors = listOf(
            blueSkyConnectors().first().copy(sourceFolder = sourceFolder1, mode = CdrClientConfig.Mode.PRODUCTION),
            blueSkyConnectors().last().copy(sourceFolder = sourceFolder1, mode = CdrClientConfig.Mode.TEST)
        )

        listOf(overlappingSourceWithinSameConnector, overlappingSourceAcrossTwoConnectors).forEach { overlappingSource ->
            val cdrClientConfig = createCdrClientConfig(overlappingSource)

            val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig.toDto())

            assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
            assertEquals(1, validationResult.validationDetails.size)
            validationResult.validationDetails.first().let { validationDetail ->
                assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
                assertEquals(sourceFolder1.toString(), validationDetail.path)
                assertEquals(DTOs.ValidationMessageKey.DUPLICATE_SOURCE_DIRS, validationDetail.messageKey)
            }
        }
    }

    @Test
    fun `test validation error because source and target directories overlap`() {
        val overlappingSourceAndTargetWithinSameConnector = listOf(
            blueSkyConnectors().first().copy(sourceFolder = targetFolder0, mode = CdrClientConfig.Mode.PRODUCTION),
            blueSkyConnectors().first().copy(targetFolder = targetFolder0, mode = CdrClientConfig.Mode.TEST)
        )
        val overlappingSourceAndTargetAcrossTwoConnectors = listOf(
            blueSkyConnectors().first().copy(sourceFolder = targetFolder0, mode = CdrClientConfig.Mode.PRODUCTION),
            blueSkyConnectors().last().copy(targetFolder = targetFolder0, mode = CdrClientConfig.Mode.PRODUCTION)
        )

        listOf(overlappingSourceAndTargetAcrossTwoConnectors, overlappingSourceAndTargetWithinSameConnector).forEach { overlappingSourceAndTarget ->
            val cdrClientConfig = createCdrClientConfig(overlappingSourceAndTarget)

            val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig.toDto())

            assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
            assertEquals(1, validationResult.validationDetails.size)
            validationResult.validationDetails.first().let { validationDetail ->
                assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
                assertEquals(targetFolder0.toString(), validationDetail.path)
                assertEquals(DTOs.ValidationMessageKey.TARGET_DIR_OVERLAPS_SOURCE_DIRS, validationDetail.messageKey)
            }
        }
    }

    @Test
    fun `test validation error because source and error or archive directories overlap`() {
        val overlappingSourceWithErrorOrArchive = listOf(
            blueSkyConnectors().first().copy(sourceFolder = sourceErrorDir1, sourceErrorFolder = sourceErrorDir1, mode = CdrClientConfig.Mode.PRODUCTION),
            blueSkyConnectors().first().copy(sourceFolder = sourceArchiveDir4, sourceArchiveFolder = sourceArchiveDir4, mode = CdrClientConfig.Mode.TEST)
        )

        val cdrClientConfig = createCdrClientConfig(overlappingSourceWithErrorOrArchive)

        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig.toDto())
        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        assertEquals(2, validationResult.validationDetails.size)
        validationResult.validationDetails.first().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
            assertEquals(sourceErrorDir1.toString(), validationDetail.path)
            assertEquals(DTOs.ValidationMessageKey.TARGET_DIR_OVERLAPS_SOURCE_DIRS, validationDetail.messageKey)
        }
        validationResult.validationDetails.last().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
            assertEquals(sourceArchiveDir4.toString(), validationDetail.path)
            assertEquals(DTOs.ValidationMessageKey.TARGET_DIR_OVERLAPS_SOURCE_DIRS, validationDetail.messageKey)
        }
    }

    @Test
    fun `test validation error because document type specific source directories overlap`() {
        val overlappingSourceWithDocTypeFolders = listOf(
            blueSkyConnectors().first().copy(
                sourceFolder = sourceFolder2,
                docTypeFolders = mapOf(
                    DocumentType.CONTAINER to Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.CREDIT to Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.FORM to Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.HOSPITAL_MCD to Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.INVOICE to Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.NOTIFICATION to Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir)
                )
            )
        )

        val cdrClientConfig = createCdrClientConfig(overlappingSourceWithDocTypeFolders)

        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig.toDto())
        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        assertEquals(1, validationResult.validationDetails.size)
        validationResult.validationDetails.first().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
            assertEquals(multiPurposeTempDir.toString(), validationDetail.path)
            assertEquals(DTOs.ValidationMessageKey.DUPLICATE_SOURCE_DIRS, validationDetail.messageKey)
        }
    }

    @Test
    fun `test validation error because same connector uses same mode twice`() {
        val sameModeTwice = listOf(
            blueSkyConnectors()[0].copy(mode = CdrClientConfig.Mode.TEST),
            blueSkyConnectors()[1].copy(mode = CdrClientConfig.Mode.TEST)
        )

        val cdrClientConfig = createCdrClientConfig(sameModeTwice)

        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig.toDto())
        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        assertEquals(1, validationResult.validationDetails.size)
        validationResult.validationDetails.first().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.ConnectorDetail>(validationDetail)
            assertEquals(DomainObjects.ConfigurationItem.CONNECTOR_MODE, validationDetail.configItem)
            assertEquals(DTOs.ValidationMessageKey.DUPLICATE_MODE, validationDetail.messageKey)
        }
    }

    @Test
    fun `validateConnectorIdIsPresent should return Success when customer list is null`() {
        val result = configValidationService.validateConnectorIdIsPresent(null)
        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateConnectorIdIsPresent should return Success when customer list is empty`() {
        val result = configValidationService.validateConnectorIdIsPresent(emptyList())
        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateConnectorIdIsPresent should return Success when all connectors have non-blank connector IDs`() {
        val connectors = listOf(
            createConnector("connector1"),
            createConnector("connector2")
        )

        val result = configValidationService.validateConnectorIdIsPresent(connectors)
        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateConnectorIdIsPresent should return Failure when at least one connector has a blank connector ID`() {
        val connectors = listOf(
            createConnector("connector1"),
            createConnector("")
        )

        val result = configValidationService.validateConnectorIdIsPresent(connectors)

        assertInstanceOf<DTOs.ValidationResult.Failure>(result) { "Expected Failure but got $result" }
        assertEquals(1, result.validationDetails.size)

        val validationDetail = result.validationDetails.first()
        assertInstanceOf<DTOs.ValidationDetail.ConfigItemDetail>(validationDetail)
        assertEquals(DomainObjects.ConfigurationItem.CONNECTOR, validationDetail.configItem)
        assertEquals(DTOs.ValidationMessageKey.NO_CONNECTOR_CONFIGURED, validationDetail.messageKey)
    }

    @Test
    fun `validateModeValue should return Success for empty list`() {
        val result = configValidationService.validateModeValue(emptyList())
        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateModeValue should return Success when all connectors have valid modes`() {
        val connectors = listOf(
            createConnector("connector1", DTOs.CdrClientConfig.Mode.TEST),
            createConnector("connector2", DTOs.CdrClientConfig.Mode.PRODUCTION)
        )

        val result = configValidationService.validateModeValue(connectors)
        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateModeValue should return Failure when at least one connector has invalid mode`() {
        val connectors = listOf(
            createConnector("connector1", DTOs.CdrClientConfig.Mode.TEST),
            createConnector("connector2", DTOs.CdrClientConfig.Mode.NONE)
        )

        val result = configValidationService.validateModeValue(connectors)

        assertInstanceOf<DTOs.ValidationResult.Failure>(result)
        assertEquals(1, result.validationDetails.size)

        val validationDetail = result.validationDetails.first()
        assertInstanceOf<DTOs.ValidationDetail.ConnectorDetail>(validationDetail)
        assertEquals("connector2", validationDetail.connectorId)
        assertEquals(DomainObjects.ConfigurationItem.CONNECTOR_MODE, validationDetail.configItem)
        assertEquals(DTOs.ValidationMessageKey.ILLEGAL_MODE, validationDetail.messageKey)
    }

    @Test
    fun `validateFileBusyTestTimeout should return Success when timeout is greater than interval`() {
        val timeout = Duration.ofSeconds(10)
        val interval = Duration.ofSeconds(2)

        val result = configValidationService.validateFileBusyTestTimeout(timeout, interval)
        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateFileBusyTestTimeout should return Failure when timeout equals interval`() {
        val timeout = Duration.ofSeconds(5)

        val result = configValidationService.validateFileBusyTestTimeout(timeout, timeout)

        assertInstanceOf<DTOs.ValidationResult.Failure>(result)
        assertEquals(1, result.validationDetails.size)

        val validationDetail = result.validationDetails.first()
        assertInstanceOf<DTOs.ValidationDetail.ConfigItemDetail>(validationDetail)
        assertEquals(DomainObjects.ConfigurationItem.FILE_BUSY_TEST_TIMEOUT, validationDetail.configItem)
        assertEquals(DTOs.ValidationMessageKey.FILE_BUSY_TEST_TIMEOUT_TOO_LONG, validationDetail.messageKey)
    }

    @Test
    fun `validateFileBusyTestTimeout should return Failure when timeout is less than interval`() {
        val timeout = Duration.ofSeconds(1)
        val interval = Duration.ofSeconds(2)

        val result = configValidationService.validateFileBusyTestTimeout(timeout, interval)

        assertInstanceOf<DTOs.ValidationResult.Failure>(result)
        assertEquals(1, result.validationDetails.size)

        val validationDetail = result.validationDetails.first()
        assertInstanceOf<DTOs.ValidationDetail.ConfigItemDetail>(validationDetail)
        assertEquals(DomainObjects.ConfigurationItem.FILE_BUSY_TEST_TIMEOUT, validationDetail.configItem)
        assertEquals(DTOs.ValidationMessageKey.FILE_BUSY_TEST_TIMEOUT_TOO_LONG, validationDetail.messageKey)
    }

    private fun createCdrClientConfig(customers: List<Connector>, defaultLocalFolder: Path = localFolder0): CdrClientConfig =
        CdrClientConfig(
            fileSynchronizationEnabled = FileSynchronization.ENABLED,
            scheduleDelay = Duration.ofSeconds(1),
            localFolder = TempDownloadDir(defaultLocalFolder),
            cdrApi = CdrApi(
                scheme = "http",
                host = Host("localhost"),
                port = 8080,
                basePath = "api",
            ),
            credentialApi = CredentialApi(
                scheme = "http",
                host = Host("localhost"),
                port = 8080,
                basePath = "client-credentials",
            ),
            customer = Customer(customers.toMutableList()),
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
            retryDelay = listOf(Duration.ofSeconds(1)),
            filesInProgressCacheSize = DataSize.ofMegabytes(1),
            idpCredentials = IdpCredentials(
                tenantId = TenantId("fake-tenant-id"),
                clientId = ClientId("fake-client-id"),
                clientSecret = ClientSecret("fake-client-secret"),
                scopes = listOf("CDR"),
                renewCredential = RenewCredential(true),
                lastCredentialRenewalTime = BEGINNING_OF_TIME,
            ),
            idpEndpoint = URL("http://localhost"),
            fileBusyTestStrategy = FileBusyTestStrategyProperty(CdrClientConfig.FileBusyTestStrategy.FILE_SIZE_CHANGED),
            fileBusyTestInterval = Duration.ofMillis(250),
            fileBusyTestTimeout = Duration.ofSeconds(1),
            retryTemplate = CdrClientConfig.RetryTemplateConfig(
                retries = 3,
                initialDelay = Duration.ofSeconds(5),
                maxDelay = Duration.ofSeconds(5),
                multiplier = 2.0,
            ),
        )

    private fun blueSkyConnectors() = listOf(
        Connector(
            connectorId = ConnectorId("connectorId"),
            targetFolder = targetFolder0,
            sourceFolder = sourceFolder0,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.TEST,
            docTypeFolders = mapOf(
                DocumentType.CONTAINER to Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.CREDIT to Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.FORM to Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.HOSPITAL_MCD to Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.INVOICE to Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.NOTIFICATION to Connector.DocTypeFolders(targetFolder = targetFolder0),
            )
        ),
        Connector(
            connectorId = ConnectorId("connectorId"),
            targetFolder = targetFolder1,
            sourceFolder = sourceFolder1,
            sourceErrorFolder = sourceErrorDir1,
            sourceArchiveFolder = sourceArchiveDir1,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.CONTAINER to Connector.DocTypeFolders(sourceFolder = createResolvedDirectory(sourceFolder2.resolve("sub"))),
                DocumentType.CREDIT to Connector.DocTypeFolders(sourceFolder = createResolvedDirectory(sourceFolder2.resolve("sub1"))),
                DocumentType.FORM to Connector.DocTypeFolders(sourceFolder = createResolvedDirectory(sourceFolder2.resolve("sub2"))),
                DocumentType.HOSPITAL_MCD to Connector.DocTypeFolders(sourceFolder = createResolvedDirectory(sourceFolder2.resolve("sub3"))),
                DocumentType.INVOICE to Connector.DocTypeFolders(sourceFolder = createResolvedDirectory(sourceFolder2.resolve("sub4"))),
                DocumentType.NOTIFICATION to Connector.DocTypeFolders(sourceFolder = createResolvedDirectory(sourceFolder2.resolve("sub5"))),
            )
        ),
        Connector(
            connectorId = ConnectorId("connectorId2"),
            targetFolder = targetFolder2,
            sourceFolder = sourceFolder2,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.TEST
        ),
        Connector(
            connectorId = ConnectorId("connectorId3"),
            targetFolder = targetFolder3,
            sourceFolder = sourceFolder3,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.PRODUCTION
        ),
        Connector(
            connectorId = ConnectorId("connectorId4"),
            targetFolder = targetFolder4,
            sourceFolder = sourceFolder4,
            sourceErrorFolder = sourceErrorDir4,
            sourceArchiveFolder = sourceArchiveDir4,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.TEST
        )
    )

    fun connectorDirsAsLocalDirs(): List<Path> {
        return listOf(
            targetFolder0,
            sourceArchiveDir1,
            sourceFolder2,
            sourceErrorDir4,
        )
    }

    private fun createConnector(connectorId: String, mode: DTOs.CdrClientConfig.Mode = DTOs.CdrClientConfig.Mode.TEST): DTOs.CdrClientConfig.Connector =
        DTOs.CdrClientConfig.Connector(
            connectorId = connectorId,
            sourceFolder = "/path/to/source",
            targetFolder = "/path/to/target",
            contentType = "application/xml",
            mode = mode,
            docTypeFolders = emptyMap(),
            sourceErrorFolder = null,
            sourceArchiveFolder = null,
            sourceArchiveEnabled = false
        )

    private fun createResolvedDirectory(path: Path): Path {
        Files.createDirectories(path)
        return path
    }

    companion object {
        @JvmStatic
        val FORUM_DATENAUSTAUSCH_MEDIA_TYPE = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")
    }

}
