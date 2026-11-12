package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.ClientId
import com.swisscom.health.des.cdr.client.config.ClientSecret
import com.swisscom.health.des.cdr.client.config.Connector
import com.swisscom.health.des.cdr.client.config.ConnectorId
import com.swisscom.health.des.cdr.client.config.Customer
import com.swisscom.health.des.cdr.client.config.TempDownloadDir
import com.swisscom.health.des.cdr.client.config.toDto
import com.swisscom.health.des.cdr.client.handler.ConfigValidationService
import com.swisscom.health.des.cdr.client.handler.ConfigurationWriter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource
import org.springframework.http.MediaType
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.isDirectory

internal class FirstInstallationTest {
    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `fresh installation starts with the default local folder and accepts configuration from the UI`() = runTest {
        val configFile = tempDir.resolve("application-customer.yaml")
        Files.copy(Path.of("conf/default-application-customer.yaml"), configFile)
        LocalFolderInit.initLocalFolder(configFile, stateDirectory = null)

        SpringApplicationBuilder(CdrClientApplication::class.java)
            .web(WebApplicationType.REACTIVE)
            .run(
                "--spring.config.additional-location=${configFile.toUri()}",
                "--client.idp-credentials.renew-credential=false",
                "--server.port=0",
                "--management.server.port=0",
                "--logging.file.name=${tempDir.resolve("service.log")}",
            ).use { context ->
                val config = context.getBean<CdrClientConfig>()
                val validation = context.getBean<ConfigValidationService>()
                val writer = context.getBean<ConfigurationWriter>()
                val mapper = context.getBean<JsonMapper>()
                val endpoint = URI("http://localhost:${context.environment.getProperty("local.server.port")}/api/service-configuration")
                val httpClient = HttpClient.newHttpClient()

                val defaultLocalFolder = tempDir.resolve(LocalFolderInit.INBOUND_DIR_NAME)
                assertEquals(defaultLocalFolder, config.localFolder.path)
                assertTrue(defaultLocalFolder.isDirectory())
                val response = httpClient.send(HttpRequest.newBuilder(endpoint).GET().build(), BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                assertEquals(defaultLocalFolder.toString(), mapper.readTree(response.body()).get("localFolder").asString())
                assertInstanceOf<ConfigurationWriter.ConfigLookupResult.Writable>(
                    writer.isWritableConfigurationItem("client.local-folder")
                )

                val downloadDir = tempDir.resolve("inflight").createDirectory()
                val updated = configuredClient(config, downloadDir)

                val updateResponse = httpClient.updateConfiguration(endpoint, updated, mapper)
                assertEquals(200, updateResponse.statusCode(), updateResponse.body())
                val persisted = YamlPropertySourceLoader().load("customer", FileSystemResource(configFile)).single()
                assertEquals(downloadDir.toString(), persisted.getProperty("client.local-folder"))
                assertEquals(DTOs.ValidationResult.Success, validation.validateAllConfigurationItems(updated))
                httpClient.close()
            }
    }

    private fun configuredClient(config: CdrClientConfig, downloadDir: Path): CdrClientConfig =
        config.copy(
            localFolder = TempDownloadDir(downloadDir),
            idpCredentials = config.idpCredentials.copy(
                clientId = ClientId("configured-client"),
                clientSecret = ClientSecret("configured-secret"),
            ),
            customer = Customer(mutableListOf(
                Connector(
                    connectorId = ConnectorId("configured-connector"),
                    sourceFolder = tempDir.resolve("source").createDirectory(),
                    targetFolder = tempDir.resolve("target").createDirectory(),
                    contentType = MediaType.APPLICATION_XML_VALUE,
                    mode = CdrClientConfig.Mode.TEST,
                )
            )),
        )

    private fun HttpClient.updateConfiguration(endpoint: URI, config: CdrClientConfig, mapper: JsonMapper): HttpResponse<String> =
        send(
            HttpRequest.newBuilder(endpoint)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(config.toDto())))
                .build(),
            BodyHandlers.ofString()
        )
}
