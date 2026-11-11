package com.swisscom.health.des.cdr.client.scheduling

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mayakapps.kache.ObjectKache
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.ConnectorId
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.config.FileBusyTester
import com.swisscom.health.des.cdr.client.handler.RetryUploadFileHandling
import com.swisscom.health.des.cdr.client.model.UploadOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText

internal class DocumentUploadSchedulerTest {
    @TempDir
    private lateinit var tempDir: Path

    private val config = mockk<CdrClientConfig>()
    private val handler = mockk<RetryUploadFileHandling>()
    private val cache = mockk<ObjectKache<String, Path>>()
    private val busyTester = mockk<FileBusyTester>()
    private lateinit var file: Path

    @BeforeEach
    fun setup() {
        file = tempDir.resolve("document.xml").apply { writeText("<document/>") }
        val connector = Connector(
            connectorId = ConnectorId("2345"),
            targetFolder = tempDir,
            sourceFolder = tempDir,
            contentType = "application/xml",
            mode = CdrClientConfig.Mode.TEST,
        )
        every { config.customer } returns Customer(mutableListOf(connector))
        every { config.fileBusyTestTimeout } returns Duration.ofMillis(10)
        every { config.fileBusyTestInterval } returns Duration.ofMillis(20)
        coEvery { cache.put(any<String>(), any<Path>()) } returns null
        coEvery { cache.remove(any()) } returns file
        coEvery { busyTester.isBusy(any()) } returns false
    }

    @Test
    fun `scheduler logs the precise terminal outcome including HTTP codes`() = runTest {
        val scenarios = listOf(
            UploadOutcome.Uploaded to "uploaded",
            UploadOutcome.Rejected(422) to "rejected (HTTP 422); moved to error directory and response file written",
            UploadOutcome.DeferredUntilRestart(403) to "deferred until restart due to configuration error (HTTP 403)",
        )
        for ((outcome, message) in scenarios) {
            coEvery { handler.uploadRetrying(any(), any(), any()) } returns outcome

            val messages = captureLogs { upload() }

            assertTrue("'$file' $message" in messages)
            assertFalse(messages.any { "uploaded or error handled" in it })
        }
        coVerify(exactly = 3) { cache.remove(file.toString()) }
    }

    @Test
    fun `busy file logs a skip without invoking upload`() = runTest {
        coEvery { busyTester.isBusy(any()) } returns true

        val messages = captureLogs { upload() }

        assertTrue("'$file' not uploaded; file is busy" in messages)
        coVerify(exactly = 0) { handler.uploadRetrying(any(), any(), any()) }
        coVerify(exactly = 1) { cache.remove(file.toString()) }
    }

    @Test
    fun `handling failure logs failure instead of a terminal success outcome`() = runTest {
        coEvery { handler.uploadRetrying(any(), any(), any()) } throws IOException("handling failed")

        val messages = captureLogs { upload() }

        assertTrue("failed to upload '$file': handling failed" in messages)
        assertFalse(messages.any { it == "'$file' uploaded" || "moved to error directory" in it || "deferred until restart" in it })
        coVerify(exactly = 1) { cache.remove(file.toString()) }
    }

    @Test
    fun `cancellation is not logged as upload success or failure`() = runTest {
        coEvery { handler.uploadRetrying(any(), any(), any()) } throws CancellationException("shutdown")

        val messages = captureLogs { upload() }

        assertFalse(messages.any { it == "'$file' uploaded" || "failed to upload" in it })
    }

    private suspend fun upload() {
        val scheduler = object : BaseUploadScheduler(config, handler, Dispatchers.Unconfined, cache, busyTester) {
            suspend fun upload() = uploadFiles(flowOf(file to "test-trace"))
        }
        scheduler.upload()
    }

    private suspend fun captureLogs(action: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("com.swisscom.health.des.cdr.client.scheduling") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            action()
            appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
