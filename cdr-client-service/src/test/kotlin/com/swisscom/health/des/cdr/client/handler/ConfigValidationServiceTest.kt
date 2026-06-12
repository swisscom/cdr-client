@file:Suppress("LargeClass")

package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.Constants.ARCHIVE_DIR_NAME
import com.swisscom.health.des.cdr.client.common.Constants.ERROR_DIR_NAME
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig as CdrClientConfigDto
import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.common.DomainObjects.ApiEndpoint
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
import com.swisscom.health.des.cdr.client.config.ProxyConfig
import com.swisscom.health.des.cdr.client.config.ProxyPassword
import com.swisscom.health.des.cdr.client.config.ProxyUrl
import com.swisscom.health.des.cdr.client.config.ProxyUsername
import com.swisscom.health.des.cdr.client.config.RenewCredential
import com.swisscom.health.des.cdr.client.config.Scope
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.TenantId
import com.swisscom.health.des.cdr.client.config.toDto
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively

@ExtendWith(MockKExtension::class)
internal class ConfigValidationServiceTest {

    @TempDir
    private lateinit var localFolder0: Path

    @TempDir
    private lateinit var sourceFolder0: Path

    private lateinit var sourceErrorFolder0: Path

    @TempDir
    private lateinit var targetFolder0: Path

    @TempDir
    private lateinit var sourceFolder1: Path

    @TempDir
    private lateinit var targetFolder1: Path

    @TempDir
    private lateinit var sourceErrorFolder1: Path

    @TempDir
    private lateinit var sourceArchiveFolder1: Path

    @TempDir
    private lateinit var sourceFolder2: Path

    private lateinit var sourceErrorFolder2: Path

    @TempDir
    private lateinit var targetFolder2: Path

    @TempDir
    private lateinit var sourceFolder3: Path

    private lateinit var sourceErrorFolder3: Path

    @TempDir
    private lateinit var targetFolder3: Path

    @TempDir
    private lateinit var sourceFolder4: Path

    @TempDir
    private lateinit var targetFolder4: Path

    @TempDir
    private lateinit var sourceErrorFolder4: Path

    @TempDir
    private lateinit var sourceArchiveFolder4: Path

    @TempDir
    private lateinit var multiPurposeTempDir: Path

    @MockK
    private lateinit var environment: Environment

    private lateinit var allGoodCdrClientConfig: CdrClientConfig

    private lateinit var configValidationService: ConfigValidationService

    @BeforeEach
    fun setUp() {
        // create error dirs that are "implied" by the blue sky connectors (null error dir -> default to `ERROR_DIR_NAME` relative to source folder)
        sourceErrorFolder0 = sourceFolder0.resolve(ERROR_DIR_NAME).createDirectories()
        sourceErrorFolder2 = sourceFolder2.resolve(ERROR_DIR_NAME).createDirectories()
        sourceErrorFolder3 = sourceFolder3.resolve(ERROR_DIR_NAME).createDirectories()

        every { environment.activeProfiles } returns arrayOf("test")
        allGoodCdrClientConfig = createCdrClientConfig(blueSkyConnectors())
        configValidationService = ConfigValidationService(allGoodCdrClientConfig)
    }

    @Test
    fun `test blue sky configuration`() {
        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(allGoodCdrClientConfig)
        assertEquals(DTOs.ValidationResult.Success, validationResult)
    }

    @Test
    fun `test validation error if local directory does not exist`() {
        val filePath = localFolder0.resolve("deeper")
        val clientConfig = allGoodCdrClientConfig.copy(localFolder = TempDownloadDir(filePath))
        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(clientConfig)

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
        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(clientConfig)

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

        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig)

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
        connectorDirs().forEach { notLegalLocalDir ->
            val cdrClientConfig = createCdrClientConfig(customers = blueSkyConnectors(), defaultLocalFolder = notLegalLocalDir)

            val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig)

            assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
            assertEquals(
                if (notLegalLocalDir == sourceErrorFolder4 || notLegalLocalDir == sourceArchiveFolder1) 2 else 1,
                validationResult.validationDetails.size
            )
            validationResult.validationDetails.first().let { validationDetail ->
                assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
                assertEquals(notLegalLocalDir.toString(), validationDetail.path)
                assertTrue(
                    listOf(
                        DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_SOURCE_DIRS,
                        DTOs.ValidationMessageKey.LOCAL_DIR_OVERLAPS_WITH_TARGET_DIRS,
                        DTOs.ValidationMessageKey.ERROR_DIR_OVERLAPS_NON_ERROR_DIR
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

            val validationResult: DTOs.ValidationResult = configValidationService.validateDirectoryOverlap(cdrClientConfig.toDto())

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

            val validationResult: DTOs.ValidationResult = configValidationService.validateDirectoryOverlap(cdrClientConfig.toDto())

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
    fun `test validation success because source and archive directories cannot overlap`() {
        val overlappingSourceWithErrorOrArchive = listOf(
            blueSkyConnectors().first().copy(
                sourceFolder = sourceErrorFolder1,
                sourceErrorFolder = sourceErrorFolder1,
                mode = CdrClientConfig.Mode.PRODUCTION
            ),
            blueSkyConnectors().first()
                // if source and archive directory resolve to the same location, then the default `archive` subdirectory is appended when computing the
                // effective archive directory -> effective(!) source and archive directories cannot overlap
                .copy(
                    sourceFolder = sourceArchiveFolder4,
                    sourceArchiveFolder = sourceArchiveFolder4,
                    sourceArchiveEnabled = true,
                    mode = CdrClientConfig.Mode.TEST
                )
        )

        val cdrClientConfig = createCdrClientConfig(overlappingSourceWithErrorOrArchive)

        val validationResult: DTOs.ValidationResult = configValidationService.validateDirectoryOverlap(cdrClientConfig.toDto())
        assertInstanceOf<DTOs.ValidationResult.Success>(validationResult)
    }

    @Test
    @Disabled(
        "For the ui it would be nice to highlight nonsensical overlaps of source directories within the connector, " +
                "but operationally they are not a problem as we register the distinct set of source directories for polling/event watching"
    )
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

        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig)
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

        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig)
        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        assertEquals(1, validationResult.validationDetails.size)
        validationResult.validationDetails.first().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.ConnectorDetail>(validationDetail)
            assertEquals(DomainObjects.ConfigurationItem.CONNECTOR_MODE, validationDetail.configItem)
            assertEquals(DTOs.ValidationMessageKey.DUPLICATE_MODE, validationDetail.messageKey)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `test validation error because non-error folders end with error name`() {
        val sourceWithErrorName = sourceFolder1.resolve("error")
        Files.createDirectories(sourceWithErrorName)
        val targetWithErrorName = targetFolder1.resolve("error")
        Files.createDirectories(targetWithErrorName)
        val archiveWithErrorName = sourceFolder2.resolve("error")
        Files.createDirectories(archiveWithErrorName)
        val docTypeSourceWithErrorName = sourceFolder3.resolve("error")
        Files.createDirectories(docTypeSourceWithErrorName)
        val docTypeTargetWithErrorName = targetFolder3.resolve("error")
        Files.createDirectories(docTypeTargetWithErrorName)

        val connectors = listOf(
            blueSkyConnectors().first().copy(
                sourceFolder = sourceWithErrorName,
                mode = CdrClientConfig.Mode.PRODUCTION
            ),
            blueSkyConnectors().first().copy(
                targetFolder = targetWithErrorName,
                sourceFolder = sourceFolder4,
                mode = CdrClientConfig.Mode.TEST
            ),
            blueSkyConnectors()[3].copy(
                sourceArchiveEnabled = true,
                sourceArchiveFolder = archiveWithErrorName,
                sourceFolder = sourceFolder2
            ),
            blueSkyConnectors()[4].copy(
                sourceFolder = sourceFolder3,
                docTypeFolders = mapOf(
                    DocumentType.CONTAINER to Connector.DocTypeFolders(sourceFolder = docTypeSourceWithErrorName)
                )
            ),
            Connector(
                connectorId = ConnectorId("connectorId5"),
                targetFolder = targetFolder4,
                sourceFolder = multiPurposeTempDir,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
                mode = CdrClientConfig.Mode.TEST,
                docTypeFolders = mapOf(
                    DocumentType.INVOICE to Connector.DocTypeFolders(targetFolder = docTypeTargetWithErrorName)
                )
            )
        )

        val cdrClientConfig = createCdrClientConfig(connectors)
        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig)

        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        val validationDetails: List<DTOs.ValidationDetail> =
            validationResult.validationDetails.filter { it.messageKey == DTOs.ValidationMessageKey.ERROR_AS_NON_ERROR_FOLDER_NAME_USED }
        assertTrue(validationDetails.size == 5)

        val errorPaths = validationDetails.map {
            (it as DTOs.ValidationDetail.PathDetail).path
        }
        assertTrue(errorPaths.contains(sourceWithErrorName.toString()))
        assertTrue(errorPaths.contains(targetWithErrorName.toString()))
        assertTrue(errorPaths.contains(archiveWithErrorName.toString()))
        assertTrue(errorPaths.contains(docTypeSourceWithErrorName.toString()))
        assertTrue(errorPaths.contains(docTypeTargetWithErrorName.toString()))
    }

