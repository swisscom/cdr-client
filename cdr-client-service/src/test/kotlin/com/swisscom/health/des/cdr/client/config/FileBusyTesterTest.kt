package com.swisscom.health.des.cdr.client.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import kotlin.io.path.createFile
import kotlin.io.path.writeText

internal class FileBusyTesterTest {

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `never_busy is always false`() = runTest { assertFalse(FileBusyTester.NeverBusy.isBusy(Path.of("/i/dont/even/exist"))) }

    @Test
    fun `always_busy is always true`() = runTest { assertTrue(FileBusyTester.AlwaysBusy.isBusy(Path.of("/i/dont/even/exist"))) }

    @Test
    fun `file size growth test`() = runBlocking(Dispatchers.IO) {
        val tester = FileBusyTester.FileSizeChanged(testInterval = Duration.ofMillis(50L))
        val file = tempDir
            .resolve("size-changed.txt")
            .createFile()

        val writeFileJob = launch {
            while (true) {
                file.writeText("a", UTF_8, WRITE, APPEND)
                delay(10L)
            }
        }

        assertTrue(tester.isBusy(file))
        delay(100L)
        assertTrue(tester.isBusy(file))
        delay(100L)
        assertTrue(tester.isBusy(file))
        delay(100L)
        assertTrue(tester.isBusy(file))

        writeFileJob.cancelAndJoin()

        assertFalse(tester.isBusy(file))
    }

}
