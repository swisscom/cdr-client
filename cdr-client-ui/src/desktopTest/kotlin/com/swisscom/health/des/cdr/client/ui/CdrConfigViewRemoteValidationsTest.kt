package com.swisscom.health.des.cdr.client.ui

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig as CdrClientConfigDto
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig.Connector as ConnectorDto
import com.swisscom.health.des.cdr.client.common.DTOs.ValidationMessageKey
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CdrConfigViewRemoteValidationsTest {

    private val apiClient = mockk<CdrClientApiClient>(relaxed = true)
    private val sut = CdrConfigViewRemoteValidations(apiClient)

    @Test
    fun `validateNotBlank returns Success when API returns empty list`() = runBlocking {
        coEvery { apiClient.validateValueIsNotBlank(any()) } returns CdrClientApiClient.Result.Success(emptyList())

        val result = sut.validateNotBlank("value", DomainObjects.ConfigurationItem.IDP_CLIENT_ID)

        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateNotBlank returns Failure when API returns non-empty list`() = runBlocking {
        coEvery { apiClient.validateValueIsNotBlank(any()) } returns CdrClientApiClient.Result.Success(listOf(ValidationMessageKey.VALUE_IS_BLANK))

        val result = sut.validateNotBlank("", DomainObjects.ConfigurationItem.IDP_CLIENT_ID)

        assertEquals(
            DTOs.ValidationResult.Failure(
                listOf(
                    DTOs.ValidationDetail.ConfigItemDetail(
                        DomainObjects.ConfigurationItem.IDP_CLIENT_ID,
                        ValidationMessageKey.VALUE_IS_BLANK
                    )
                )
            ), result
        )
    }

    @Test
    fun `validateDirectory returns Success when API returns Success`() = runBlocking {
        coEvery {
            apiClient.validateDirectory(
                any(),
                any(),
                any()
            )
        } returns CdrClientApiClient.Result.Success<DTOs.ValidationResult>(DTOs.ValidationResult.Success)

        val config = CdrClientConfigDto.EMPTY
        val result = sut.validateDirectory(config, "/tmp/somewhere", DomainObjects.ConfigurationItem.LOCAL_DIRECTORY)

        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateDirectory returns Failure when API returns Failure for matching path`() = runBlocking {
        val path = "/tmp/somewhere"
        val detail = DTOs.ValidationDetail.PathDetail(path = path, messageKey = ValidationMessageKey.DIRECTORY_NOT_FOUND)
        coEvery {
            apiClient.validateDirectory(
                any(),
                any(),
                any()
            )
        } returns CdrClientApiClient.Result.Success<DTOs.ValidationResult>(DTOs.ValidationResult.Failure(listOf(detail)))

        val config = CdrClientConfigDto.EMPTY
        val result = sut.validateDirectory(config, path, DomainObjects.ConfigurationItem.LOCAL_DIRECTORY)

        assertEquals(DTOs.ValidationResult.Failure(listOf(detail)), result)
    }

    @Test
    fun `validateDirectory ignores failures for other paths`() = runBlocking {
        val path = "/tmp/somewhere"
        val otherPath = "/tmp/other"
        val detail = DTOs.ValidationDetail.PathDetail(path = otherPath, messageKey = ValidationMessageKey.DIRECTORY_NOT_FOUND)
        coEvery {
            apiClient.validateDirectory(
                any(),
                any(),
                any()
            )
        } returns CdrClientApiClient.Result.Success<DTOs.ValidationResult>(DTOs.ValidationResult.Failure(listOf(detail)))

        val config = CdrClientConfigDto.EMPTY
        val result = sut.validateDirectory(config, path, DomainObjects.ConfigurationItem.LOCAL_DIRECTORY)

        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateConnectorMode returns Failure when API returns Connector error for same connector`() = runBlocking {
        val connectorId = "my-connector"
        val connectorDetail = DTOs.ValidationDetail.ConnectorDetail(
            connectorId = connectorId,
            configItem = DomainObjects.ConfigurationItem.CONNECTOR_MODE,
            messageKey = ValidationMessageKey.ILLEGAL_MODE
        )
        coEvery {
            apiClient.validateConnectorMode(
                any(),
                any()
            )
        } returns CdrClientApiClient.Result.Success<DTOs.ValidationResult>(DTOs.ValidationResult.Failure(listOf(connectorDetail)))

        val config = CdrClientConfigDto.EMPTY.copy(customer = listOf(ConnectorDto.EMPTY.copy(connectorId = connectorId)))

        val result = sut.validateConnectorMode(connectorId, config, DomainObjects.ConfigurationItem.CONNECTOR_MODE)

        assertEquals(DTOs.ValidationResult.Failure(listOf(connectorDetail)), result)
    }

    @Test
    fun `validateConnectorMode ignores connector errors for other connectors`() = runBlocking {
        val connectorId = "my-connector"
        val otherConnectorId = "other"
        val connectorDetail = DTOs.ValidationDetail.ConnectorDetail(
            connectorId = otherConnectorId,
            configItem = DomainObjects.ConfigurationItem.CONNECTOR_MODE,
            messageKey = ValidationMessageKey.ILLEGAL_MODE
        )
        coEvery {
            apiClient.validateConnectorMode(
                any(),
                any()
            )
        } returns CdrClientApiClient.Result.Success<DTOs.ValidationResult>(DTOs.ValidationResult.Failure(listOf(connectorDetail)))

        val config = CdrClientConfigDto.EMPTY.copy(customer = listOf(ConnectorDto.EMPTY.copy(connectorId = connectorId)))

        val result = sut.validateConnectorMode(connectorId, config, DomainObjects.ConfigurationItem.CONNECTOR_MODE)

        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateProxyUrl returns Success when API returns Success`() = runBlocking {
        coEvery { apiClient.validateProxyUrl(any<String>()) } returns CdrClientApiClient.Result.Success<DTOs.ValidationResult>(DTOs.ValidationResult.Success)

        val config = CdrClientConfigDto.EMPTY
        val result = sut.validateProxyUrl(config.proxyConfig.url)

        assertEquals(DTOs.ValidationResult.Success, result)
    }

    @Test
    fun `validateProxyUrl returns Failure when API returns Failure`() = runBlocking {
        val detail = DTOs.ValidationDetail.ConfigItemDetail(
            configItem = DomainObjects.ConfigurationItem.PROXY_URL,
            messageKey = ValidationMessageKey.PROXY_URL_INVALID_FORMAT
        )
        coEvery { apiClient.validateProxyUrl(any<String>()) } returns CdrClientApiClient.Result.Success<DTOs.ValidationResult>(
            DTOs.ValidationResult.Failure(
                listOf(
                    detail
                )
            )
        )

        val config = CdrClientConfigDto.EMPTY
        val result = sut.validateProxyUrl(config.proxyConfig.url)

        assertEquals(DTOs.ValidationResult.Failure(listOf(detail)), result)
    }

}
