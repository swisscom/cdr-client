package com.swisscom.health.des.cdr.clientvm

import com.swisscom.health.des.cdr.clientvm.config.CdrClientConfig
import com.swisscom.health.des.cdr.clientvm.handler.CONNECTOR_ID_HEADER
import com.swisscom.health.des.cdr.clientvm.handler.PULL_RESULT_ID_HEADER
import com.swisscom.health.des.cdr.clientvm.handler.PullFileHandling
import com.swisscom.health.des.cdr.clientvm.handler.PushFileHandling
import com.swisscom.health.des.cdr.clientvm.scheduling.Scheduler
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

@ExtendWith(MockKExtension::class)
internal class PullSchedulerAndFileHandlerMultipleConnectorTest {

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
    private lateinit var pushFileHandling: PushFileHandling

    @TempDir
    private lateinit var folder: File

    private lateinit var cdrServiceMock: MockWebServer

    private lateinit var scheduler: Scheduler
    private lateinit var pullFileHandling: PullFileHandling

    private var counterOne = 0
    private var counterTwo = 0

    private val directory1 = "customer"
    private val directory2 = "otherOne"
    private val inflightFolder = "inflight"
    private val connectorId1 = "1234"
    private val connectorId2 = "3456"

    @BeforeEach
    fun setup() {
        folder.mkdirs()
        cdrServiceMock = MockWebServer()
        cdrServiceMock.start()

        val endpoint = CdrClientConfig.Endpoint(
            host = cdrServiceMock.hostName,
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )
        val connector1 =
            CdrClientConfig.Connector(
                connectorId = connectorId1,
                targetFolder = "${folder.absolutePath}${File.separator}$directory1",
                sourceFolder = "${folder.absolutePath}${File.separator}$directory1/source",
                contentType = "application/forumdatenaustausch+xml;charset=UTF-8;version=4.5",
                mode = CdrClientConfig.Mode.TEST
            )
        val connector2 =
            CdrClientConfig.Connector(
                connectorId = connectorId2,
                targetFolder = "${folder.absolutePath}${File.separator}$directory2",
                sourceFolder = "${folder.absolutePath}${File.separator}$directory2/source",
                contentType = "application/forumdatenaustausch+xml;charset=UTF-8;version=4.5",
                mode = CdrClientConfig.Mode.PRODUCTION
            )
        File("${folder.absolutePath}${File.separator}$directory1").mkdirs()
        File("${folder.absolutePath}${File.separator}$directory2").mkdirs()
        File("${folder.absolutePath}${File.separator}$inflightFolder").mkdirs()
        every { config.customer } returns listOf(connector1, connector2)
        every { config.endpoint } returns endpoint
        every { config.localFolder } returns "${folder.absolutePath}${File.separator}$inflightFolder"
        every { config.functionKey } returns "1"
        mockTracer()

        pullFileHandling = PullFileHandling(config, OkHttpClient.Builder().build(), tracer)
        scheduler = Scheduler(
            config,
            pullFileHandling,
            pushFileHandling,
        )

    }

    private fun mockTracer() {
        every { tracer.spanBuilder() } returns spanBuilder
        every { tracer.currentSpan() } returns null
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
    }

    private fun handleDispatcher(request: RecordedRequest, practOneMaxCount: Int, practTwoMaxCount: Int): MockResponse {
        return if (request.method == "GET" && request.headers[CONNECTOR_ID_HEADER] == connectorId1
        ) {
            mockResponseDependingOnPath(request) { handleConnectorOne(practOneMaxCount) }
        } else if (request.method == "GET" && request.headers[CONNECTOR_ID_HEADER] == connectorId2
        ) {
            mockResponseDependingOnPath(request) { handleConnectorTwo(practTwoMaxCount) }
        } else if (request.method == "DELETE") {
            MockResponse().setResponseCode(HttpStatus.OK.value())
        } else {
            MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .setBody("I'm sorry. My responses are limited. You must ask the right questions.")
        }
    }

