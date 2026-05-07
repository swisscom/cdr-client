package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.xml.DocumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URI
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
            scheme = "http",
            host = Host("localhost"),
            port = 87,
            basePath = "/"
        ),
        filesInProgressCacheSize = DataSize.ofMegabytes(1L),
        idpCredentials = IdpCredentials(
            tenantId = TenantId("fake-tenant-id"),
            clientId = ClientId("fake-client-id"),
            clientSecret = ClientSecret("fake-client-secret"),
            scope = Scope("scope1"),
            renewCredential = RenewCredential.ENABLED,
            maxCredentialAge = Duration.ofDays(30),
            lastCredentialRenewalTime = LastCredentialRenewalTime(LAST_UPDATED_AT),
        ),
        idpEndpoint = URI("http://localhost").toURL(),
        localFolder = TempDownloadDir(RELATIVE_PATH),
        pullThreadPoolSize = 1,
        pushThreadPoolSize = 1,
        retryDelay = emptyList(),
        scheduleDelay = Duration.ofSeconds(1L),
        credentialApi = CredentialApi(
            scheme = "http",
            host = Host("localhost"),
            port = 87,
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
        fileBusyTestStrategy = FileBusyTestStrategyProperty(CdrClientConfig.FileBusyTestStrategy.NEVER_BUSY),
        proxyConfig = DTOs.CdrClientConfig.ProxyConfig.EMPTY.toCdrClientConfig(),
        oldFileThreshold = Duration.ofHours(2L),
        fileSystemCheckInterval = Duration.ofMinutes(5L),
    )

    private val sameConfigButAbsolutePaths: CdrClientConfig = CdrClientConfig(
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
            scheme = "http",
            host = Host("localhost"),
            port = 87,
            basePath = "api/documents"
        ),
        filesInProgressCacheSize = DataSize.ofMegabytes(1L),
        idpCredentials = IdpCredentials(
            tenantId = TenantId("fake-tenant-id"),
            clientId = ClientId("fake-client-id"),
            clientSecret = ClientSecret("fake-client-secret"),
            scope = Scope("scope1"),
            renewCredential = RenewCredential.ENABLED,
            maxCredentialAge = Duration.ofDays(30),
            lastCredentialRenewalTime = LastCredentialRenewalTime(LAST_UPDATED_AT),
        ),
        idpEndpoint = URI("http://localhost").toURL(),
        localFolder = TempDownloadDir(ABSOLUTE_PATH),
        pullThreadPoolSize = 1,
        pushThreadPoolSize = 1,
        retryDelay = emptyList(),
        scheduleDelay = Duration.ofSeconds(1L),
        credentialApi = CredentialApi(
            scheme = "http",
            host = Host("localhost"),
            port = 87,
            basePath = "api/client-credentials",
        ),
        retryTemplate = CdrClientConfig.RetryTemplateConfig(
            retries = 1,
            initialDelay = Duration.ofSeconds(1L),
            maxDelay = Duration.ofSeconds(1L),
            multiplier = 2.0
        ),
        fileBusyTestInterval = Duration.ofSeconds(1L),
        fileBusyTestTimeout = Duration.ofSeconds(1L),
        fileBusyTestStrategy = FileBusyTestStrategyProperty(CdrClientConfig.FileBusyTestStrategy.NEVER_BUSY),
        proxyConfig = DTOs.CdrClientConfig.ProxyConfig.EMPTY.toCdrClientConfig(),
        oldFileThreshold = Duration.ofHours(2L),
        fileSystemCheckInterval = Duration.ofMinutes(5L),
    )

    @Test
    fun `there and back again - make sure no information is lost in translation`() {
        val configRoundTrip = configAllRelativePaths.toDto().toCdrClientConfig()
        sameConfigButAbsolutePaths.copy(
            idpCredentials = sameConfigButAbsolutePaths.idpCredentials.copy(
                clientSecret = ClientSecret.MASKED_SECRET
            )
        ).also { sameConfigButAbsolutePathsMaskedSecret ->
            assertEquals(sameConfigButAbsolutePathsMaskedSecret, configRoundTrip)
        }
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
