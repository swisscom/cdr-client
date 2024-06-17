package com.swisscom.health.des.cdr.clientvm.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

class CdrClientConfigTest {

    private lateinit var cdrClientConfig: CdrClientConfig

    @Test
    fun `test bluesky configuration`() {
        cdrClientConfig = createCdrClientConfig(
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "connectorId",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId",
                    targetFolder = "targetFolder1",
                    sourceFolder = "sourceFolder1",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder2",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId3",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder3",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId4",
                    targetFolder = "targetFolder4",
                    sourceFolder = "sourceFolder4",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                )
            )
        )

        assertDoesNotThrow { cdrClientConfig.checkAndReport() }
        assertFalse(cdrClientConfig.toString().contains(functionKey))
    }

    @Test
    fun `test fail because no customer is configured`() {
        cdrClientConfig = createCdrClientConfig(emptyList())

        assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }
    }

    @ParameterizedTest
    @MethodSource("provideSameFolderConnectors")
    fun `test fail because same folder is used`(customers: List<CdrClientConfig.Connector>) {
        cdrClientConfig = createCdrClientConfig(customers)

        assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }
    }

    @ParameterizedTest
    @MethodSource("provideSameModeConnectors")
    fun `test fail because same mode is used multiple times for the same connector id`(customers: List<CdrClientConfig.Connector>) {
        cdrClientConfig = createCdrClientConfig(customers)

        assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }
    }


    private fun createCdrClientConfig(customers: List<CdrClientConfig.Connector>): CdrClientConfig {
        return CdrClientConfig(
            functionKey = functionKey,
            scheduleDelay = "scheduleDelay",
            localFolder = "localFolder",
            endpoint = CdrClientConfig.Endpoint(
                scheme = "http",
                host = "localhost",
                port = 8080,
                basePath = "/api"
            ),
            customer = customers,
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
            retryDelay = arrayOf(Duration.ofSeconds(1))
        )
    }

    companion object {
        const val functionKey = "functionKey123"

        @JvmStatic
        @Suppress("LongMethod")
        fun provideSameFolderConnectors() = listOf(
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "connectorId",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                )
            ),
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder2",
                    sourceFolder = "targetFolder2",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId3",
                    targetFolder = "targetFolder3",
                    sourceFolder = "sourceFolder3",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                )
            ),
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId3",
                    targetFolder = "sourceFolder",
                    sourceFolder = "targetFolder2",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                )
            ),
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId3",
                    targetFolder = "sourceFolder2",
                    sourceFolder = "targetFolder",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                )
            )
        )

        @JvmStatic
        @Suppress("LongMethod")
        fun provideSameModeConnectors() = listOf(
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "connectorId",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId",
                    targetFolder = "targetFolder2",
                    sourceFolder = "sourceFolder2",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                )
            ),
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder2",
                    sourceFolder = "sourceFolder2",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                )
            ),
            listOf(
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder2",
                    sourceFolder = "sourceFolder2",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder",
                    sourceFolder = "sourceFolder",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder3",
                    sourceFolder = "sourceFolder3",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.TEST
                ),
                CdrClientConfig.Connector(
                    connectorId = "connectorId2",
                    targetFolder = "targetFolder4",
                    sourceFolder = "sourceFolder4",
                    contentType = "contentType",
                    mode = CdrClientConfig.Mode.PRODUCTION
                )
            )
        )
    }

}
