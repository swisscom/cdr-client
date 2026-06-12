package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.common.Constants.ARCHIVE_DIR_NAME
import com.swisscom.health.des.cdr.client.common.Constants.ERROR_DIR_NAME
import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.config.CdrClientConfig.Mode
import com.swisscom.health.des.cdr.client.xml.CommunicationType
import com.swisscom.health.des.cdr.client.xml.DocumentMetaData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import java.nio.file.Path

class ConnectorDirectoriesTest {

    private val forumDatenaustauschMediaType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")
    private val docMetaNotification = DocumentMetaData(DocumentType.NOTIFICATION, CommunicationType.UNKNOWN)
    private val docMetaInvoice = DocumentMetaData(DocumentType.INVOICE, CommunicationType.UNKNOWN)

    @TempDir
    private lateinit var tmpDir: Path

    //
    // relative paths setup
    //
    private lateinit var targetDirectory: String
    private lateinit var sourceDirectory: String
    private lateinit var baseSourceDir: Path
    private lateinit var baseTargetDir: Path
    private lateinit var relativeInvoiceSourceDir: Path
    private lateinit var relativeInvoiceTargetDir: Path
    private lateinit var relativeErrorDir: Path
    private lateinit var relativeArchiveDir: Path
    private lateinit var allRelativePathsConnector: Connector

    //
    // absolute paths setup
    //
    private lateinit var absTargetDirectory: String
    private lateinit var absSourceDirectory: String
    private lateinit var absBaseSourceDir: Path
    private lateinit var absBaseTargetDir: Path
    private lateinit var absErrorDir: Path
    private lateinit var absArchiveDir: Path
    private lateinit var absInvoiceSourceDir: Path
    private lateinit var absInvoiceTargetDir: Path
    private lateinit var absInvoiceErrorDir: Path
    private lateinit var absInvoiceArchiveDir: Path
    private lateinit var allAbsolutePathsConnector: Connector

    //
    // request/response split
    //
    private lateinit var absMcdRequestSourceDir: Path
    private lateinit var absMcdRequestTargetDir: Path
    private lateinit var absMcdResponseSourceDir: Path
    private lateinit var absMcdResponseTargetDir: Path

    private lateinit var absMcdErrorDir: Path
    private lateinit var absMcdArchiveDir: Path
    private lateinit var requestResponseSplitConnector: Connector

    @Suppress("LongMethod")
    @BeforeEach
    fun setup() {
        // all relative paths
        targetDirectory = "target"
        sourceDirectory = "source"
        baseSourceDir = tmpDir.resolve(sourceDirectory)
        baseTargetDir = tmpDir.resolve(targetDirectory)
        relativeInvoiceSourceDir = Path.of("invoice_src")
        relativeInvoiceTargetDir = Path.of("invoice_src")


        allRelativePathsConnector = Connector(
            connectorId = ConnectorId("1"),
            targetFolder = baseTargetDir,
            sourceFolder = baseSourceDir,
            contentType = forumDatenaustauschMediaType.toString(),
            sourceArchiveEnabled = true,
            sourceArchiveFolder = null,
            sourceErrorFolder = null,
            mode = Mode.TEST,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to Connector.DocTypeFolders(
                    sourceFolder = relativeInvoiceSourceDir,
                    targetFolder = relativeInvoiceTargetDir,
                    errorFolder = null,
                    archiveFolder = null,
                )
            )
        )

        // absolute paths setup
        absTargetDirectory = "abs_target"
        absSourceDirectory = "abs_source"
        absBaseSourceDir = tmpDir.resolve(absSourceDirectory)
        absBaseTargetDir = tmpDir.resolve(absTargetDirectory)
        absErrorDir = tmpDir.resolve("abs_error")
        absArchiveDir = tmpDir.resolve("abs_archive")
        absInvoiceSourceDir = tmpDir.resolve("abs_invoice_src")
        absInvoiceTargetDir = tmpDir.resolve("abs_invoice_tgt")
        absInvoiceErrorDir = tmpDir.resolve("abs_invoice_error")
        absInvoiceArchiveDir = tmpDir.resolve("abs_invoice_archive")

