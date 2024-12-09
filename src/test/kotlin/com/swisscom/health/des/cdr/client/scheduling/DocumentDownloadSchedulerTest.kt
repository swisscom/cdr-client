package com.swisscom.health.des.cdr.client.scheduling

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.handler.PullFileHandling
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import java.nio.file.Path
import kotlin.io.path.createDirectories

@ExtendWith(MockKExtension::class)
internal class DocumentDownloadSchedulerTest {

    @TempDir
    private lateinit var tmpDir: Path

    @MockK
    private lateinit var config: CdrClientConfig

    @MockK
    private lateinit var tracer: Tracer

    @MockK
    private lateinit var spanBuilder: Span.Builder

    @MockK
    private lateinit var span: Span

    @MockK
    private lateinit var spanInScope: Tracer.SpanInScope

    @MockK
    private lateinit var traceContext: TraceContext

    @MockK
    private lateinit var pullFileHandling: PullFileHandling

    private val inflightFolder = "inflight"
    private val targetDirectory = "customer"
    private val sourceDirectory = "source"

    @BeforeEach
    fun setup() {
        val inflightDir = tmpDir.resolve(inflightFolder).also { it.createDirectories() }
        val targetDir = tmpDir.resolve(targetDirectory).also { it.createDirectories() }
        val sourceDir = tmpDir.resolve(sourceDirectory).also { it.createDirectories() }

        val connector =
            CdrClientConfig.Connector(
                connectorId = "1234",
                targetFolder = targetDir,
                sourceFolder = sourceDir,
                contentType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8"),
                mode = CdrClientConfig.Mode.TEST
            )
        every { config.customer } returns listOf(connector)
        every { config.localFolder } returns inflightDir
        mockTracer()
    }

    private fun mockTracer() {
        every { tracer.spanBuilder() } returns spanBuilder
        every { spanBuilder.setNoParent() } returns spanBuilder
        every { spanBuilder.name(any()) } returns spanBuilder
        every { spanBuilder.start() } returns span
        every { tracer.withSpan(any()) } returns spanInScope
        every { span.name(any()) } returns span
        every { span.start() } returns span
        every { span.event(any()) } returns span
        every { span.end() } returns Unit
        every { span.tag(any(), any<String>()) } returns span
        every { span.context() } returns traceContext
        every { spanInScope.close() } returns Unit
        every { traceContext.traceId() } returns "1"
    }

    @Test
    fun `test sync pull of single file to folder`() = runTest {
        coEvery { pullFileHandling.pullSyncConnector(any()) } returns Unit

        val documentDownloadScheduler = DocumentDownloadScheduler(
            cdrClientConfig = config,
            pullFileHandling = pullFileHandling,
            cdrDownloadsDispatcher = Dispatchers.IO,
        )

        documentDownloadScheduler.syncFilesToClientFolders()

        coVerify(exactly = 1) { pullFileHandling.pullSyncConnector(any()) }
    }

    @Test
    // Test for coverage only, as there is only a log output in the error case
    fun `test sync push of single file to folder throws exception`() = runTest {
        coEvery { pullFileHandling.pullSyncConnector(any()) } throws IllegalArgumentException("Exception")

        val documentDownloadScheduler = DocumentDownloadScheduler(
            cdrClientConfig = config,
            pullFileHandling = pullFileHandling,
            cdrDownloadsDispatcher = Dispatchers.IO,
        )

        documentDownloadScheduler.syncFilesToClientFolders()

        coVerify(exactly = 1) { pullFileHandling.pullSyncConnector(any()) }
    }

}