    @Test
    fun `test validation success when error folders end with error name`() {
        val errorDirWithErrorName = sourceFolder1.resolve("error")
        Files.createDirectories(errorDirWithErrorName)

        val connectors = listOf(
            blueSkyConnectors().first().copy(
                sourceFolder = sourceFolder1,
                targetFolder = targetFolder1,
                sourceErrorFolder = errorDirWithErrorName,
                mode = CdrClientConfig.Mode.PRODUCTION
            )
        )

        val cdrClientConfig = createCdrClientConfig(connectors)
        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig)

        assertEquals(DTOs.ValidationResult.Success, validationResult)
    }

    @Test
    fun `test validation error when local folder ends with error name`() {
        val localWithErrorName = multiPurposeTempDir.resolve("error")
        Files.createDirectories(localWithErrorName)

        val cdrClientConfig = createCdrClientConfig(blueSkyConnectors(), localWithErrorName)
        val validationResult: DTOs.ValidationResult = configValidationService.validateAllConfigurationItems(cdrClientConfig)

        assertInstanceOf<DTOs.ValidationResult.Failure>(validationResult)
        assertEquals(1, validationResult.validationDetails.size)
        validationResult.validationDetails.first().let { validationDetail ->
            assertInstanceOf<DTOs.ValidationDetail.PathDetail>(validationDetail)
            assertEquals(localWithErrorName.toString(), validationDetail.path)
            assertEquals(DTOs.ValidationMessageKey.ERROR_AS_NON_ERROR_FOLDER_NAME_USED, validationDetail.messageKey)
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
            createConnector("connector1", CdrClientConfigDto.Mode.TEST),
            createConnector("connector2", CdrClientConfigDto.Mode.PRODUCTION)
        )

        val result = configValidationService.validateModeValue(connectors)
        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateModeValue should return Failure when at least one connector has invalid mode`() {
        val connectors = listOf(
            createConnector("connector1", CdrClientConfigDto.Mode.TEST),
            createConnector("connector2", CdrClientConfigDto.Mode.NONE)
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

    @OptIn(ExperimentalPathApi::class)
    @Test
    @Suppress("NestedBlockDepth")
    fun `test validation error for relative source and target folders`() {
        val dirs = listOf("relative/source", "relative/target")
        try {
            dirs.forEach { Files.createDirectories(Paths.get(it)) }
            val connector = Connector(
                connectorId = ConnectorId("test-connector"),
                sourceFolder = Paths.get("relative/source"),
                targetFolder = Paths.get("relative/target"),
                contentType = "application/xml",
                mode = CdrClientConfig.Mode.TEST,
                docTypeFolders = emptyMap(),
                sourceErrorFolder = Paths.get("relative/error"),
                sourceArchiveFolder = Paths.get("relative/archive"),
                sourceArchiveEnabled = true,
            )
            val config = createCdrClientConfig(listOf(connector))
            val service = ConfigValidationService(config)
            val result = service.validateAllConfigurationItems(config)
            assertInstanceOf<DTOs.ValidationResult.Failure>(result)
            val details = result.validationDetails.filterIsInstance<DTOs.ValidationDetail.PathDetail>()
                .filter { it.messageKey == DTOs.ValidationMessageKey.DIRECTORY_NEEDS_ABSOLUTE_PATH }
            assertEquals(2, details.size)
            val failedPaths = details.map { it.path }
            assertTrue(failedPaths.contains("relative/source"))
            assertTrue(failedPaths.contains("relative/target"))
            details.forEach { assertEquals(DTOs.ValidationMessageKey.DIRECTORY_NEEDS_ABSOLUTE_PATH, it.messageKey) }
        } finally {
            dirs.forEach { dir: String ->
                val path = Paths.get(dir)
                Files.walk(path).use { dirEntries ->
                    val containsFiles = dirEntries.anyMatch { dirEntry: Path -> Files.isRegularFile(dirEntry) }
                    if (!containsFiles) {
                        path.deleteRecursively()
                    }
                }
            }
            Paths.get("relative").deleteIfExists()
        }
    }

    @Test
    fun `test validation error for absolute directories that are not read-writable`() {
        // none of the directories exist, so they are also not read/writable
        val absSourceDir = multiPurposeTempDir.resolve(Path.of("base", "source"))
        val absTargetDir = multiPurposeTempDir.resolve(Path.of("base", "target"))
        val absArchiveDir = multiPurposeTempDir.resolve(Path.of("base", "archive"))
        val absErrorDir = multiPurposeTempDir.resolve(Path.of("base", "error"))
        val absInvoiceSourceDir = multiPurposeTempDir.resolve(Path.of("base", "invoice", "source"))
        val absInvoiceTargetDir = multiPurposeTempDir.resolve(Path.of("base", "invoice", "target"))
        val absInvoiceArchiveDir = multiPurposeTempDir.resolve(Path.of("base", "invoice", "archive"))
        val absInvoiceErrorDir = multiPurposeTempDir.resolve(Path.of("base", "invoice", "error"))

        Files.createDirectories(
            multiPurposeTempDir.resolve(Path.of("base")),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        )
        Files.createDirectories(
            multiPurposeTempDir.resolve(Path.of("base", "invoice")),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        )

        Files.createDirectories(absSourceDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")))
        Files.createDirectories(absTargetDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")))
        Files.createDirectories(absArchiveDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")))
        Files.createDirectories(absErrorDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")))
        Files.createDirectories(absInvoiceSourceDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")))
        Files.createDirectories(absInvoiceTargetDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")))
        Files.createDirectories(absInvoiceArchiveDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")))
        Files.createDirectories(absInvoiceErrorDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")))

        val connector = CdrClientConfigDto.Connector(
            connectorId = "connectorId",
            targetFolder = absTargetDir.toString(),
            sourceFolder = absSourceDir.toString(),
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            sourceArchiveEnabled = true,
            sourceArchiveFolder = absArchiveDir.toString(),
            sourceErrorFolder = absErrorDir.toString(),
            mode = CdrClientConfigDto.Mode.TEST,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to CdrClientConfigDto.Connector.DocTypeFolders(
                    sourceFolder = absInvoiceSourceDir.toString(),
                    targetFolder = absInvoiceTargetDir.toString(),
                    archiveFolder = absInvoiceArchiveDir.toString(),
                    errorFolder = absInvoiceErrorDir.toString(),
                )
            )
        )

        val result = configValidationService.validateConnectorFolders(connector)

        assertInstanceOf<DTOs.ValidationResult.Failure>(result)

        val details = result.validationDetails.filterIsInstance<DTOs.ValidationDetail.PathDetail>()
        assertEquals(8, details.size)
        val failedPaths = details.map { it.path }
        assertTrue(failedPaths.contains(absSourceDir.toString()))
        assertTrue(failedPaths.contains(absTargetDir.toString()))
        assertTrue(failedPaths.contains(absErrorDir.toString()))
        assertTrue(failedPaths.contains(absArchiveDir.toString()))
        assertTrue(failedPaths.contains(absInvoiceSourceDir.toString()))
        assertTrue(failedPaths.contains(absInvoiceTargetDir.toString()))
        assertTrue(failedPaths.contains(absInvoiceArchiveDir.toString()))
        assertTrue(failedPaths.contains(absInvoiceErrorDir.toString()))
        details.forEach { pathDetail -> assertEquals(DTOs.ValidationMessageKey.NOT_READ_WRITABLE, pathDetail.messageKey) }
    }

    @Test
    fun `test validation error if cdr-api is illegal host`() {
        val config = createCdrClientConfig(blueSkyConnectors()).run {
            copy(
                cdrApi = cdrApi.copy(
                    host = Host("illegal host!")
                )
            )
        }
        val service = ConfigValidationService(config)
        val result = service.validateAllConfigurationItems(config)

        assertInstanceOf<DTOs.ValidationResult.Failure>(result)
        assertEquals(1, result.validationDetails.size)

        val validationDetail = result.validationDetails.first()
        assertInstanceOf<DTOs.ValidationDetail.ConfigItemDetail>(validationDetail)
        assertEquals(DomainObjects.ConfigurationItem.CDR_API_HOST, validationDetail.configItem)
        assertEquals(DTOs.ValidationMessageKey.ILLEGAL_VALUE, validationDetail.messageKey)

    }

    @Test
    fun `test validation error if cdr-api host and-or scope and-or tenant are from different environments`() {
        val config = createCdrClientConfig(blueSkyConnectors()).run {
            copy(
                cdrApi = cdrApi.copy(
                    // tenant id and scope are for localhost -> mismatch with cdr api endpoint
                    host = Host(ApiEndpoint.PRODUCTION.host),
                    port = ApiEndpoint.PRODUCTION.port,
                    scheme = ApiEndpoint.PRODUCTION.protocol
                )
            )
        }
        val service = ConfigValidationService(config)
        val result = service.validateAllConfigurationItems(config)

        assertInstanceOf<DTOs.ValidationResult.Failure>(result)
        assertEquals(1, result.validationDetails.size)

        val validationDetail = result.validationDetails.first()
        assertInstanceOf<DTOs.ValidationDetail.ConfigItemDetail>(validationDetail)
        assertEquals(DomainObjects.ConfigurationItem.CDR_API_HOST, validationDetail.configItem)
        assertEquals(DTOs.ValidationMessageKey.ILLEGAL_VALUE_COMBINATION, validationDetail.messageKey)
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `test validation passes when error directory is same as source folder`() {
        val tempDir = Files.createTempDirectory("sourceAndErrorDir")
        // if error dir and source dir evaluate to the same path, even if it is specified as an absolute path, then the actual error directory
        // is the `ERROR_DIR_NAME` resolved relative to the source directory; and that directory must exist for all validations to pass
        tempDir.resolve(ERROR_DIR_NAME).createDirectory()
        try {
            val connector = Connector(
                connectorId = ConnectorId("test-connector"),
                sourceFolder = tempDir,
                targetFolder = Files.createTempDirectory("targetDir"),
                contentType = "application/xml",
                mode = CdrClientConfig.Mode.TEST,
                docTypeFolders = emptyMap(),
                sourceErrorFolder = tempDir,
                sourceArchiveFolder = null,
                sourceArchiveEnabled = false,
            )
            val config = createCdrClientConfig(listOf(connector))
            val service = ConfigValidationService(config)
            val result = service.validateAllConfigurationItems(config)
            assertEquals(DTOs.ValidationResult.Success, result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test validation handles path with trailing space gracefully`() {
        val tempSourceDir = Files.createTempDirectory("testSourceDir")
        val tempTargetDir = Files.createTempDirectory("testTargetDir")
        try {
            // Create connector with valid paths
            val connector: CdrClientConfigDto.Connector = Connector(
                connectorId = ConnectorId("test-connector"),
                sourceFolder = tempSourceDir,
                targetFolder = tempTargetDir,
                contentType = "application/xml",
                mode = CdrClientConfig.Mode.TEST,
                docTypeFolders = emptyMap(),
                sourceErrorFolder = null,
                sourceArchiveFolder = null,
                sourceArchiveEnabled = false,
            ).toDto()

            // Now create a DTO with paths that have trailing spaces
            val connectorWithTrailingSpace = connector.copy(
                sourceFolder = "$tempSourceDir ",
                targetFolder = "$tempTargetDir "
            )

            val result: DTOs.ValidationResult = configValidationService.validateConnectorFolders(connectorWithTrailingSpace)

            // The paths with trailing spaces should FAIL validation
            // On Windows: InvalidPathException -> null path -> directory not found
            // On Unix: Path created with trailing space that doesn't exist -> directory not found
            assertInstanceOf<DTOs.ValidationResult.Failure>(result)
            assertTrue(result.validationDetails.isNotEmpty())
            // Should have at least one path detail with directory not found
            assertTrue(result.validationDetails.any {
                it is DTOs.ValidationDetail.PathDetail &&
                        it.messageKey == DTOs.ValidationMessageKey.DIRECTORY_NOT_FOUND
            })
        } finally {
            Files.deleteIfExists(tempSourceDir)
            Files.deleteIfExists(tempTargetDir)
        }
    }

    @Test
    fun `test validation handles UNC paths without NullPointerException`() {
        val tempSourceDir = Files.createTempDirectory("testSourceDir")
        val tempTargetDir = Files.createTempDirectory("testTargetDir")
        try {
            // Create connector with a UNC path (this simulates Windows network share paths)
            val connector = Connector(
                connectorId = ConnectorId("test-connector"),
                sourceFolder = tempSourceDir,
                targetFolder = tempTargetDir,
                contentType = "application/xml",
                mode = CdrClientConfig.Mode.TEST,
                docTypeFolders = emptyMap(),
                sourceErrorFolder = null,
                sourceArchiveFolder = null,
                sourceArchiveEnabled = false,
            ).toDto()

            // Create a DTO with a UNC path (Windows network share)
            val connectorWithUncPath = connector.copy(
                sourceFolder = """\\cdrintstpublic.file.core.windows.net\test-file-share"""
            )

            // This should not throw NullPointerException when checking fileName
            // It should return a validation error (directory not found)
            val result = configValidationService.validateConnectorFolders(connectorWithUncPath)

            // The UNC path likely won't exist, so we expect a failure
            // The important thing is that it doesn't crash with NullPointerException
            assertInstanceOf<DTOs.ValidationResult.Failure>(result)
        } finally {
            Files.deleteIfExists(tempSourceDir)
            Files.deleteIfExists(tempTargetDir)
        }
    }

    private fun createCdrClientConfig(customers: List<Connector>, defaultLocalFolder: Path = localFolder0): CdrClientConfig =
        CdrClientConfig(
            fileSynchronizationEnabled = FileSynchronization.ENABLED,
            scheduleDelay = Duration.ofSeconds(1),
            localFolder = TempDownloadDir(defaultLocalFolder),
            cdrApi = CdrApi(
                scheme = "http",
                host = Host("localhost"),
                port = 87,
                basePath = "api",
            ),
            credentialApi = CredentialApi(
                scheme = "http",
                host = Host("localhost"),
                port = 87,
                basePath = "client-credentials",
            ),
            customer = Customer(customers.toMutableList()),
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
            retryDelay = listOf(Duration.ofSeconds(1)),
            filesInProgressCacheSize = DataSize.ofMegabytes(1),
            idpCredentials = IdpCredentials(
                tenantId = TenantId(DomainObjects.TenantId.LOCALHOST.tenantId),
                clientId = ClientId("fake-client-id"),
                clientSecret = ClientSecret("fake-client-secret"),
                scope = Scope(DomainObjects.OAuthScope.LOCALHOST.scope),
                renewCredential = RenewCredential(true),
                lastCredentialRenewalTime = BEGINNING_OF_TIME,
            ),
            idpEndpoint = URI("http://localhost").toURL(),
            fileBusyTestStrategy = FileBusyTestStrategyProperty(CdrClientConfig.FileBusyTestStrategy.FILE_SIZE_CHANGED),
            fileBusyTestInterval = Duration.ofMillis(250),
            fileBusyTestTimeout = Duration.ofSeconds(1),
            retryTemplate = CdrClientConfig.RetryTemplateConfig(
                retries = 3,
                initialDelay = Duration.ofSeconds(5),
                maxDelay = Duration.ofSeconds(5),
                multiplier = 2.0,
            ),
            proxyConfig = ProxyConfig(
                url = ProxyUrl(""),
                username = ProxyUsername(""),
                password = ProxyPassword(""),
            ),
            oldFileThreshold = Duration.ofHours(2L),
            fileSystemCheckInterval = Duration.ofMinutes(5L),
        )

    @Suppress("LongMethod")
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
            sourceErrorFolder = sourceErrorFolder1,
            sourceArchiveFolder = sourceArchiveFolder1,
            sourceArchiveEnabled = true,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.CONTAINER to Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub").createDirectories()).also {
                    // create error dirs that are "implied" by the blue sky connectors;
                    //   null error dir -> default to `ERROR_DIR_NAME` relative to source folder
                    // create archive dirs that are "implied" by the blue sky connectors;
                    //   null archive dir -> default to `ARCHIVE_DIR_NAME` relative to source folder
                    it.sourceFolder?.resolve(ERROR_DIR_NAME)?.createDirectories()
                    it.sourceFolder?.resolve(ARCHIVE_DIR_NAME)?.createDirectories()
                },
                DocumentType.CREDIT to Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub1").createDirectories()).also {
                    it.sourceFolder?.resolve(ERROR_DIR_NAME)?.createDirectories()
                    it.sourceFolder?.resolve(ARCHIVE_DIR_NAME)?.createDirectories()
                },
                DocumentType.FORM to Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub2").createDirectories()).also {
                    it.sourceFolder?.resolve(ERROR_DIR_NAME)?.createDirectories()
                    it.sourceFolder?.resolve(ARCHIVE_DIR_NAME)?.createDirectories()
                },
                DocumentType.HOSPITAL_MCD to Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub3").createDirectories()).also {
                    it.sourceFolder?.resolve(ERROR_DIR_NAME)?.createDirectories()
                    it.sourceFolder?.resolve(ARCHIVE_DIR_NAME)?.createDirectories()
                },
                DocumentType.INVOICE to Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub4").createDirectories()).also {
                    it.sourceFolder?.resolve(ERROR_DIR_NAME)?.createDirectories()
                    it.sourceFolder?.resolve(ARCHIVE_DIR_NAME)?.createDirectories()
                },
                DocumentType.NOTIFICATION to Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub5").createDirectories()).also {
                    it.sourceFolder?.resolve(ERROR_DIR_NAME)?.createDirectories()
                    it.sourceFolder?.resolve(ARCHIVE_DIR_NAME)?.createDirectories()
                },
            )
        ),
        Connector(
            connectorId = ConnectorId("connectorId2"),
            targetFolder = targetFolder2,
            sourceFolder = sourceFolder2,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.TEST,
        ),
        Connector(
            connectorId = ConnectorId("connectorId3"),
            targetFolder = targetFolder3,
            sourceFolder = sourceFolder3,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.PRODUCTION,
        ),
        Connector(
            connectorId = ConnectorId("connectorId4"),
            targetFolder = targetFolder4,
            sourceFolder = sourceFolder4,
            sourceErrorFolder = sourceErrorFolder4,
            sourceArchiveFolder = sourceArchiveFolder4,
            sourceArchiveEnabled = true,
            contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE.toString(),
            mode = CdrClientConfig.Mode.TEST,
        )
    )

    fun connectorDirs(): List<Path> {
        return listOf(
            targetFolder0,
            sourceArchiveFolder1,
            sourceFolder2,
            sourceErrorFolder4,
        )
    }

    private fun createConnector(connectorId: String, mode: CdrClientConfigDto.Mode = CdrClientConfigDto.Mode.TEST): CdrClientConfigDto.Connector =
        CdrClientConfigDto.Connector(
            connectorId = connectorId,
            sourceFolder = "/path/to/source",
            targetFolder = "/path/to/target",
            contentType = "application/xml",
            mode = mode,
            docTypeFolders = emptyMap(),
            sourceErrorFolder = null,
            sourceArchiveFolder = null,
            sourceArchiveEnabled = false,
        )

    companion object {
        @JvmStatic
        val FORUM_DATENAUSTAUSCH_MEDIA_TYPE = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")
    }

}
