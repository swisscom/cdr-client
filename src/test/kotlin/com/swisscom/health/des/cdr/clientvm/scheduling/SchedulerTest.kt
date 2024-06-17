package com.swisscom.health.des.cdr.clientvm.scheduling

import com.swisscom.health.des.cdr.clientvm.config.CdrClientConfig
import com.swisscom.health.des.cdr.clientvm.handler.PullFileHandling
import com.swisscom.health.des.cdr.clientvm.handler.PushFileHandling
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File

@ExtendWith(MockKExtension::class)
internal class SchedulerTest {

    @TempDir
    private lateinit var folder: File

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

    @MockK
    private lateinit var pushFileHandling: PushFileHandling

    private val inflightFolder = "inflight"
    private val targetDirectory = "customer"
    private val sourceDirectory = "source"
    private lateinit var scheduler: Scheduler

    @BeforeEach
    fun setup() {
        folder.mkdirs()
        File("${folder.absolutePath}${File.separator}$targetDirectory").mkdirs()
        File("${folder.absolutePath}${File.separator}$inflightFolder").mkdirs()
        File("${folder.absolutePath}${File.separator}$sourceDirectory").mkdirs()
        val connector =
            CdrClientConfig.Connector(
                connectorId = "1234",
                targetFolder = "${folder.absolutePath}${File.separator}$targetDirectory",
                sourceFolder = "${folder.absolutePath}${File.separator}$sourceDirectory",
                contentType = "application/forumdatenaustausch+xml;charset=UTF-8;version=4.5",
                mode = CdrClientConfig.Mode.TEST
            )
        every { config.customer } returns listOf(connector)
        every { config.localFolder } returns "${folder.absolutePath}${File.separator}$inflightFolder"
        mockTracer()

        scheduler = Scheduler(
            config,
            pullFileHandling,
            pushFileHandling,
        )
    }

    @AfterEach
    fun tearDown() {
        folder.delete()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `test sync pull of single file to folder`() = runTest {
        coEvery { pullFileHandling.pullSyncConnector(any()) } returns Unit

        scheduler.syncFilesToClientFolders()

        coVerify(exactly = 1) { pullFileHandling.pullSyncConnector(any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    // Test for coverage only, as there is only a log output in the error case
    fun `test sync push of single file to folder throws exception`() = runTest {
        coEvery { pullFileHandling.pullSyncConnector(any()) } throws IllegalArgumentException("Exception")

        scheduler.syncFilesToClientFolders()

        coVerify(exactly = 1) { pullFileHandling.pullSyncConnector(any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `test sync push of single file to folder`() = runTest {
        coEvery { pushFileHandling.pushSyncConnector(any()) } returns Unit

        scheduler.syncFilesToApi()

        coVerify(exactly = 1) { pushFileHandling.pushSyncConnector(any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    // Test for coverage only, as there is only a log output in the error case
    fun `test sync pull of single file to folder throws exception`() = runTest {
        coEvery { pushFileHandling.pushSyncConnector(any()) } throws IllegalArgumentException("Exception")

        scheduler.syncFilesToApi()

        coVerify(exactly = 1) { pushFileHandling.pushSyncConnector(any()) }
    }

}