        allAbsolutePathsConnector = Connector(
            connectorId = ConnectorId("2"),
            targetFolder = absBaseTargetDir,
            sourceFolder = absBaseSourceDir,
            contentType = forumDatenaustauschMediaType.toString(),
            sourceArchiveEnabled = true,
            sourceArchiveFolder = absArchiveDir,
            sourceErrorFolder = absErrorDir,
            mode = Mode.TEST,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to Connector.DocTypeFolders(
                    sourceFolder = absInvoiceSourceDir,
                    targetFolder = absInvoiceTargetDir,
                    errorFolder = absInvoiceErrorDir,
                    archiveFolder = absInvoiceArchiveDir,
                )
            )
        )

        // request/response split
        relativeErrorDir = Path.of("custom_error_dir")
        relativeArchiveDir = Path.of("custom_archive_dir")
        absMcdRequestSourceDir = tmpDir.resolve("mcd_request_src")
        absMcdRequestTargetDir = tmpDir.resolve("mcd_request_tgt")
        absMcdResponseSourceDir = tmpDir.resolve("mcd_response_src")
        absMcdResponseTargetDir = tmpDir.resolve("mcd_response_tgt")
        absMcdErrorDir = tmpDir.resolve("mcd_error")
        absMcdArchiveDir = tmpDir.resolve("mcd_archive_dir")

        requestResponseSplitConnector = Connector(
            connectorId = ConnectorId("3"),
            targetFolder = baseTargetDir,
            sourceFolder = baseSourceDir,
            contentType = forumDatenaustauschMediaType.toString(),
            sourceArchiveEnabled = true,
            sourceArchiveFolder = relativeArchiveDir,
            sourceErrorFolder = relativeErrorDir,
            mode = Mode.PRODUCTION,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to Connector.DocTypeFolders(
                    sourceFolder = absInvoiceSourceDir,
                    targetFolder = absInvoiceTargetDir,
                    errorFolder = relativeErrorDir,
                    archiveFolder = relativeArchiveDir,
                ),
                DocumentType.HOSPITAL_MCD to Connector.DocTypeFolders(
                    requestResponseSplit = true, // requires all dirs to be absolute
                    sourceFolder = relativeInvoiceSourceDir, // don't care
                    sourceFolderReq = absMcdRequestSourceDir,
                    sourceFolderResp = absMcdResponseSourceDir,
                    targetFolder = relativeInvoiceTargetDir, // don't care
                    targetFolderResp = absMcdResponseTargetDir,
                    targetFolderReq = absMcdRequestTargetDir,
                    archiveFolder = absMcdArchiveDir,
                    errorFolder = absMcdErrorDir,
                )
            ),
        )
    }

    @Test
    @Suppress("LongMethod")
    fun `test all relative paths`() {
        var relativePathsConnector = allRelativePathsConnector

        val expectedBaseSourceDir = tmpDir.resolve(sourceDirectory)
        val expectedBaseTargetDir = tmpDir.resolve(targetDirectory)
        val expectedErrorDir = expectedBaseSourceDir.resolve(ERROR_DIR_NAME)
        val expectedArchiveDir = expectedBaseSourceDir.resolve(ARCHIVE_DIR_NAME)
        val expectedInvoiceSourceDir = expectedBaseSourceDir.resolve("invoice_src")
        val expectedInvoiceTargetDir = expectedBaseTargetDir.resolve("invoice_src")
        val expectedInvoiceErrorDir = expectedInvoiceSourceDir.resolve(ERROR_DIR_NAME)
        val expectedInvoiceArchiveDir = expectedInvoiceSourceDir.resolve(ARCHIVE_DIR_NAME)

        assertEquals(expectedBaseSourceDir, relativePathsConnector.getEffectiveSourceFolder(docMetaNotification))
        assertEquals(expectedBaseTargetDir, relativePathsConnector.getEffectiveTargetFolder(docMetaNotification))
        assertEquals(expectedErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(docMetaNotification))
        assertEquals(expectedArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(docMetaNotification))
        assertEquals(expectedInvoiceSourceDir, relativePathsConnector.getEffectiveSourceFolder(docMetaInvoice))
        assertEquals(expectedInvoiceTargetDir, relativePathsConnector.getEffectiveTargetFolder(docMetaInvoice))
        assertEquals(expectedInvoiceErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(docMetaInvoice))
        assertEquals(expectedInvoiceArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))


        //
        // Test `archive` directory precedence rules
        //

        // unset document type specific archive directory
        relativePathsConnector = allRelativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to allRelativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(archiveFolder = null)
            )
        )

        // if archive directory is null for the document type, it should default to `archive` resolved against the document type specific source directory
        assertEquals(expectedInvoiceArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))

        relativePathsConnector = relativePathsConnector.copy(sourceArchiveFolder = null)

        // if no archive directory is defined at all, it should still default to `archive` resolved against the document type specific source directory
        assertEquals(expectedInvoiceArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))

        // test interaction with document type specific source directory

        // unset document type specific source directory
        relativePathsConnector = allRelativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to allRelativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(sourceFolder = null)
            )
        )

        // if document type specific source directory is unset, then the document type specific archive directory should be resolved against
        // the global source directory
        assertEquals(expectedArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))

        // unset document type specific archive directory
        relativePathsConnector = relativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to relativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(archiveFolder = null)
            )
        )

        // if both document type specific archive and source directories are unset, then the global archive directory should be returned
        assertEquals(expectedArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))

        // test on/off switch

        relativePathsConnector = allRelativePathsConnector.copy(sourceArchiveEnabled = false)

        assertNull(relativePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))
        assertNull(relativePathsConnector.getEffectiveSourceArchiveFolder(docMetaNotification))

        //
        // Test `error` directory precedence rules
        //

        relativePathsConnector = allRelativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to allRelativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(errorFolder = null)
            )
        )

        // if error directory is null for the document type, it should default to `error` resolved against the document type specific source directory
        assertEquals(expectedInvoiceErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(docMetaInvoice))

        relativePathsConnector = relativePathsConnector.copy(sourceErrorFolder = null)

        // if no error directory is defined at all, it should still default to `error` resolved against the document type specific source directory
        assertEquals(expectedInvoiceErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(docMetaInvoice))

        // test interaction with document type specific source directory

        // unset document type specific source directory
        relativePathsConnector = allRelativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to allRelativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(sourceFolder = null)
            )
        )

        // if document type specific source directory is unset, then the document type specific error directory should be resolved
        // against the global source directory
        assertEquals(expectedErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(docMetaInvoice))

        // unset document type specific error directory
        relativePathsConnector = relativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to relativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(errorFolder = null)
            )
        )

        // if both document type specific error and source directories are unset, then the global error directory should be returned
        assertEquals(expectedErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(docMetaInvoice))
    }

    @Test
    fun `test all absolute paths`() {
        assertEquals(absBaseSourceDir, allAbsolutePathsConnector.getEffectiveSourceFolder(docMetaNotification))
        assertEquals(absBaseTargetDir, allAbsolutePathsConnector.getEffectiveTargetFolder(docMetaNotification))
        assertEquals(absErrorDir, allAbsolutePathsConnector.getEffectiveSourceErrorFolder(docMetaNotification))
        assertEquals(absArchiveDir, allAbsolutePathsConnector.getEffectiveSourceArchiveFolder(docMetaNotification))
        assertEquals(absInvoiceSourceDir, allAbsolutePathsConnector.getEffectiveSourceFolder(docMetaInvoice))
        assertEquals(absInvoiceTargetDir, allAbsolutePathsConnector.getEffectiveTargetFolder(docMetaInvoice))
        assertEquals(absInvoiceErrorDir, allAbsolutePathsConnector.getEffectiveSourceErrorFolder(docMetaInvoice))
        assertEquals(absInvoiceArchiveDir, allAbsolutePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))
    }

    @Test
    fun `test archive toggles with relative paths`() {
        val expectedBaseSourceDir = tmpDir.resolve(sourceDirectory)
        val expectedInvoiceSourceDir = expectedBaseSourceDir.resolve("invoice_src")
        val expectedInvoiceArchiveDir = expectedInvoiceSourceDir.resolve(ARCHIVE_DIR_NAME)

        var archiveTogglesRelativePathsConnector = allRelativePathsConnector

        assertEquals(expectedInvoiceArchiveDir, archiveTogglesRelativePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))

        // disabling archiving should result in no archive directory
        archiveTogglesRelativePathsConnector = archiveTogglesRelativePathsConnector.copy(sourceArchiveEnabled = false)

        assertNull(archiveTogglesRelativePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))
    }

    @Test
    fun `test archive toggles with absolute paths`() {
        var archiveTogglesAbsolutePathsConnector = allAbsolutePathsConnector

        assertEquals(absInvoiceArchiveDir, archiveTogglesAbsolutePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))

        // disabling archiving all together should result in no archive directory
        archiveTogglesAbsolutePathsConnector = archiveTogglesAbsolutePathsConnector.copy(sourceArchiveEnabled = false)

        assertNull(archiveTogglesAbsolutePathsConnector.getEffectiveSourceArchiveFolder(docMetaInvoice))
    }

    @Test
    fun `test global archive toggle is applied globally`() {
        val archiveTogglesConnector = allRelativePathsConnector.copy(sourceArchiveEnabled = false)
        val archiveTogglesAbsolutePathsConnector = allAbsolutePathsConnector.copy(sourceArchiveEnabled = false)
        val archiveTogglesRequestResponseSplitConnector = requestResponseSplitConnector.copy(sourceArchiveEnabled = false)

        assertTrue(archiveTogglesConnector.effectiveArchiveFolders.values.flatten().isEmpty())
        assertTrue(archiveTogglesAbsolutePathsConnector.effectiveArchiveFolders.values.flatten().isEmpty())
        assertTrue(archiveTogglesRequestResponseSplitConnector.effectiveArchiveFolders.values.flatten().isEmpty())
    }

    @Test
    fun `test get all source folders()`() {
        val requestResponseSplitSourceFolders: Map<DocumentType, List<Path>> = requestResponseSplitConnector.effectiveSourceFolders

        // the connector specifies source dirs for INVOICE and MCD document types, all other document types should use the global/default source dir
        assertEquals(listOf(absInvoiceSourceDir), requestResponseSplitSourceFolders[DocumentType.INVOICE])

        assertEquals(2, requestResponseSplitSourceFolders[DocumentType.HOSPITAL_MCD]?.size)
        assertEquals(listOf(absMcdRequestSourceDir, absMcdResponseSourceDir), requestResponseSplitSourceFolders[DocumentType.HOSPITAL_MCD])

        DocumentType.entries
            .filterNot { it == DocumentType.INVOICE || it == DocumentType.HOSPITAL_MCD }
            .forEach { docType ->
                assertEquals(listOf(baseSourceDir), requestResponseSplitSourceFolders[docType])
            }
    }

    @Test
    fun `test get all target folders()`() {
        val requestResponseSplitTargetFolders: Map<DocumentType, List<Path>> = requestResponseSplitConnector.effectiveTargetFolders

        // the connector specifies target dirs for INVOICE and MCD document types, all other document types should use the global/default target dir
        assertEquals(listOf(absInvoiceTargetDir), requestResponseSplitTargetFolders[DocumentType.INVOICE])

        assertEquals(2, requestResponseSplitTargetFolders[DocumentType.HOSPITAL_MCD]?.size)
        assertEquals(listOf(absMcdRequestTargetDir, absMcdResponseTargetDir), requestResponseSplitTargetFolders[DocumentType.HOSPITAL_MCD])

        DocumentType.entries
            .filterNot { it == DocumentType.INVOICE || it == DocumentType.HOSPITAL_MCD }
            .forEach { docType ->
                assertEquals(listOf(baseTargetDir), requestResponseSplitTargetFolders[docType])
            }
    }

    @Test
    fun `test get all archive folders`() {
        val requestResponseSplitArchiveFolders: Map<DocumentType, List<Path>> = requestResponseSplitConnector.effectiveArchiveFolders

        // the connector specifies archive dirs for INVOICE and MCD document types, all other document types should use the global/default archive dir
        val defaultArchiveDir = listOf(baseSourceDir.resolve(relativeArchiveDir))
        val invoiceArchiveDir = listOf(absInvoiceSourceDir.resolve(relativeArchiveDir))
        val mcdArchiveDirs = CommunicationType.entries.map { absMcdArchiveDir.resolve(it.name) }

        assertEquals(invoiceArchiveDir, requestResponseSplitArchiveFolders[DocumentType.INVOICE])
        assertEquals(mcdArchiveDirs, requestResponseSplitArchiveFolders[DocumentType.HOSPITAL_MCD])

        DocumentType.entries
            .filterNot { it == DocumentType.INVOICE || it == DocumentType.HOSPITAL_MCD }
            .forEach { docType ->
                assertEquals(defaultArchiveDir, requestResponseSplitArchiveFolders[docType])
            }
    }

    @Test
    fun `test get all error folders`() {
        val requestResponseSplitErrorFolders: Map<DocumentType, List<Path>> = requestResponseSplitConnector.effectiveErrorFolders

        // the connector specifies archive dirs for INVOICE and MCD document types, all other document types should use the global/default archive dir
        val defaultErrorDir = listOf(baseSourceDir.resolve(relativeErrorDir))
        val invoiceErrorDir = listOf(absInvoiceSourceDir.resolve(relativeErrorDir))
        val mcdErrorDirs = CommunicationType.entries.map { absMcdErrorDir.resolve(it.name) }

        assertEquals(invoiceErrorDir, requestResponseSplitErrorFolders[DocumentType.INVOICE])
        assertEquals(mcdErrorDirs, requestResponseSplitErrorFolders[DocumentType.HOSPITAL_MCD])

        DocumentType.entries
            .filterNot { it == DocumentType.INVOICE || it == DocumentType.HOSPITAL_MCD }
            .forEach { docType ->
                assertEquals(defaultErrorDir, requestResponseSplitErrorFolders[docType])
            }
    }

}
