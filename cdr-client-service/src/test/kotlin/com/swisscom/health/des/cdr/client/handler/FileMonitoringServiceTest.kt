package com.swisscom.health.des.cdr.client.handler

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
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.Companion.TEMP_FILE_EXTENSION
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

internal class FileMonitoringServiceTest {

    @TempDir
    private lateinit var tempDir: Path

    @TempDir
    private lateinit var errorDir1: Path

    @TempDir
    private lateinit var errorDir2: Path

    @TempDir
    private lateinit var sourceDir1: Path

    @TempDir
    private lateinit var sourceDir2: Path

    @TempDir
    private lateinit var targetDir1: Path

    @TempDir
    private lateinit var targetDir2: Path

    private lateinit var fileMonitoringService: FileMonitoringService

    @BeforeEach
    fun setUp() {
        val config = createDefaultConfig()
        fileMonitoringService = FileMonitoringService(config)
    }

    @Test
    fun `should report no issues when all directories are empty`() = runTest {
        fileMonitoringService.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus

        assertEquals(0, status.errorFileCount)
        assertEquals(0, status.oldTempFileCount)
    }

    @Test
    fun `should count XML files in error directories`() = runTest {
        createErrorFile(errorDir1, "error1.xml")
        createErrorFile(errorDir1, "error2.xml")
        createErrorFile(errorDir2, "error3.xml")

        fileMonitoringService.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus
        assertEquals(3, status.errorFileCount)
    }

    @Test
    fun `should ignore non-XML files in error directories`() = runTest {
        createErrorFile(errorDir1, "error1.xml")
        createErrorFile(errorDir1, "error2.txt")
        createErrorFile(errorDir1, "error3.response")
        createErrorFile(errorDir2, "error4.xml")

        fileMonitoringService.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus
        assertEquals(2, status.errorFileCount)
    }

    @Test
    fun `should count old temp files older than 2 hours`() = runTest {
        val now = Instant.now()
        val threeHoursAgo = now.minus(3, ChronoUnit.HOURS)
        val oneHourAgo = now.minus(1, ChronoUnit.HOURS)

        createTempFile(tempDir, "old1.$TEMP_FILE_EXTENSION", threeHoursAgo)
        createTempFile(tempDir, "old2.$TEMP_FILE_EXTENSION", threeHoursAgo)
        createTempFile(tempDir, "recent.$TEMP_FILE_EXTENSION", oneHourAgo)

        fileMonitoringService.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus
        assertEquals(2, status.oldTempFileCount)
    }

    @Test
    fun `should ignore non-temp files in temp directory`() = runTest {
        val threeHoursAgo = Instant.now().minus(3, ChronoUnit.HOURS)

        createTempFile(tempDir, "file1.$TEMP_FILE_EXTENSION", threeHoursAgo)
        createTempFile(tempDir, "file2.xml", threeHoursAgo)
        createTempFile(tempDir, "file3.txt", threeHoursAgo)

        fileMonitoringService.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus
        assertEquals(1, status.oldTempFileCount)
    }

    @Test
    fun `should not count recent temp files`() = runTest {
        val oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS)

        createTempFile(tempDir, "recent1.$TEMP_FILE_EXTENSION", oneHourAgo)
        createTempFile(tempDir, "recent2.$TEMP_FILE_EXTENSION", oneHourAgo)

