package com.swisscom.health.des.cdr.client.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class ConfigConverterTest {

    private val config: CdrClientConfig = CdrClientConfig(
        fileSynchronizationEnabled = FileSynchronization.ENABLED,
        customer = listOf(
            CdrClientConfig.Connector(
                connectorId = "1",
                targetFolder = EMPTY_PATH,
                sourceFolder = EMPTY_PATH,
                contentType = MediaType.APPLICATION_OCTET_STREAM,
                sourceArchiveEnabled = false,
                sourceArchiveFolder = EMPTY_PATH,
                sourceErrorFolder = EMPTY_PATH,
                mode = CdrClientConfig.Mode.PRODUCTION,
                docTypeFolders = emptyMap()
            )
        ),
        cdrApi = CdrClientConfig.Endpoint(
            scheme = "https",
            host = "localhost",
            port = 8080,
            basePath = "/"
        ),
        filesInProgressCacheSize = DataSize.ofMegabytes(1L),
        idpCredentials = IdpCredentials(
            tenantId = "",
            clientId = "",
            clientSecret = ClientSecret(""),
            scopes = emptyList(),
            renewCredential = RenewCredential.ENABLED,
            maxCredentialAge = Duration.ofDays(30),
            lastCredentialRenewalTime = LastUpdatedAt(Instant.now()),
        ),
        idpEndpoint = URL("http://localhost:8080"),
        localFolder = EMPTY_PATH,
        pullThreadPoolSize = 1,
        pushThreadPoolSize = 1,
        retryDelay = emptyList(),
        scheduleDelay = Duration.ofSeconds(1L),
        credentialApi = CdrClientConfig.Endpoint(
            scheme = "https",
            host = "localhost",
            port = 8080,
            basePath = "/"
        ),
        retryTemplate = CdrClientConfig.RetryTemplateConfig(
            retries = 1,
            initialDelay = Duration.ofSeconds(1L),
            maxDelay = Duration.ofSeconds(1L),
            multiplier = 2.0
        ),
        fileBusyTestInterval = Duration.ofSeconds(1L),
        fileBusyTestTimeout = Duration.ofSeconds(1L),
        fileBusyTestStrategy = CdrClientConfig.FileBusyTestStrategy.NEVER_BUSY
    )


    @Test
    fun `there and back again - make sure no information is lost in translation`() {
        val configRoundTrip = config.toDto().toCdrClientConfig()
        assertEquals(config, configRoundTrip)

        val configWithDelta = config.toDto().copy(pullThreadPoolSize = 2).toCdrClientConfig()
        assertNotEquals(config, configWithDelta)
    }

    companion object {
        @JvmStatic
        private val EMPTY_PATH = Path.of("")
    }
}
