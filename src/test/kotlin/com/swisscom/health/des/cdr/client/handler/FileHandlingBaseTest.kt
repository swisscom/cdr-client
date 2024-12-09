package com.swisscom.health.des.cdr.client.handler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.deleteIfExists

class FileHandlingBaseTest {

    @Test
    fun `test non writable folder`() {
        val createTempDirectory = Files.createTempDirectory("nonWritableTest")
        Assertions.assertTrue(pathIsDirectoryAndWritable(createTempDirectory, "test", KotlinLogging.logger {}))
        createTempDirectory.toFile().setWritable(false)
        Assertions.assertFalse(pathIsDirectoryAndWritable(createTempDirectory, "test", KotlinLogging.logger {}))
        createTempDirectory.toFile().setWritable(true)
        Assertions.assertTrue(createTempDirectory.deleteIfExists())
    }

    @Test
    fun `test non directory`() {
        val createTempFile = Files.createTempFile("nonDirectoryTest", "test")
        Assertions.assertFalse(pathIsDirectoryAndWritable(createTempFile, "test", KotlinLogging.logger {}))
        Assertions.assertTrue(createTempFile.deleteIfExists())
    }
}