        fileMonitoringService.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus
        assertEquals(0, status.oldTempFileCount)
    }

    @Test
    fun `should count files in nested error directories`() = runTest {
        val nestedDir = errorDir1.resolve("subfolder").createDirectories()
        createErrorFile(errorDir1, "error1.xml")
        createErrorFile(nestedDir, "error2.xml")

        fileMonitoringService.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus
        assertEquals(2, status.errorFileCount)
    }

    @Test
    fun `should handle both error files and old temp files`() = runTest {
        val threeHoursAgo = Instant.now().minus(3, ChronoUnit.HOURS)

        createErrorFile(errorDir1, "error1.xml")
        createErrorFile(errorDir2, "error2.xml")
        createTempFile(tempDir, "old.$TEMP_FILE_EXTENSION", threeHoursAgo)

        fileMonitoringService.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus
        assertEquals(2, status.errorFileCount)
        assertEquals(1, status.oldTempFileCount)
    }

    @Test
    fun `should handle missing error directories gracefully`() = runTest {
        val nonExistentErrorDir = tempDir.resolve("does-not-exist")
        val connector = Connector(
            connectorId = ConnectorId("test"),
            targetFolder = targetDir1,
            sourceFolder = sourceDir1,
            contentType = MediaType.APPLICATION_XML.toString(),
            mode = CdrClientConfig.Mode.TEST,
            sourceErrorFolder = nonExistentErrorDir,
        )
        val config = createConfigWith(connector)
        val service = FileMonitoringService(config)

        service.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus
        assertEquals(0, status.errorFileCount)
    }

    @Test
    fun `should update status on subsequent checks`() = runTest {
        fileMonitoringService.checkFileStatus()

        var status = fileMonitoringService.monitoringStatus
        assertEquals(0, status.errorFileCount)

        createErrorFile(errorDir1, "error1.xml")
        createErrorFile(errorDir1, "error2.xml")
        fileMonitoringService.checkFileStatus()

        status = fileMonitoringService.monitoringStatus
        assertEquals(2, status.errorFileCount)

        errorDir1.resolve("error1.xml").toFile().delete()
        fileMonitoringService.checkFileStatus()

        status = fileMonitoringService.monitoringStatus
        assertEquals(1, status.errorFileCount)

        errorDir1.resolve("error2.xml").toFile().delete()
        fileMonitoringService.checkFileStatus()

        status = fileMonitoringService.monitoringStatus
        assertEquals(0, status.errorFileCount)
    }

    @Test
    fun `should handle files at exactly 2 hour threshold`() = runTest {
        val justOverTwoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS).minus(1, ChronoUnit.SECONDS)
        val justUnderTwoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS).plus(1, ChronoUnit.SECONDS)

        createTempFile(tempDir, "over.$TEMP_FILE_EXTENSION", justOverTwoHoursAgo)
        createTempFile(tempDir, "under.$TEMP_FILE_EXTENSION", justUnderTwoHoursAgo)

        fileMonitoringService.checkFileStatus()

        val status = fileMonitoringService.monitoringStatus
        assertEquals(1, status.oldTempFileCount)
    }

    @Test
    fun `should handle multiple connectors with different error directories`() = runTest {
        val connector1 = Connector(
            connectorId = ConnectorId("connector1"),
            targetFolder = targetDir1,
            sourceFolder = sourceDir1,
            contentType = MediaType.APPLICATION_XML.toString(),
            mode = CdrClientConfig.Mode.TEST,
            sourceErrorFolder = errorDir1,
        )
        val connector2 = Connector(
            connectorId = ConnectorId("connector2"),
            targetFolder = targetDir2,
            sourceFolder = sourceDir2,
            contentType = MediaType.APPLICATION_XML.toString(),
            mode = CdrClientConfig.Mode.PRODUCTION,
            sourceErrorFolder = errorDir2,
        )
        val config = createConfigWith(connector1, connector2)
        val service = FileMonitoringService(config)

        createErrorFile(errorDir1, "error1.xml")
        createErrorFile(errorDir1, "error2.xml")
        createErrorFile(errorDir2, "error3.xml")

        service.checkFileStatus()

        val status = service.monitoringStatus
        assertEquals(3, status.errorFileCount)
    }

    private fun createErrorFile(errorDir: Path, fileName: String) {
        errorDir.resolve(fileName).createFile().writeText("test error content")
    }

    private fun createTempFile(tempDir: Path, fileName: String, lastModified: Instant) {
        val file = tempDir.resolve(fileName).createFile()
        file.writeText("test temp content")
        Files.setLastModifiedTime(file, FileTime.from(lastModified))
    }

    private fun createDefaultConfig(): CdrClientConfig {
        val connector1 = Connector(
            connectorId = ConnectorId("connector1"),
            targetFolder = targetDir1,
            sourceFolder = sourceDir1,
            contentType = MediaType.APPLICATION_XML.toString(),
            mode = CdrClientConfig.Mode.TEST,
            sourceErrorFolder = errorDir1,
        )
        val connector2 = Connector(
            connectorId = ConnectorId("connector2"),
            targetFolder = targetDir2,
            sourceFolder = sourceDir2,
            contentType = MediaType.APPLICATION_XML.toString(),
            mode = CdrClientConfig.Mode.PRODUCTION,
            sourceErrorFolder = errorDir2,
        )
        return createConfigWith(connector1, connector2)
    }

    private fun createConfigWith(vararg connectors: Connector): CdrClientConfig =
        CdrClientConfig(
            fileSynchronizationEnabled = FileSynchronization.ENABLED,
            customer = Customer(connectors.toMutableList()),
            cdrApi = CdrApi(
                scheme = "http",
                host = Host("localhost"),
                port = 80,
                basePath = "/"
            ),
            filesInProgressCacheSize = DataSize.ofMegabytes(1L),
            idpCredentials = IdpCredentials(
                tenantId = TenantId("test-tenant"),
                clientId = ClientId("test-client"),
                clientSecret = ClientSecret("test-secret"),
                scope = Scope("test-scope"),
                renewCredential = RenewCredential.DISABLED,
                maxCredentialAge = Duration.ofDays(365),
                lastCredentialRenewalTime = BEGINNING_OF_TIME,
            ),
            idpEndpoint = URI("http://localhost").toURL(),
            localFolder = TempDownloadDir(tempDir),
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
            fileBusyTestStrategy = FileBusyTestStrategyProperty(CdrClientConfig.FileBusyTestStrategy.NEVER_BUSY),
            proxyConfig = ProxyConfig(
                url = ProxyUrl(""),
                username = ProxyUsername(""),
                password = ProxyPassword(""),
            ),
            oldFileThreshold = Duration.ofHours(2L),
            fileSystemCheckInterval = Duration.ofMinutes(5L),
        )
}
