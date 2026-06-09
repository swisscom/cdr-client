package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.common.Constants.ARCHIVE_DIR_NAME
import com.swisscom.health.des.cdr.client.common.Constants.ERROR_DIR_NAME
import com.swisscom.health.des.cdr.client.xml.DocumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import java.nio.file.Path

class ConnectorDirectoriesTest {

    private val forumDatenaustauschMediaType = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")

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

    @BeforeEach
    fun setup() {
        // all relative paths
        targetDirectory = "target"
        sourceDirectory = "source"
        baseSourceDir = tmpDir.resolve(sourceDirectory)
        baseTargetDir = tmpDir.resolve(targetDirectory)
        relativeInvoiceSourceDir = Path.of("invoice_src")
        relativeInvoiceTargetDir = Path.of("invoice_src")
        relativeErrorDir = Path.of(ERROR_DIR_NAME)
        relativeArchiveDir = Path.of(ARCHIVE_DIR_NAME)

        allRelativePathsConnector = Connector(
            connectorId = ConnectorId("1"),
            targetFolder = baseTargetDir,
            sourceFolder = baseSourceDir,
            contentType = forumDatenaustauschMediaType.toString(),
            mode = CdrClientConfig.Mode.TEST,
            sourceArchiveEnabled = true,
            sourceErrorFolder = relativeErrorDir,
            sourceArchiveFolder = relativeArchiveDir,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to Connector.DocTypeFolders(
                    sourceFolder = relativeInvoiceSourceDir,
                    targetFolder = relativeInvoiceTargetDir,
                    errorFolder = relativeErrorDir,
                    archiveFolder = relativeArchiveDir,
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
            mode = CdrClientConfig.Mode.TEST,
            sourceArchiveEnabled = true,
            sourceErrorFolder = absErrorDir,
            sourceArchiveFolder = absArchiveDir,
            docTypeFolders = mapOf(
                DocumentType.INVOICE to Connector.DocTypeFolders(
                    sourceFolder = absInvoiceSourceDir,
                    targetFolder = absInvoiceTargetDir,
                    errorFolder = absInvoiceErrorDir,
                    archiveFolder = absInvoiceArchiveDir,
                )
            )
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

        assertEquals(expectedBaseSourceDir, relativePathsConnector.getEffectiveSourceFolder(DocumentType.NOTIFICATION))
        assertEquals(expectedBaseTargetDir, relativePathsConnector.getEffectiveTargetFolder(DocumentType.NOTIFICATION))
        assertEquals(expectedErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(DocumentType.NOTIFICATION))
        assertEquals(expectedArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.NOTIFICATION))
        assertEquals(expectedInvoiceSourceDir, relativePathsConnector.getEffectiveSourceFolder(DocumentType.INVOICE))
        assertEquals(expectedInvoiceTargetDir, relativePathsConnector.getEffectiveTargetFolder(DocumentType.INVOICE))
        assertEquals(expectedInvoiceErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(DocumentType.INVOICE))
        assertEquals(expectedInvoiceArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))


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
        assertEquals(expectedInvoiceArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))

        relativePathsConnector = relativePathsConnector.copy(sourceArchiveFolder = null)