    private fun mockResponseDependingOnPath(request: RecordedRequest, handleMethod: () -> MockResponse): MockResponse {
        return with(request.path!!) {
            when {
                contains("limit=1") -> handleMethod()
                else -> MockResponse().setResponseCode(HttpStatus.NOT_FOUND.value())
            }
        }
    }

    private fun handleConnectorOne(maxCount: Int): MockResponse {
        return if (counterOne < maxCount) {
            counterOne++
            MockResponse().setResponseCode(HttpStatus.OK.value())
                .setHeader(PULL_RESULT_ID_HEADER, UUID.randomUUID().toString())
                .setBody(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
        } else {
            MockResponse().setResponseCode(HttpStatus.NO_CONTENT.value())
        }
    }

    private fun handleConnectorTwo(maxCount: Int): MockResponse {
        return if (counterTwo < maxCount) {
            counterTwo++
            MockResponse().setResponseCode(HttpStatus.OK.value())
                .setHeader(PULL_RESULT_ID_HEADER, UUID.randomUUID().toString())
                .setBody(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
        } else {
            MockResponse().setResponseCode(HttpStatus.NO_CONTENT.value())
        }
    }

    @AfterEach
    fun tearDown() {
        folder.delete()
        cdrServiceMock.shutdown()
        counterOne = 0
        counterTwo = 0
    }

    @Test
    fun `test sync of multiple files to folder for two connectors`() = runTest {
        val practOneMaxCount = 75
        val practTwoMaxCount = 50
        cdrServiceMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return handleDispatcher(request, practOneMaxCount, practTwoMaxCount)
            }
        }

        scheduler.syncFilesToClientFolders()


        Assertions.assertEquals(practTwoMaxCount * 2 + practOneMaxCount * 2 + 2, cdrServiceMock.requestCount)

        val listFiles = folder.listFiles()
        Assertions.assertNotNull(listFiles)
        listFiles!!
        listFiles.let { Assertions.assertEquals(3, it.size) }
        listFiles.filter { !it.endsWith(inflightFolder) }.forEach { Assertions.assertTrue(it.list().size > 5) }
        listFiles.filter { it.endsWith(directory1) }[0].list().let {
            if (it != null) {
                Assertions.assertEquals(counterOne, it.size)
            } else {
                Assertions.fail("No file list for folder $directory1 found")
            }
        }
        listFiles.filter { it.endsWith(directory2) }[0].list().let {
            if (it != null) {
                Assertions.assertEquals(counterTwo, it.size)
            } else {
                Assertions.fail("No file list for folder $directory2 found")
            }
        }
        listFiles.filter { it.endsWith(inflightFolder) }[0].list().let {
            if (it != null) {
                Assertions.assertEquals(0, it.size)
            } else {
                Assertions.fail("No file list for folder $inflightFolder found")
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `test sync of multiple files to folder for one connector`() = runTest {
        val practOneMaxCount = 10
        val practTwoMaxCount = 0
        cdrServiceMock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return handleDispatcher(request, practOneMaxCount, practTwoMaxCount)
            }
        }

        scheduler.syncFilesToClientFolders()
        advanceUntilIdle()

        Assertions.assertEquals(practTwoMaxCount * 2 + practOneMaxCount * 2 + 2, cdrServiceMock.requestCount)
        val listFiles = folder.listFiles()
        Assertions.assertNotNull(listFiles)
        listFiles!!
        listFiles.let { Assertions.assertEquals(3, it.size) }
        listFiles.filter { it.endsWith(directory1) }[0].list().let {
            if (it != null) {
                Assertions.assertEquals(counterOne, it.size)
            } else {
                Assertions.fail("No file list for folder $directory1 found")
            }
        }
        listFiles.filter { it.endsWith(directory2) }[0].list().let {
            if (it != null) {
                Assertions.assertEquals(counterTwo, it.size)
            } else {
                Assertions.fail("No file list for folder $directory2 found")
            }
        }
        listFiles.filter { it.endsWith(inflightFolder) }[0].list().let {
            if (it != null) {
                Assertions.assertEquals(0, it.size)
            } else {
                Assertions.fail("No file list for folder $inflightFolder found")
            }
        }
    }

}
