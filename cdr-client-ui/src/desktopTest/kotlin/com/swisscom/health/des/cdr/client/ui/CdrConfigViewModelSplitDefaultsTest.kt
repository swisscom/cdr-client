package com.swisscom.health.des.cdr.client.ui

import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig as CdrClientConfigDto
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig.Connector as ConnectorDto
import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CdrConfigViewModelSplitDefaultsTest {

    private val apiClient = mockk<CdrClientApiClient>(relaxed = true)

    @Test
    fun `enabling split derives missing values from current unsplit doc type config`() {
        val connector = ConnectorDto.EMPTY.copy(
            connectorId = "connector-1",
            targetFolder = "/connector/target",
            sourceFolder = "/connector/source",
            sourceArchiveEnabled = true,
            sourceArchiveFolder = "/connector/archive",
            sourceErrorFolder = "/connector/error",
            docTypeFolders = mapOf(
                DocumentType.INVOICE to ConnectorDto.DocTypeFolders(
                    targetFolder = "/invoice/target",
                    sourceFolder = "/invoice/source",
                    archiveFolder = "/invoice/archive",
                    errorFolder = "/invoice/error",
                )
            )
        )

        val updatedConnector = applySplitToggle(connector, DocumentType.INVOICE, doSplit = true)
        val updatedDocTypeFolders = updatedConnector.docTypeFolders.getValue(DocumentType.INVOICE)

        assertTrue(updatedDocTypeFolders.requestResponseSplit)
        assertEquals("/invoice/target", updatedDocTypeFolders.targetFolderReq)
        assertEquals("/invoice/target", updatedDocTypeFolders.targetFolderResp)
        assertEquals("/invoice/source", updatedDocTypeFolders.sourceFolderReq)
        assertEquals("/invoice/source", updatedDocTypeFolders.sourceFolderResp)
        assertEquals("/invoice/error", updatedDocTypeFolders.errorFolder)
        assertEquals("/invoice/archive", updatedDocTypeFolders.archiveFolder)
    }

    @Test
    fun `enabling split falls back to connector level defaults from current config`() {
        val connector = ConnectorDto.EMPTY.copy(
            connectorId = "connector-1",
            targetFolder = "/connector/target",
            sourceFolder = "/connector/source",
            sourceArchiveEnabled = true,
            sourceArchiveFolder = "/connector/archive",
            sourceErrorFolder = "/connector/error",
        )

        val updatedConnector = applySplitToggle(connector, DocumentType.INVOICE, doSplit = true)
        val updatedDocTypeFolders = updatedConnector.docTypeFolders.getValue(DocumentType.INVOICE)

        assertEquals("/connector/target", updatedDocTypeFolders.targetFolderReq)
        assertEquals("/connector/target", updatedDocTypeFolders.targetFolderResp)
        assertEquals("/connector/source", updatedDocTypeFolders.sourceFolderReq)
        assertEquals("/connector/source", updatedDocTypeFolders.sourceFolderResp)
        assertEquals("/connector/error", updatedDocTypeFolders.errorFolder)
        assertEquals("/connector/archive", updatedDocTypeFolders.archiveFolder)
    }

    @Test
    fun `enabling split preserves existing explicit split values and derives only missing ones`() {
        val connector = ConnectorDto.EMPTY.copy(
            connectorId = "connector-1",
            targetFolder = "/connector/target",
            sourceFolder = "/connector/source",
            sourceArchiveEnabled = true,
            sourceArchiveFolder = "/connector/archive",
            sourceErrorFolder = "/connector/error",
            docTypeFolders = mapOf(
                DocumentType.INVOICE to ConnectorDto.DocTypeFolders(
                    requestResponseSplit = false,
                    targetFolder = "/invoice/target",
                    sourceFolder = "/invoice/source",
                    targetFolderReq = "/custom/request/target",
                    sourceFolderResp = "/custom/response/source",
                    errorFolder = "/custom/error",
                )
            )
        )

        val updatedConnector = applySplitToggle(connector, DocumentType.INVOICE, doSplit = true)
        val updatedDocTypeFolders = updatedConnector.docTypeFolders.getValue(DocumentType.INVOICE)

        assertEquals("/custom/request/target", updatedDocTypeFolders.targetFolderReq)
        assertEquals("/invoice/target", updatedDocTypeFolders.targetFolderResp)
        assertEquals("/invoice/source", updatedDocTypeFolders.sourceFolderReq)
        assertEquals("/custom/response/source", updatedDocTypeFolders.sourceFolderResp)
        assertEquals("/custom/error", updatedDocTypeFolders.errorFolder)
        assertEquals("/invoice/source", updatedDocTypeFolders.archiveFolder)
    }

    @Test
    fun `enabling split keeps archive empty when connector archive is disabled`() {
        val connector = ConnectorDto.EMPTY.copy(
            connectorId = "connector-1",
            targetFolder = "/connector/target",
            sourceFolder = "/connector/source",
            sourceArchiveEnabled = false,
            sourceErrorFolder = "/connector/error",
        )

        val updatedConnector = applySplitToggle(connector, DocumentType.INVOICE, doSplit = true)
        val updatedDocTypeFolders = updatedConnector.docTypeFolders.getValue(DocumentType.INVOICE)

        assertEquals(null, updatedDocTypeFolders.archiveFolder)
        assertEquals("/connector/error", updatedDocTypeFolders.errorFolder)
    }

    @Test
    fun `disabling split clears archive and error values`() {
        val connector = ConnectorDto.EMPTY.copy(
            connectorId = "connector-1",
            targetFolder = "/connector/target",
            sourceFolder = "/connector/source",
            sourceArchiveEnabled = true,
            sourceArchiveFolder = "/connector/archive",
            sourceErrorFolder = "/connector/error",
            docTypeFolders = mapOf(
                DocumentType.INVOICE to ConnectorDto.DocTypeFolders(
                    sourceFolder = "/unsplit/source",
                    targetFolder = "/unsplit/target",
                    errorFolder = "/unsplit/error",
                    archiveFolder = "/unsplit/archive",
                )
            )
        )

        val splitConnector = applySplitToggle(connector, DocumentType.INVOICE, doSplit = true)
        val updatedConnector = applySplitToggle(splitConnector, DocumentType.INVOICE, doSplit = false)
        val updatedDocTypeFolders = updatedConnector.docTypeFolders.getValue(DocumentType.INVOICE)

        assertEquals(false, updatedDocTypeFolders.requestResponseSplit)
        assertEquals(null, updatedDocTypeFolders.errorFolder)
        assertEquals(null, updatedDocTypeFolders.archiveFolder)
    }

    @Test
    fun `disabling split clears mixed previously present values`() {
        val connector = ConnectorDto.EMPTY.copy(
            connectorId = "connector-1",
            targetFolder = "/connector/target",
            sourceFolder = "/connector/source",
            sourceArchiveEnabled = true,
            sourceArchiveFolder = "/connector/archive",
            sourceErrorFolder = "/connector/error",
            docTypeFolders = mapOf(
                DocumentType.INVOICE to ConnectorDto.DocTypeFolders(
                    sourceFolder = "/unsplit/source",
                    errorFolder = "/unsplit/error",
                )
            )
        )

        val splitConnector = applySplitToggle(connector, DocumentType.INVOICE, doSplit = true)
        val updatedConnector = applySplitToggle(splitConnector, DocumentType.INVOICE, doSplit = false)
        val updatedDocTypeFolders = updatedConnector.docTypeFolders.getValue(DocumentType.INVOICE)

        assertEquals(null, updatedDocTypeFolders.errorFolder)
        assertEquals(null, updatedDocTypeFolders.archiveFolder)
    }

    @Test
    fun `disabling split after enabling clears explicit error and derived archive`() {
        val connector = ConnectorDto.EMPTY.copy(
            connectorId = "connector-1",
            targetFolder = "/connector/target",
            sourceFolder = "/connector/source",
            sourceArchiveEnabled = true,
            sourceArchiveFolder = "/connector/archive",
            sourceErrorFolder = "/connector/error",
            docTypeFolders = mapOf(
                DocumentType.INVOICE to ConnectorDto.DocTypeFolders(
                    sourceFolder = "/unsplit/source",
                    errorFolder = "/unsplit/error",
                )
            )
        )

        val splitConnector = applySplitToggle(connector, DocumentType.INVOICE, doSplit = true)
        val detoggledConnector = applySplitToggle(splitConnector, DocumentType.INVOICE, doSplit = false)
        val updatedDocTypeFolders = detoggledConnector.docTypeFolders.getValue(DocumentType.INVOICE)

        assertEquals(null, updatedDocTypeFolders.errorFolder)
        assertEquals(null, updatedDocTypeFolders.archiveFolder)
    }

    @Test
    fun `remote config refresh clears local changes after a local edit`() = runBlocking {
        val initialConfig = CdrClientConfigDto.EMPTY.copy(
            localFolder = "/tmp/initial",
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
        )
        val refreshedConfig = initialConfig.copy(localFolder = "/tmp/refreshed")
        val viewModel = CdrConfigViewModel(apiClient)

        coEvery { apiClient.getClientServiceConfiguration() } returnsMany listOf(
            CdrClientApiClient.Result.Success(initialConfig),
            CdrClientApiClient.Result.Success(refreshedConfig),
        )

        viewModel.queryClientServiceConfiguration().join()
        assertEquals(initialConfig, viewModel.uiStateFlow.value.clientServiceConfig)
        assertEquals(false, viewModel.uiStateFlow.value.hasLocalConfigChanges)

        viewModel.setLocalPath("/tmp/local-edit")
        assertEquals("/tmp/local-edit", viewModel.uiStateFlow.value.clientServiceConfig.localFolder)
        assertEquals(true, viewModel.uiStateFlow.value.hasLocalConfigChanges)

        viewModel.queryClientServiceConfiguration().join()
        assertEquals(refreshedConfig, viewModel.uiStateFlow.value.clientServiceConfig)
        assertEquals(false, viewModel.uiStateFlow.value.hasLocalConfigChanges)
    }

    private fun applySplitToggle(connector: ConnectorDto, docType: DocumentType, doSplit: Boolean): ConnectorDto = runBlocking {
        val viewModel = CdrConfigViewModel(apiClient)
        val config = CdrClientConfigDto.EMPTY.copy(customer = listOf(connector))
        coEvery { apiClient.getClientServiceConfiguration() } returns CdrClientApiClient.Result.Success(config)
        viewModel.queryClientServiceConfiguration().join()
        viewModel.setConnectorDocTypeRequestResponseSplit(docType, doSplit, connector)

        viewModel.uiStateFlow.value.clientServiceConfig.customer.single()
    }
}