        // if no archive directory is defined at all, it should still default to `archive` resolved against the document type specific source directory
        assertEquals(expectedInvoiceArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))

        // test interaction with document type specific source directory

        // unset document type specific source directory
        relativePathsConnector = allRelativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to allRelativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(sourceFolder = null)
            )
        )

        // if document type specific source directory is unset, then the document type specific archive directory should be resolved against
        // the global source directory
        assertEquals(expectedArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))

        // unset document type specific archive directory
        relativePathsConnector = relativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to relativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(archiveFolder = null)
            )
        )

        // if both document type specific archive and source directories are unset, then the global archive directory should be returned
        assertEquals(expectedArchiveDir, relativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))

        // test on/off switch

        relativePathsConnector = allRelativePathsConnector.copy(sourceArchiveEnabled = false)

        assertNull(relativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))
        assertNull(relativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.NOTIFICATION))

        //
        // Test `error` directory precedence rules
        //

        relativePathsConnector = allRelativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to allRelativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(errorFolder = null)
            )
        )

        // if error directory is null for the document type, it should default to `error` resolved against the document type specific source directory
        assertEquals(expectedInvoiceErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(DocumentType.INVOICE))

        relativePathsConnector = relativePathsConnector.copy(sourceErrorFolder = null)

        // if no error directory is defined at all, it should still default to `error` resolved against the document type specific source directory
        assertEquals(expectedInvoiceErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(DocumentType.INVOICE))

        // test interaction with document type specific source directory

        // unset document type specific source directory
        relativePathsConnector = allRelativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to allRelativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(sourceFolder = null)
            )
        )

        // if document type specific source directory is unset, then the document type specific error directory should be resolved
        // against the global source directory
        assertEquals(expectedErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(DocumentType.INVOICE))

        // unset document type specific error directory
        relativePathsConnector = relativePathsConnector.copy(
            docTypeFolders = mapOf(
                DocumentType.INVOICE to relativePathsConnector.effectiveDocTypeFolders[DocumentType.INVOICE]!!.copy(errorFolder = null)
            )
        )

        // if both document type specific error and source directories are unset, then the global error directory should be returned
        assertEquals(expectedErrorDir, relativePathsConnector.getEffectiveSourceErrorFolder(DocumentType.INVOICE))
    }

    @Test
    fun `test all absolute paths`() {
        assertEquals(absBaseSourceDir, allAbsolutePathsConnector.getEffectiveSourceFolder(DocumentType.NOTIFICATION))
        assertEquals(absBaseTargetDir, allAbsolutePathsConnector.getEffectiveTargetFolder(DocumentType.NOTIFICATION))
        assertEquals(absErrorDir, allAbsolutePathsConnector.getEffectiveSourceErrorFolder(DocumentType.NOTIFICATION))
        assertEquals(absArchiveDir, allAbsolutePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.NOTIFICATION))
        assertEquals(absInvoiceSourceDir, allAbsolutePathsConnector.getEffectiveSourceFolder(DocumentType.INVOICE))
        assertEquals(absInvoiceTargetDir, allAbsolutePathsConnector.getEffectiveTargetFolder(DocumentType.INVOICE))
        assertEquals(absInvoiceErrorDir, allAbsolutePathsConnector.getEffectiveSourceErrorFolder(DocumentType.INVOICE))
        assertEquals(absInvoiceArchiveDir, allAbsolutePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))
    }

    @Test
    fun `test archive toggles with relative paths`() {
        val expectedBaseSourceDir = tmpDir.resolve(sourceDirectory)
        val expectedInvoiceSourceDir = expectedBaseSourceDir.resolve("invoice_src")
        val expectedInvoiceArchiveDir = expectedInvoiceSourceDir.resolve(ARCHIVE_DIR_NAME)

        var archiveTogglesRelativePathsConnector = allRelativePathsConnector

        assertEquals(expectedInvoiceArchiveDir, archiveTogglesRelativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))

        // disabling archiving should result in no archive directory
        archiveTogglesRelativePathsConnector = archiveTogglesRelativePathsConnector.copy(sourceArchiveEnabled = false)

        assertNull(archiveTogglesRelativePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))
    }

    @Test
    fun `test archive toggles with absolute paths`() {
        var archiveTogglesAbsolutePathsConnector = allAbsolutePathsConnector

        assertEquals(absInvoiceArchiveDir, archiveTogglesAbsolutePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))

        // disabling archiving all together should result in no archive directory
        archiveTogglesAbsolutePathsConnector = archiveTogglesAbsolutePathsConnector.copy(sourceArchiveEnabled = false)

        assertNull(archiveTogglesAbsolutePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))
    }

    @Test
    fun `test global archive toggle overrides per document type archive toggle`() {
        val archiveTogglesConnector = allRelativePathsConnector.copy(sourceArchiveEnabled = false)
        val archiveTogglesAbsolutePathsConnector = allAbsolutePathsConnector.copy(sourceArchiveEnabled = false)

        assertNull(archiveTogglesConnector.getEffectiveSourceArchiveFolder(DocumentType.NOTIFICATION))
        assertNull(archiveTogglesConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))

        assertNull(archiveTogglesAbsolutePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.NOTIFICATION))
        assertNull(archiveTogglesAbsolutePathsConnector.getEffectiveSourceArchiveFolder(DocumentType.INVOICE))
    }

}
