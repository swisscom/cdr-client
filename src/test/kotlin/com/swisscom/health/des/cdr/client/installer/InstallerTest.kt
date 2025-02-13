package com.swisscom.health.des.cdr.client.installer

import com.swisscom.health.des.cdr.client.getInstallDir
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Scanner
import kotlin.io.path.exists

@ExtendWith(MockKExtension::class)
class InstallerTest {

    private lateinit var installer: Installer

    @MockK
    private lateinit var scanner: Scanner

    private lateinit var createTempDirectory: Path

    @BeforeEach
    fun setUp() {
        installer = Installer(scanner)
        createTempDirectory = Files.createTempDirectory("createAppCustomer")
    }

    @AfterEach
    fun tearDown() {
        if (createTempDirectory.exists()) {
            createTempDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test install method`() {
        mockkStatic("com.swisscom.health.des.cdr.client.CdrClientApplicationKt")
        println("createTempDirectory: $createTempDirectory, ${createTempDirectory.toUri().rawPath}")
        every { getInstallDir() } returns createTempDirectory.toString()
        every { scanner.nextLine() } returns "tenantId" andThen "clientId" andThen "clientSecret" andThen "connectorId" andThen "n"

        installer.install()

        val configFile = File(createTempDirectory.toString() + File.separator + Installer.CONFIG_FILE)
        assertTrue(configFile.exists(), "Config file should exist")
        val configContent = configFile.readText()
        assertTrue(configContent.contains("tenant-id=tenantId"), "Config file should contain tenant-id")
        assertTrue(configContent.contains("client-id=clientId"), "Config file should contain client-id")
        assertTrue(configContent.contains("client-secret=clientSecret"), "Config file should contain client-secret")
        assertTrue(configContent.contains("client.customer[0].connector-id=connectorId"), "Config file should contain customer[0].connector-id")
        assertTrue(configContent.contains("client.customer[1].connector-id=connectorId"), "Config file should contain customer[1].connector-id")
    }
}
