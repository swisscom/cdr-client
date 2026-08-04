package com.swisscom.health.des.cdr.client.scheduling

import com.swisscom.health.des.cdr.client.common.Constants.RESTART_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.common.Constants.UPLOAD_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.ConnectorId
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.handler.SchedulingValidationService
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

@ExtendWith(MockKExtension::class)
internal class UploadStartupPreparationTest {

    @MockK
    private lateinit var config: CdrClientConfig

    @MockK
    private lateinit var schedulingValidationService: SchedulingValidationService

    @TempDir
    private lateinit var tmpDir: Path

    private lateinit var sourceDir: Path

    @BeforeEach
    fun setup() {
        sourceDir = tmpDir.resolve("source").also { it.createDirectories() }
        val connector = Connector(
            connectorId = ConnectorId("1234"),
            targetFolder = tmpDir.resolve("target").also { it.createDirectories() },
            sourceFolder = sourceDir,
            contentType = "application/forumdatenaustausch+xml;charset=UTF-8",
            mode = CdrClientConfig.Mode.TEST,
        )
        every { config.customer } returns Customer(mutableListOf(connector))
    }

    @Test
    fun `renames restart files when scheduling is allowed`() {
        every { schedulingValidationService.isSchedulingAllowed } returns true
        val restartFile = sourceDir.resolve("document.$RESTART_FILE_EXTENSION").also { it.writeText("payload") }

        UploadStartupPreparation(config, schedulingValidationService, 0.0).prepareUploadStartupState()

        val xmlFile = sourceDir.resolve("document.xml")
        assertFalse(restartFile.exists())
        assertTrue(xmlFile.exists())
        assertEquals("payload", xmlFile.toFile().readText())
    }

    @Test
    fun `leaves restart files untouched when scheduling is not allowed`() {
        every { schedulingValidationService.isSchedulingAllowed } returns false
        val restartFile = sourceDir.resolve("document.$RESTART_FILE_EXTENSION").also { it.writeText("payload") }

        UploadStartupPreparation(config, schedulingValidationService, 0.0).prepareUploadStartupState()

        assertTrue(restartFile.exists())
        assertFalse(sourceDir.resolve("document.xml").exists())
    }

    @Test
    fun `leaves upload files untouched`() {
        every { schedulingValidationService.isSchedulingAllowed } returns true
        val uploadFile = sourceDir.resolve("document.$UPLOAD_FILE_EXTENSION").also { it.writeText("payload") }

        UploadStartupPreparation(config, schedulingValidationService, 0.0).prepareUploadStartupState()

        assertTrue(uploadFile.exists())
        assertEquals("payload", uploadFile.toFile().readText())

        val regularFiles = sourceDir.listDirectoryEntries().filter { it.isRegularFile() }
        assertEquals(1, regularFiles.size)
        assertEquals(uploadFile, regularFiles.single())
        assertEquals(UPLOAD_FILE_EXTENSION, regularFiles.single().extension)
    }

    @Test
    fun `fails when telemetry sampling is enabled`() {
        val startupPreparation = UploadStartupPreparation(config, schedulingValidationService, 0.1)

        assertThrows<IllegalStateException> {
            startupPreparation.prepareUploadStartupState()
        }
    }
}
