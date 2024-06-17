package com.swisscom.health.des.cdr.clientvm.handler

import com.swisscom.health.des.cdr.clientvm.config.CdrClientConfig
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

@ExtendWith(MockKExtension::class)
internal class PushFileHandlingTest {
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

    @TempDir
    private lateinit var folder: File

    private lateinit var cdrServiceMock: MockWebServer

    private lateinit var pushFileHandling: PushFileHandling
    private val inflightFolder = "inflight"
    private val targetDirectory = "customer"
    private val sourceDirectory = "source"
    private lateinit var endpoint: CdrClientConfig.Endpoint

    @BeforeEach
    fun setup() {
        folder.mkdirs()
        cdrServiceMock = MockWebServer()
        cdrServiceMock.start()
        mockTracer()

        endpoint = CdrClientConfig.Endpoint(
            host = cdrServiceMock.hostName,
            basePath = "documents",
            scheme = "http",
            port = cdrServiceMock.port,
        )

        File("${folder.absolutePath}${File.separator}$sourceDirectory").mkdirs()
        File("${folder.absolutePath}${File.separator}$inflightFolder").mkdirs()
        every { config.endpoint } returns endpoint
        every { config.localFolder } returns "${folder.absolutePath}${File.separator}$inflightFolder"
        every { config.functionKey } returns "1"
        val duration = Duration.ofMillis(100)
        every { config.retryDelay } returns arrayOf(duration, duration, duration)

        pushFileHandling = PushFileHandling(config, OkHttpClient.Builder().build(), tracer)
    }

    @AfterEach
    fun tearDown() {
        folder.delete()
        cdrServiceMock.shutdown()
    }

    @Test
    fun `test successfully write two files to API`() {
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy.xml").use { it.write("Hello".toByteArray()) }
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy-2.xml").use { it.write("Hello 2".toByteArray()) }

        val mockResponse = MockResponse().setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .setResponseCode(HttpStatus.OK.value())
            .setBody("{\"message\": \"Upload successful\"}")
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)

        runBlocking {
            pushFileHandling.pushSyncConnector(createConnector("2345"))
        }

        assertEquals(2, cdrServiceMock.requestCount)
        assertFolder(0)
    }

    @Test
    fun `test ignore non xml files`() {
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy.txt").use { it.write("Hello".toByteArray()) }
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy-2.error").use { it.write("Hello 2".toByteArray()) }
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy-2.log").use { it.write("Hello 2".toByteArray()) }

        runBlocking {
            pushFileHandling.pushSyncConnector(createConnector("2345"))
        }

        assertEquals(0, cdrServiceMock.requestCount)
        assertFolder(3)
    }

    @Test
    fun `test successfully write two files to API fail with third`() {
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy.xml").use { it.write("Hello".toByteArray()) }
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy-2.xml").use { it.write("Hello 2".toByteArray()) }
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy-3.xml").use { it.write("Hello 3".toByteArray()) }

        val mockResponse = MockResponse().setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .setBody("{\"message\": \"Upload successful\"}")
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()).setBody("{\"message\": \"Exception\"}"))
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()).setBody("{\"message\": \"Exception\"}"))
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()).setBody("{\"message\": \"Exception\"}"))
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.BAD_REQUEST.value()).setBody("{\"message\": \"Exception\"}"))

        runBlocking {
            pushFileHandling.pushSyncConnector(createConnector("2345"))
        }

        assertEquals(6, cdrServiceMock.requestCount)
        assertFolder(2)
    }

    @Test
    fun `test successfully write two files to API fail with third do not retry`() {
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy.xml").use { it.write("Hello".toByteArray()) }
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy-2.xml").use { it.write("Hello 2".toByteArray()) }
        FileOutputStream("${folder.absolutePath}${File.separator}$sourceDirectory/dummy-3.xml").use { it.write("Hello 3".toByteArray()) }

        val mockResponse = MockResponse().setResponseCode(HttpStatus.OK.value())
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .setBody("{\"message\": \"Upload successful\"}")
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(mockResponse)
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.BAD_REQUEST.value()).setBody("{\"message\": \"Exception\"}"))

        runBlocking {
            pushFileHandling.pushSyncConnector(createConnector("2345"))
        }

        assertEquals(3, cdrServiceMock.requestCount)
        assertFolder(2)
    }

    private fun createConnector(
        connectorId: String,
        targetFolder: String = "${folder.absolutePath}${File.separator}$targetDirectory",
        sourceFolder: String = "${folder.absolutePath}${File.separator}$sourceDirectory"
    ): CdrClientConfig.Connector =
        CdrClientConfig.Connector(
            connectorId = connectorId,
            targetFolder = targetFolder,
            sourceFolder = sourceFolder,
            contentType = "application/forumdatenaustausch+xml;charset=UTF-8;version=4.5",
            mode = CdrClientConfig.Mode.TEST
        )

    private fun assertFolder(expectedSize: Int) {
        val listFiles = folder.listFiles()!!
        listFiles.filter { it.endsWith(sourceDirectory) }[0].list()?.let { assertEquals(expectedSize, it.size) }
            ?: fail("No file list for folder $sourceDirectory found")
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
}
