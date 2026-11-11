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
import com.swisscom.health.des.cdr.client.handler.FileMonitoringService
import com.swisscom.health.des.cdr.client.handler.SchedulingValidationService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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

@ExtendWith(OutputCaptureExtension::class)
internal class FirstInstallationTest {
    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `fresh installation starts with no local folder and accepts configuration from the UI`(output: CapturedOutput) = runTest {
        val configFile = tempDir.resolve("application-customer.yaml")
        Files.copy(Path.of("conf/default-application-customer.yaml"), configFile)

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
                val scheduling = context.getBean<SchedulingValidationService>()
                val validation = context.getBean<ConfigValidationService>()
                val writer = context.getBean<ConfigurationWriter>()
                val mapper = context.getBean<JsonMapper>()
                val endpoint = URI("http://localhost:${context.environment.getProperty("local.server.port")}/api/service-configuration")
                val httpClient = HttpClient.newHttpClient()

                assertEquals("", config.localFolder.value)
                val response = httpClient.send(HttpRequest.newBuilder(endpoint).GET().build(), BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                assertEquals("", mapper.readTree(response.body()).get("localFolder").asString())
                assertFalse(validation.isConfigValid)
                assertFalse(scheduling.isSchedulingAllowed)
                assertTrue(output.all.contains("No file upload/download will be possible due to configuration validation failure."))
                assertFalse(output.all.contains("IllegalStateException"))
                assertInstanceOf<ConfigurationWriter.ConfigLookupResult.Writable>(
                    writer.isWritableConfigurationItem("client.local-folder")
                )
                assertEquals(400, httpClient.updateConfiguration(endpoint, config, mapper).statusCode())
                context.getBean<FileMonitoringService>().checkFileStatus()

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
