package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.xml.DocumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.absolute

class ConfigConverterTest {

    private val configAllRelativePaths: CdrClientConfig = CdrClientConfig(
        fileSynchronizationEnabled = FileSynchronization.ENABLED,
        customer = Customer(
            mutableListOf(
                Connector(
                    connectorId = ConnectorId("1"),
                    targetFolder = RELATIVE_PATH,
                    sourceFolder = RELATIVE_PATH,
                    contentType = MediaType.APPLICATION_OCTET_STREAM.toString(),
                    sourceArchiveEnabled = false,
                    sourceArchiveFolder = RELATIVE_PATH,
                    sourceErrorFolder = RELATIVE_PATH,
                    mode = CdrClientConfig.Mode.PRODUCTION,
                    docTypeFolders = mapOf(
                        DocumentType.CONTAINER to Connector.DocTypeFolders(sourceFolder = RELATIVE_PATH),
                        DocumentType.CREDIT to Connector.DocTypeFolders(targetFolder = RELATIVE_PATH),
                    ),
                )
            )
        ),
        cdrApi = CdrApi(
            scheme = "https",
            host = Host("localhost"),
            port = 8080,
            basePath = "/"
        ),
        filesInProgressCacheSize = DataSize.ofMegabytes(1L),
        idpCredentials = IdpCredentials(
            tenantId = TenantId("fake-tenant-id"),
            clientId = ClientId("fake-client-id"),
            clientSecret = ClientSecret("fake-client-secret"),
            scopes = Scopes(mutableListOf(Scope("scope1"))),
            renewCredential = RenewCredential.ENABLED,
            maxCredentialAge = Duration.ofDays(30),
            lastCredentialRenewalTime = LastCredentialRenewalTime(LAST_UPDATED_AT),
        ),
        idpEndpoint = URL("http://localhost:8080"),
        localFolder = TempDownloadDir(RELATIVE_PATH),
        pullThreadPoolSize = 1,
        pushThreadPoolSize = 1,
        retryDelay = emptyList(),
        scheduleDelay = Duration.ofSeconds(1L),
        credentialApi = CredentialApi(
            scheme = "https",
            host = Host("localhost"),
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
        fileBusyTestStrategy = FileBusyTestStrategyProperty(CdrClientConfig.FileBusyTestStrategy.NEVER_BUSY)
    )

    private val configAllAbsolutePaths: CdrClientConfig = CdrClientConfig(
        fileSynchronizationEnabled = FileSynchronization.ENABLED,
        customer = Customer(
            mutableListOf(
                Connector(
                    connectorId = ConnectorId("1"),
                    targetFolder = ABSOLUTE_PATH,
                    sourceFolder = ABSOLUTE_PATH,
                    contentType = MediaType.APPLICATION_OCTET_STREAM.toString(),
                    sourceArchiveEnabled = false,
                    sourceArchiveFolder = ABSOLUTE_PATH,
                    sourceErrorFolder = ABSOLUTE_PATH,
                    mode = CdrClientConfig.Mode.PRODUCTION,
                    docTypeFolders = mapOf(
                        DocumentType.CONTAINER to Connector.DocTypeFolders(sourceFolder = ABSOLUTE_PATH),
                        DocumentType.CREDIT to Connector.DocTypeFolders(targetFolder = ABSOLUTE_PATH),
                    ),
                )
            )),
        cdrApi = CdrApi(
            scheme = "https",
            host = Host("localhost"),
            port = 8080,
            basePath = "/"
        ),
        filesInProgressCacheSize = DataSize.ofMegabytes(1L),
        idpCredentials = IdpCredentials(
            tenantId = TenantId("fake-tenant-id"),
            clientId = ClientId("fake-client-id"),
            clientSecret = ClientSecret("fake-client-secret"),
            scopes = Scopes(mutableListOf(Scope("scope1"))),
            renewCredential = RenewCredential.ENABLED,
            maxCredentialAge = Duration.ofDays(30),
            lastCredentialRenewalTime = LastCredentialRenewalTime(LAST_UPDATED_AT),
        ),
        idpEndpoint = URL("http://localhost:8080"),
        localFolder = TempDownloadDir(ABSOLUTE_PATH),
        pullThreadPoolSize = 1,
        pushThreadPoolSize = 1,
        retryDelay = emptyList(),
        scheduleDelay = Duration.ofSeconds(1L),
        credentialApi = CredentialApi(
            scheme = "https",
            host = Host("localhost"),
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
        fileBusyTestStrategy = FileBusyTestStrategyProperty(CdrClientConfig.FileBusyTestStrategy.NEVER_BUSY)
    )


    @Test
    fun `there and back again - make sure no information is lost in translation`() {
        val configRoundTrip = configAllRelativePaths.toDto().toCdrClientConfig()
        assertEquals(configAllAbsolutePaths, configRoundTrip)
    }

    private companion object {
        @JvmStatic
        private val RELATIVE_PATH = Path.of("foo/bar")

        @JvmStatic
        private val ABSOLUTE_PATH = RELATIVE_PATH.absolute()

        @JvmStatic
        private val LAST_UPDATED_AT = Instant.now()
    }

}
