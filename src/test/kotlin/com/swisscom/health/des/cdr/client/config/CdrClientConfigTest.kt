package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.config.CdrClientConfig.IdpCredentials
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Path
import java.time.Duration

class CdrClientConfigTest {

    @TempDir
    private lateinit var localFolder: Path

    @TempDir
    private lateinit var sourceFolder: Path

    @TempDir
    private lateinit var targetFolder: Path

    @TempDir
    private lateinit var sourceFolder1: Path

    @TempDir
    private lateinit var targetFolder1: Path

    @TempDir
    private lateinit var sourceFolder2: Path

    @TempDir
    private lateinit var targetFolder2: Path

    @TempDir
    private lateinit var sourceFolder3: Path

    @TempDir
    private lateinit var targetFolder3: Path

    @TempDir
    private lateinit var sourceFolder4: Path

    @TempDir
    private lateinit var targetFolder4: Path

    private lateinit var cdrClientConfig: CdrClientConfig

    @Test
    fun `test bluesky configuration`() {
        cdrClientConfig = createCdrClientConfig(
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "connectorId",
                    targetFolder = targetFolder,
                    sourceFolder = sourceFolder,
                    contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId",
                    targetFolder = targetFolder1,
                    sourceFolder = sourceFolder1,
                    contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                    mode = CdrClientConfig.Mode.PRODUCTION
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = targetFolder,
                    sourceFolder = sourceFolder2,
                    contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId3",
                    targetFolder = targetFolder,
                    sourceFolder = sourceFolder3,
                    contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                    mode = CdrClientConfig.Mode.PRODUCTION
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId4",
                    targetFolder = targetFolder4,
                    sourceFolder = sourceFolder4,
                    contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                    mode = CdrClientConfig.Mode.TEST
                )
            )
        )

        assertDoesNotThrow { cdrClientConfig.checkAndReport() }
    }

    @Test
    fun `test fail because no customer is configured`() {
        cdrClientConfig = createCdrClientConfig(emptyList())

        assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }
    }

    @Test
    fun `test fail because same folder is used`() {
        provideSameFolderConnectors().forEach { customers ->
            cdrClientConfig = createCdrClientConfig(customers)

            assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }
        }
    }

    @Test
    fun `test fail because same mode is used multiple times for the same connector id`() {
        provideSameModeConnectors().forEach { customers ->
            cdrClientConfig = createCdrClientConfig(customers)

            assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }
        }
    }


    private fun createCdrClientConfig(customers: List<CdrClientConfig.Connector>): CdrClientConfig {
        return CdrClientConfig(
            scheduleDelay = Duration.ofSeconds(1),
            localFolder = localFolder,
            endpoint = CdrClientConfig.Endpoint(
                scheme = "http",
                host = "localhost",
                port = 8080,
                basePath = "/api"
            ),
            customer = customers,
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
            retryDelay = listOf(Duration.ofSeconds(1)),
            filesInProgressCacheSize = DataSize.ofMegabytes(1),
            idpCredentials = IdpCredentials(
                tenantId = "tenantId",
                clientId = "clientId",
                clientSecret = "secret",
                scopes = listOf("CDR")
            ),
            idpEndpoint = URL("http://localhost")
        )
    }

    @Suppress("LongMethod")
    private fun provideSameFolderConnectors() = listOf(
        listOf(
            CdrClientConfig.Connector(
                connectorId = "connectorId",
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.TEST
            ),
            CdrClientConfig.Connector(
                connectorId = "connectorId",
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.PRODUCTION
            )
        ),
        listOf(
            CdrClientConfig.Connector(
                connectorId = "connectorId2",
                targetFolder = targetFolder2,
                sourceFolder = targetFolder2,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.TEST
            ),
            CdrClientConfig.Connector(
                connectorId = "connectorId3",
                targetFolder = targetFolder3,
                sourceFolder = sourceFolder3,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.PRODUCTION
            )
        ),
        listOf(
            CdrClientConfig.Connector(
                connectorId = "connectorId2",
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.TEST
            ),
            CdrClientConfig.Connector(
                connectorId = "connectorId3",
                targetFolder = sourceFolder,
                sourceFolder = targetFolder2,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.PRODUCTION
            )
        ),
        listOf(
            CdrClientConfig.Connector(
                connectorId = "connectorId2",
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.TEST
            ),
            CdrClientConfig.Connector(
                connectorId = "connectorId3",
                targetFolder = sourceFolder2,
                sourceFolder = targetFolder,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.PRODUCTION
            )
        )
    )

    @Suppress("LongMethod")
    private fun provideSameModeConnectors() = listOf(
        listOf(
            CdrClientConfig.Connector(
                connectorId = "connectorId",
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.TEST
            ),
            CdrClientConfig.Connector(
                connectorId = "connectorId",
                targetFolder = targetFolder2,
                sourceFolder = sourceFolder2,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.TEST
            )
        ),
        listOf(
            CdrClientConfig.Connector(
                connectorId = "connectorId2",
                targetFolder = targetFolder2,
                sourceFolder = sourceFolder2,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.PRODUCTION
            ),
            CdrClientConfig.Connector(
                connectorId = "connectorId2",
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.PRODUCTION
            )
        ),
        listOf(
            CdrClientConfig.Connector(
                connectorId = "connectorId2",
                targetFolder = targetFolder2,
                sourceFolder = sourceFolder2,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.PRODUCTION
            ),
            CdrClientConfig.Connector(
                connectorId = "connectorId2",
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.PRODUCTION
            ),
            CdrClientConfig.Connector(
                connectorId = "connectorId2",
                targetFolder = targetFolder3,
                sourceFolder = sourceFolder3,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.TEST
            ),
            CdrClientConfig.Connector(
                connectorId = "connectorId2",
                targetFolder = targetFolder4,
                sourceFolder = sourceFolder4,
                contentType = FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                mode = CdrClientConfig.Mode.PRODUCTION
            )
        )
    )

    companion object {
        @JvmStatic
        val FORUM_DATENAUSTAUSCH_MEDIA_TYPE = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")
    }

}
