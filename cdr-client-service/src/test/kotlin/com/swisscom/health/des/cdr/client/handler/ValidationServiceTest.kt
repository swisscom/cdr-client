package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
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
import com.swisscom.health.des.cdr.client.xml.DocumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal class ValidationServiceTest {

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

    private lateinit var allGoodCdrClientConfig: CdrClientConfig

    private lateinit var validationService: ValidationService

    @BeforeEach
    fun setUp() {
        allGoodCdrClientConfig = createCdrClientConfig(blueSkyConnectors())
        validationService = ValidationService(allGoodCdrClientConfig)
    }

    @Test
    fun `test blue sky configuration`() {
        val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(allGoodCdrClientConfig.toDto())
        assertEquals(DTOs.ValidationResult.Success, validationResult)
    }

    @Test
    fun `test validation error if local directory does not exist`() {
        val filePath = localFolder0.resolve("deeper")
        val clientConfig = allGoodCdrClientConfig.copy(localFolder = TempDownloadDir(filePath))
        val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(clientConfig.toDto())

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
        val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(clientConfig.toDto())

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

        val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(cdrClientConfig.toDto())

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

            val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(cdrClientConfig.toDto())

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
    fun `test validation error because source folders overlap`() {
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

            val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(cdrClientConfig.toDto())

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

            val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(cdrClientConfig.toDto())

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

        val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(cdrClientConfig.toDto())
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
                    DocumentType.CONTAINER to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.CREDIT to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.FORM to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.HOSPITAL_MCD to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.INVOICE to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir),
                    DocumentType.NOTIFICATION to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = multiPurposeTempDir)
                )
            )
        )

        val cdrClientConfig = createCdrClientConfig(overlappingSourceWithDocTypeFolders)

        val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(cdrClientConfig.toDto())
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

        val validationResult: DTOs.ValidationResult = validationService.validateAllConfigurationItems(cdrClientConfig.toDto())
        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        assertEquals(1, validationResult.validationDetails.size)
        validationResult.validationDetails.first().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.ConfigItemDetail>(validationDetail)
            assertEquals(DomainObjects.ConfigurationItem.CONNECTOR_MODE, validationDetail.configItem)
            assertEquals(DTOs.ValidationMessageKey.DUPLICATE_MODE, validationDetail.messageKey)
        }
    }


    private fun createCdrClientConfig(customers: List<CdrClientConfig.Connector>, defaultLocalFolder: Path = localFolder0): CdrClientConfig =
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
            customer = Customer(customers),
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
            retryDelay = listOf(Duration.ofSeconds(1)),
            filesInProgressCacheSize = DataSize.ofMegabytes(1),
            idpCredentials = IdpCredentials(
                tenantId = TenantId("fake-tenant-id"),
                clientId = ClientId("fake-client-id"),
                clientSecret = ClientSecret("fake-client-secret"),
                scopes = listOf("CDR"),
                renewCredential = RenewCredential(true)
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
        CdrClientConfig.Connector(
            connectorId = "connectorId",
            targetFolder = targetFolder0,
            sourceFolder = sourceFolder0,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.TEST,
            docTypeFolders = mapOf(
                DocumentType.CONTAINER to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.CREDIT to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.FORM to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.HOSPITAL_MCD to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.INVOICE to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                DocumentType.NOTIFICATION to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
            )
        ),
        CdrClientConfig.Connector(
            connectorId = "connectorId",
            targetFolder = targetFolder1,
            sourceFolder = sourceFolder1,
            sourceErrorFolder = sourceErrorDir1,
            sourceArchiveFolder = sourceArchiveDir1,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.CONTAINER to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub")),
                DocumentType.CREDIT to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub1")),
                DocumentType.FORM to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub2")),
                DocumentType.HOSPITAL_MCD to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub3")),
                DocumentType.INVOICE to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub4")),
                DocumentType.NOTIFICATION to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub5")),
            )
        ),
        CdrClientConfig.Connector(
            connectorId = "connectorId2",
            targetFolder = targetFolder2,
            sourceFolder = sourceFolder2,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.TEST
        ),
        CdrClientConfig.Connector(
            connectorId = "connectorId3",
            targetFolder = targetFolder3,
            sourceFolder = sourceFolder3,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.PRODUCTION
        ),
        CdrClientConfig.Connector(
            connectorId = "connectorId4",
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

    companion object {
        @JvmStatic
        val FORUM_DATENAUSTAUSCH_MEDIA_TYPE = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")
    }

}
