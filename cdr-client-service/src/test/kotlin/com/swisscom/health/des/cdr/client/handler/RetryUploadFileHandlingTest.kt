package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.common.Constants.UPLOAD_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.ConnectorId
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.UploadDocumentResult
import com.swisscom.health.des.cdr.client.xml.CommunicationType
import com.swisscom.health.des.cdr.client.xml.DocumentMetaData
import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.junit5.MockKExtension.CheckUnnecessaryStub
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking

@ExtendWith(MockKExtension::class)
@CheckUnnecessaryStub
internal class RetryUploadFileHandlingTest {

    @MockK
    private lateinit var cdrClientConfig: CdrClientConfig

    @MockK
    private lateinit var tracer: Tracer

    @MockK
    private lateinit var cdrApiClient: CdrApiClient

    @TempDir
    private lateinit var tempDir: Path

    private lateinit var connector: Connector
    private lateinit var documentMetaData: DocumentMetaData
    private lateinit var retryUploadFileHandling: RetryUploadFileHandling

    @BeforeEach
    fun setup() {
        connector = Connector(
            connectorId = ConnectorId("2345"),
            targetFolder = tempDir,
            sourceFolder = tempDir,
            contentType = "application/forumdatenaustausch+xml;charset=UTF-8",
            mode = CdrClientConfig.Mode.TEST,
        )
        documentMetaData = DocumentMetaData(documentType = DocumentType.UNKNOWN, communicationType = CommunicationType.UNKNOWN)

        every { cdrClientConfig.pushThreadPoolSize } returns 1
        every { cdrClientConfig.retryDelay } returns listOf(Duration.ZERO)
        every { cdrClientConfig.customer } returns Customer(mutableListOf(connector))
        every { tracer.currentSpan() } returns null

        retryUploadFileHandling = RetryUploadFileHandling(
            cdrClientConfig = cdrClientConfig,
            tracer = tracer,
            cdrApiClient = cdrApiClient,
        )
    }

    @Test
    fun `when default upload target already exists the upload filename is randomized`() = runBlocking {
        val sourceFile = tempDir.resolve("document.xml")
        val existingUploadFile = tempDir.resolve("document.$UPLOAD_FILE_EXTENSION")
        sourceFile.writeText("content")
        existingUploadFile.writeText("already-there")

        val uploadedFile = slot<Path>()
        every {
            cdrApiClient.uploadDocument(
                contentType = any(),
                file = capture(uploadedFile),
                connectorId = any(),
                mode = any(),
                traceId = any(),
            )
        } returns UploadDocumentResult.Success

        retryUploadFileHandling.uploadRetrying(
            file = sourceFile,
            docMeta = documentMetaData,
            connector = connector
        )

        verify(exactly = 1) { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) }
        assertTrue(uploadedFile.isCaptured)
        assertEquals(tempDir, uploadedFile.captured.parent)
        assertEquals("document.$UPLOAD_FILE_EXTENSION", existingUploadFile.fileName.toString())
        assertTrue(existingUploadFile.toFile().exists())
        assertFalse(sourceFile.toFile().exists())
        assertNotEquals(existingUploadFile.fileName.toString(), uploadedFile.captured.fileName.toString())
        assertTrue(uploadedFile.captured.fileName.toString().matches(Regex("document_[0-9a-fA-F-]{36}\\.$UPLOAD_FILE_EXTENSION")))
    }
}
