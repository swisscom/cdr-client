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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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
internal class PullFileHandlingTest {
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

    private lateinit var pullFileHandling: PullFileHandling
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

        File("${folder.absolutePath}${File.separator}$targetDirectory").mkdirs()
        File("${folder.absolutePath}${File.separator}$inflightFolder").mkdirs()
        every { config.endpoint } returns endpoint
        every { config.localFolder } returns "${folder.absolutePath}${File.separator}$inflightFolder"
        every { config.functionKey } returns "1"

        pullFileHandling = PullFileHandling(config, OkHttpClient.Builder().build(), tracer)
    }

    @AfterEach
    fun tearDown() {
        folder.delete()
        cdrServiceMock.shutdown()
    }

    @Test
    fun `test sync of single file to folder`() {
        enqueueFileResponseWithReportResponse()
        enqueueEmptyResponse()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }

        assertEquals(3, cdrServiceMock.requestCount, "more requests where done than expected")
        val listFiles = folder.listFiles()
        assertNotNull(listFiles)
        listFiles!!
        assertEquals(2, listFiles.size)

        listFiles.filter { it.endsWith(targetDirectory) }[0].list().let {
            if (it != null) {
                assertEquals(1, it.size)
                assertTrue(it[0].endsWith(".xml"), "File extension is not .xml")
            } else {
                fail("No file list for folder $targetDirectory found")
            }
        }
        listFiles.filter { it.endsWith(inflightFolder) }[0].list().let {
            if (it != null) {
                assertEquals(0, it.size)
            } else {
                fail("A file was found in folder $inflightFolder, which shouldn't be there")
            }
        }
    }

    @Test
    fun `test sync of single file no header present`() {
        enqueueFileResponseNoHeader()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }

        assertEquals(1, cdrServiceMock.requestCount, "more requests where done than expected")
        val listFiles = folder.listFiles()
        assertNotNull(listFiles)
        listFiles!!
        assertEquals(2, listFiles.size)

        listFiles.filter { it.endsWith(targetDirectory) }[0].list().let {
            if (it != null) {
                assertEquals(0, it.size)
            } else {
                fail("A file was found in folder $targetDirectory, which shouldn't be there")
            }
        }
        listFiles.filter { it.endsWith(inflightFolder) }[0].list().let {
            if (it != null) {
                assertEquals(0, it.size)
            } else {
                fail("A file was found in folder $inflightFolder, which shouldn't be there")
            }
        }
    }

    @Test
    fun `test sync of single file to folder with failed report for success`() {
        enqueueFileResponse()
        enqueueExceptionResponse()
        enqueueEmptyResponse()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }

        assertEquals(2, cdrServiceMock.requestCount, "more requests where done than expected")
        val listFiles = folder.listFiles()
        assertNotNull(listFiles)
        listFiles!!
        assertEquals(2, listFiles.size)

        listFiles.filter { it.endsWith(targetDirectory) }[0].list().let {
            if (it != null) {
                assertEquals(0, it.size)
            } else {
                fail("No file list for folder $targetDirectory found")
            }
        }
        listFiles.filter { it.endsWith(inflightFolder) }[0].list().let {
            if (it != null) {
                assertEquals(1, it.size)
            } else {
                fail("No file list for folder $inflightFolder found")
            }
        }
    }

    @Test
    fun `test sync of multiple files to folder`() {
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueEmptyResponse()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }


        assertEquals(11, cdrServiceMock.requestCount, "more requests where done than expected")
        val listFiles = folder.listFiles()
        assertNotNull(listFiles)
        listFiles!!
        assertEquals(2, listFiles.size)

        listFiles.filter { it.endsWith(targetDirectory) }[0].list().let {
            if (it != null) {
                assertEquals(5, it.size)
            } else {
                fail("No file list for folder $targetDirectory found")
            }
        }
        listFiles.filter { it.endsWith(inflightFolder) }[0].list().let {
            if (it != null) {
                assertEquals(0, it.size)
            } else {
                fail("No file list for folder $inflightFolder found")
            }
        }
    }

    @Test
    fun `test sync of multiple files with an exception to folder`() {
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueFileResponseWithReportResponse()
        enqueueExceptionResponse()
        enqueueFileResponseWithReportResponse()
        enqueueEmptyResponse()

        runBlocking {
            pullFileHandling.pullSyncConnector(createConnector("1-2-3-4"))
        }

        assertEquals(7, cdrServiceMock.requestCount, "more requests where done than expected")
        val listFiles = folder.listFiles()
        assertNotNull(listFiles)
        listFiles!!
        assertEquals(2, listFiles.size)

        listFiles.filter { it.endsWith(targetDirectory) }[0].list().let {
            if (it != null) {
                assertEquals(3, it.size)
            } else {
                fail("No file list for folder $targetDirectory found")
            }
        }
        listFiles.filter { it.endsWith(inflightFolder) }[0].list().let {
            if (it != null) {
                assertEquals(0, it.size)
            } else {
                fail("No file list for folder $inflightFolder found")
            }
        }
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
            mode = CdrClientConfig.Mode.PRODUCTION
        )

    private fun enqueueFileResponseWithReportResponse() {
        enqueueFileResponse()
        enqueueReportResponse()
    }

    private fun enqueueFileResponse() {
        val pullRequestId = UUID.randomUUID().toString()
        val mockResponse = MockResponse().setResponseCode(HttpStatus.OK.value())
            .setHeader(PULL_RESULT_ID_HEADER, pullRequestId)
            .setBody(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
        cdrServiceMock.enqueue(mockResponse)
    }

    private fun enqueueFileResponseNoHeader() {
        val mockResponse = MockResponse().setResponseCode(HttpStatus.OK.value())
            .setBody(String(ClassPathResource("messages/dummy.txt").inputStream.readAllBytes(), StandardCharsets.UTF_8))
        cdrServiceMock.enqueue(mockResponse)
    }

    private fun enqueueReportResponse() {
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.OK.value()))
    }

    private fun enqueueEmptyResponse() {
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.NO_CONTENT.value()))
    }

    private fun enqueueExceptionResponse() {
        cdrServiceMock.enqueue(MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()))
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


