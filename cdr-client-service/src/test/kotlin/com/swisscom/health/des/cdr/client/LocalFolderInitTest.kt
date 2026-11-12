package com.swisscom.health.des.cdr.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class LocalFolderInitTest {

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `blank local folder in default template is set to inbound next to the config file`() {
        val configFile = tempDir.resolve("application-customer.yaml")
        Files.copy(Path.of("conf/default-application-customer.yaml"), configFile)

        LocalFolderInit.initLocalFolder(configFile, stateDirectory = null)

        val expected = tempDir.resolve(LocalFolderInit.INBOUND_DIR_NAME).toAbsolutePath()
        assertTrue(expected.isDirectory())
        assertEquals(expected.toString(), configFile.property("client.local-folder"))
        assertEquals("1.3", configFile.property("version"))
        assertEquals("70b434db-eccb-4280-95dc-1e59220aca55", configFile.property("client.idp-credentials.tenant-id"))
    }

    @Test
    fun `null local folder is set as well`() {
        val configFile = tempDir.resolve("application-customer.yaml").apply { writeText("client:\n  local-folder:\n") }

        LocalFolderInit.initLocalFolder(configFile, stateDirectory = null)

        assertEquals(tempDir.resolve(LocalFolderInit.INBOUND_DIR_NAME).toAbsolutePath().toString(), configFile.property("client.local-folder"))
    }

    @Test
    fun `configured local folder is left untouched`() {
        val content = "client:\n  local-folder: /some/where # comment\n"
        val configFile = tempDir.resolve("application-customer.yaml").apply { writeText(content) }

        LocalFolderInit.initLocalFolder(configFile, stateDirectory = null)

        assertEquals(content, configFile.readText())
        assertFalse(tempDir.resolve(LocalFolderInit.INBOUND_DIR_NAME).isDirectory())
    }

    @Test
    fun `inbound is placed next to a conf directory`() {
        val confDir = tempDir.resolve("conf").createDirectory()
        val configFile = confDir.resolve("application-customer.yaml").apply { writeText("client:\n  local-folder: \"\"\n") }

        LocalFolderInit.initLocalFolder(configFile, stateDirectory = null)

        val expected = tempDir.resolve(LocalFolderInit.INBOUND_DIR_NAME).toAbsolutePath()
        assertTrue(expected.isDirectory())
        assertFalse(confDir.resolve(LocalFolderInit.INBOUND_DIR_NAME).isDirectory())
        assertEquals(expected.toString(), configFile.property("client.local-folder"))
    }

    @Test
    fun `systemd state directory takes precedence`() {
        val stateDir = tempDir.resolve("state").createDirectory()
        val configFile = tempDir.resolve("application-customer.yaml").apply { writeText("client:\n  local-folder: \"\"\n") }

        LocalFolderInit.initLocalFolder(configFile, stateDirectory = "$stateDir${File.pathSeparator}${tempDir.resolve("other")}")

        val expected = stateDir.resolve(LocalFolderInit.INBOUND_DIR_NAME).toAbsolutePath()
        assertTrue(expected.isDirectory())
        assertEquals(expected.toString(), configFile.property("client.local-folder"))
    }

    @Test
    fun `path is persisted even if the folder cannot be created`() {
        // a regular file in place of the base directory makes directory creation fail
        val blockingFile = tempDir.resolve("state").createFile()
        val configFile = tempDir.resolve("application-customer.yaml").apply { writeText("client:\n  local-folder: \"\"\n") }

        LocalFolderInit.initLocalFolder(configFile, stateDirectory = blockingFile.toString())

        val expected = blockingFile.resolve(LocalFolderInit.INBOUND_DIR_NAME).toAbsolutePath()
        assertFalse(expected.isDirectory())
        assertEquals(expected.toString(), configFile.property("client.local-folder"))
    }

    @Test
    fun `local folder stays blank if the config file cannot be written`() {
        val content = "client:\n  local-folder: \"\"\n"
        val configFile = tempDir.resolve("application-customer.yaml").apply { writeText(content) }
        assertTrue(configFile.toFile().setWritable(false))

        try {
            LocalFolderInit.initLocalFolder(configFile, stateDirectory = null)

            assertEquals(content, configFile.readText())
        } finally {
            configFile.toFile().setWritable(true)
        }
    }

    @Test
    fun `missing client section or config file is ignored`() {
        val configFile = tempDir.resolve("application-customer.yaml").apply { writeText("version: \"1.3\"\n") }

        LocalFolderInit.initLocalFolder(configFile, stateDirectory = null)
        LocalFolderInit.initLocalFolder(tempDir.resolve("missing.yaml"), stateDirectory = null)

        assertEquals("version: \"1.3\"\n", configFile.readText())
        assertFalse(tempDir.resolve("missing.yaml").exists())
        assertFalse(tempDir.resolve(LocalFolderInit.INBOUND_DIR_NAME).exists())
    }

    private fun Path.property(name: String): Any? =
        YamlPropertySourceLoader().load("customer", FileSystemResource(this)).single().getProperty(name)?.toString()
}
