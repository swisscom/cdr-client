package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.LogCorrelation
import com.swisscom.health.des.cdr.client.common.Constants.RESTART_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.common.Constants.UPLOAD_FILE_EXTENSION
import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.ConnectorId
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.config.getEffectiveSourceErrorFolder
import com.swisscom.health.des.cdr.client.handler.CdrApiClient.UploadDocumentResult
import com.swisscom.health.des.cdr.client.model.UploadOutcome
import com.swisscom.health.des.cdr.client.xml.CommunicationType
import com.swisscom.health.des.cdr.client.xml.DocumentMetaData
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.junit5.MockKExtension.CheckUnnecessaryStub
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

@ExtendWith(MockKExtension::class)
@CheckUnnecessaryStub
internal class RetryUploadFileHandlingTest {

    @MockK
    private lateinit var cdrClientConfig: CdrClientConfig

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
        retryUploadFileHandling = RetryUploadFileHandling(
            cdrClientConfig = cdrClientConfig,
            cdrApiClient = cdrApiClient,
        )
    }

    @Test
    fun `when default upload target already exists the upload filename is randomized`() = runBlocking {
        every { cdrClientConfig.customer } returns Customer(mutableListOf(connector))
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

        val outcome = retryUploadFileHandling.uploadRetrying(
            file = sourceFile,
            docMeta = documentMetaData,
            connector = connector
        )

        assertEquals(UploadOutcome.Uploaded, outcome)
        verify(exactly = 1) { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) }
        assertTrue(uploadedFile.isCaptured)
        assertEquals(tempDir, uploadedFile.captured.parent)
        assertEquals("document.$UPLOAD_FILE_EXTENSION", existingUploadFile.fileName.toString())
        assertTrue(existingUploadFile.toFile().exists())
        assertFalse(sourceFile.toFile().exists())
        assertNotEquals(existingUploadFile.fileName.toString(), uploadedFile.captured.fileName.toString())
        assertTrue(uploadedFile.captured.fileName.toString().matches(Regex("document_[0-9a-fA-F-]{36}\\.$UPLOAD_FILE_EXTENSION")))
    }

    @Test
    fun `rejected upload returns status after moving file and writing response`() = runBlocking {
        every { cdrClientConfig.customer } returns Customer(mutableListOf(connector))
        val sourceFile = tempDir.resolve("document.xml").apply { writeText("content") }
        val errorFolder = connector.getEffectiveSourceErrorFolder(documentMetaData).createDirectories()
        every { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) } returns
                UploadDocumentResult.UploadClientErrorResponse(422, "invalid document")

        val outcome = retryUploadFileHandling.uploadRetrying(sourceFile, documentMetaData, connector)

        assertEquals(UploadOutcome.Rejected(422), outcome)
        assertEquals("content", errorFolder.listDirectoryEntries("*.xml").single().readText())
        assertEquals("invalid document", errorFolder.listDirectoryEntries("*.response").single().readText())
        assertFalse(sourceFile.exists())
    }

    @Test
    fun `configuration error returns status after renaming for restart`() = runBlocking {
        val sourceFile = tempDir.resolve("document.xml").apply { writeText("content") }
        every { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) } returns
                UploadDocumentResult.UploadClientConfigNonRetryableErrorResponse(403, "configuration error")

        val outcome = retryUploadFileHandling.uploadRetrying(sourceFile, documentMetaData, connector)

        assertEquals(UploadOutcome.DeferredUntilRestart(403), outcome)
        assertEquals("content", tempDir.resolve("document.$RESTART_FILE_EXTENSION").readText())
        assertFalse(sourceFile.exists())
    }

    @Test
    fun `transient errors return only the eventual upload outcome`() = runBlocking {
        every { cdrClientConfig.customer } returns Customer(mutableListOf(connector))
        val sourceFile = tempDir.resolve("document.xml").apply { writeText("content") }
        every { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) } returnsMany listOf(
            UploadDocumentResult.UploadServerErrorResponse(503, "unavailable"),
            UploadDocumentResult.UploadClientRetryableErrorResponse(429, "busy"),
            UploadDocumentResult.UploadError("connection lost"),
            UploadDocumentResult.Success,
        )

        assertEquals(UploadOutcome.Uploaded, retryUploadFileHandling.uploadRetrying(sourceFile, documentMetaData, connector))
        verify(exactly = 4) { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `failure moving rejected file propagates instead of returning rejected`() = runBlocking {
        every { cdrClientConfig.customer } returns Customer(mutableListOf(connector))
        val sourceFile = tempDir.resolve("document.xml").apply { writeText("content") }
        every { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) } returns
                UploadDocumentResult.UploadClientErrorResponse(422, "invalid document")

        assertThrows<IOException> {
            retryUploadFileHandling.uploadRetrying(sourceFile, documentMetaData, connector)
        }
        assertTrue(tempDir.resolve("document.$UPLOAD_FILE_EXTENSION").exists())
    }

    @Test
    fun `failure renaming for restart propagates instead of returning deferred`() = runBlocking {
        val sourceFile = tempDir.resolve("document.xml").apply { writeText("content") }
        val restartFile = tempDir.resolve("document.$RESTART_FILE_EXTENSION").apply { writeText("existing") }
        every { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) } returns
                UploadDocumentResult.UploadClientConfigNonRetryableErrorResponse(403, "configuration error")

        assertThrows<IOException> {
            retryUploadFileHandling.uploadRetrying(sourceFile, documentMetaData, connector)
        }
        assertEquals("existing", restartFile.readText())
    }

    @Test
    fun `successful upload remains uploaded if archive cleanup fails`() = runBlocking {
        val archiveFolder = tempDir.resolve("not-a-directory").apply { writeText("blocked") }
        connector = connector.copy(sourceArchiveEnabled = true, sourceArchiveFolder = archiveFolder)
        every { cdrClientConfig.customer } returns Customer(mutableListOf(connector))
        val sourceFile = tempDir.resolve("document.xml").apply { writeText("content") }
        every { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) } returns UploadDocumentResult.Success

        assertEquals(UploadOutcome.Uploaded, retryUploadFileHandling.uploadRetrying(sourceFile, documentMetaData, connector))
        assertTrue(tempDir.resolve("document.$UPLOAD_FILE_EXTENSION").exists())
    }

    @Test
    fun `cancellation propagates and restores source file`() = runBlocking {
        val sourceFile = tempDir.resolve("document.xml").apply { writeText("content") }
        every { cdrApiClient.uploadDocument(any(), any(), any(), any(), any()) } throws CancellationException("shutdown")

        assertThrows<CancellationException> {
            retryUploadFileHandling.uploadRetrying(sourceFile, documentMetaData, connector)
        }
        assertTrue(sourceFile.exists())
        assertFalse(tempDir.resolve("document.$UPLOAD_FILE_EXTENSION").exists())
    }

    @Test
    fun `upload uses current correlation trace id for outbound call`() = runBlocking {
        every { cdrClientConfig.customer } returns Customer(mutableListOf(connector))
        val sourceFile = tempDir.resolve("document.xml")
        sourceFile.writeText("content")

        val traceId = LogCorrelation.withNewTraceIdSuspending {
            val capturedTraceId = slot<String>()
            every {
                cdrApiClient.uploadDocument(
                    contentType = any(),
                    file = any(),
                    connectorId = any(),
                    mode = any(),
                    traceId = capture(capturedTraceId),
                )
            } returns UploadDocumentResult.Success

            retryUploadFileHandling.uploadRetrying(
                file = sourceFile,
                docMeta = documentMetaData,
                connector = connector
            )

            capturedTraceId.captured
        }

        assertNotNull(traceId)
        assertTrue(traceId.isNotBlank())
    }
}
