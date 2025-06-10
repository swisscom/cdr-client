package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.xml.DocumentType
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.stream.Stream

internal class CdrClientConfigTest {

    @TempDir
    private lateinit var localFolder0: Path

    private lateinit var cdrClientConfig: CdrClientConfig

    @Test
    fun `test blue sky configuration`() {
        cdrClientConfig = createCdrClientConfig(blueskyConnectors())
        assertDoesNotThrow { cdrClientConfig.checkAndReport() }
    }

    @Test
    fun `test blue sky create local folder if it doesn't exist`() {
        val filePath = localFolder0.resolve("deeper")
        cdrClientConfig = createCdrClientConfig(blueskyConnectors(), filePath)
        assertDoesNotThrow { cdrClientConfig.checkAndReport() }
    }

    @Test
    fun `test fail because local folder is not a directory`() {
        val filePath = localFolder0.resolve("file.txt")
        Files.createFile(filePath)
        cdrClientConfig = createCdrClientConfig(blueskyConnectors(), filePath)
        val message = assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }.message
        assertNotNull(message)
        assertTrue(message!!.contains("Local folder is not a directory"), "actual: '${message}'")
    }

    @Test
    fun `test fail because no customer is configured`() {
        cdrClientConfig = createCdrClientConfig(emptyList())

        val message = assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }.message
        assertNotNull(message)
        assertTrue(message!!.contains("There were no customer entries configured"))
    }

    @Test
    fun `test fail because local folder is overlapping with another folder`() {
        cdrClientConfig = createCdrClientConfig(blueskyConnectors(), targetFolder0)

        val message = assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }.message
        assertNotNull(message)
        val pattern = "The local folder '.*' is configured as source or target folder for a connector".toRegex()
        assertTrue(message!!.contains(pattern))
    }

    @ParameterizedTest
    @MethodSource("provideSameFolderConnectors")
    fun `test fail because same folder is illegally used`(list: List<CdrClientConfig.Connector>, expectedMessagePart: String) {
        cdrClientConfig = createCdrClientConfig(list)

        val message = assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }.message
        assertNotNull(message)
        assertTrue(message!!.contains(expectedMessagePart), "actual: '${message}'")
    }

    @ParameterizedTest
    @MethodSource("provideSameFolderConnectorsTypeFolders")
    fun `test fail because same folder is illegally used cross type folders`(list: List<CdrClientConfig.Connector>, expectedMessagePart: String) {
        cdrClientConfig = createCdrClientConfig(list)

        val message = assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }.message
        assertNotNull(message)
        assertTrue(message!!.contains(expectedMessagePart), "actual: '${message}'")
    }

    @ParameterizedTest
    @MethodSource("provideSameModeConnectors")
    fun `test fail because same mode is used multiple times for the same connector id`(list: List<CdrClientConfig.Connector>) {
        cdrClientConfig = createCdrClientConfig(list)

        val message = assertThrows<IllegalStateException> { cdrClientConfig.checkAndReport() }.message
        assertNotNull(message)
        assertTrue(message!!.contains("A connector has `production` or `test` mode defined defined twice"))
    }

    private fun createCdrClientConfig(customers: List<CdrClientConfig.Connector>, defaultLocalFolder: Path = localFolder0): CdrClientConfig {
        return CdrClientConfig(
            fileSynchronizationEnabled = FileSynchronization.ENABLED,
            scheduleDelay = Duration.ofSeconds(1),
            localFolder = defaultLocalFolder,
            cdrApi = CdrClientConfig.Endpoint(
                scheme = "http",
                host = "localhost",
                port = 8080,
                basePath = "api",
            ),
            credentialApi = CdrClientConfig.Endpoint(
                scheme = "http",
                host = "localhost",
                port = 8080,
                basePath = "client-credentials",
            ),
            customer = customers,
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
            retryDelay = listOf(Duration.ofSeconds(1)),
            filesInProgressCacheSize = DataSize.ofMegabytes(1),
            idpCredentials = IdpCredentials(
                tenantId = "tenantId",
                clientId = "clientId",
                clientSecret = ClientSecret("secret"),
                scopes = listOf("CDR"),
                renewCredential = RenewCredential(true)
            ),
            idpEndpoint = URL("http://localhost"),
            fileBusyTestStrategy = CdrClientConfig.FileBusyTestStrategy.FILE_SIZE_CHANGED,
            fileBusyTestInterval = Duration.ofMillis(250),
            fileBusyTestTimeout = Duration.ofSeconds(1),
            retryTemplate = CdrClientConfig.RetryTemplateConfig(
                retries = 3,
                initialDelay = Duration.ofSeconds(5),
                maxDelay = Duration.ofSeconds(5),
                multiplier = 2.0,
            ),
        )
    }

    companion object {
        @JvmStatic
        val FORUM_DATENAUSTAUSCH_MEDIA_TYPE = MediaType.parseMediaType("application/forumdatenaustausch+xml;charset=UTF-8")

        @TempDir
        private lateinit var sourceFolder0: Path

        @TempDir
        private lateinit var targetFolder0: Path

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

        private const val USED_FOR_BOTH_ERROR_MESSAGE_PART = "both source and target directories"
        private const val DUPLICATE_SOURCE_FOLDER_ERROR_MESSAGE_PART = "Duplicate source folders detected"

        @JvmStatic
        private fun blueskyConnectors() = listOf(
            createConnector(
                "connectorId",
                targetFolder0,
                sourceFolder0,
                FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                CdrClientConfig.Mode.TEST,
                mapOf(
                    DocumentType.CONTAINER to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                    DocumentType.CREDIT to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                    DocumentType.FORM to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                    DocumentType.HOSPITAL_MCD to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                    DocumentType.INVOICE to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                    DocumentType.NOTIFICATION to CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetFolder0),
                )
            ),
            createConnector(
                "connectorId", targetFolder1, sourceFolder1, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION,
                mapOf(
                    DocumentType.CONTAINER to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub")),
                    DocumentType.CREDIT to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub1")),
                    DocumentType.FORM to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub2")),
                    DocumentType.HOSPITAL_MCD to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub3")),
                    DocumentType.INVOICE to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub4")),
                    DocumentType.NOTIFICATION to CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceFolder2.resolve("sub5")),
                )
            ),
            createConnector("connectorId2", targetFolder2, sourceFolder2, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.TEST),
            createConnector("connectorId3", targetFolder3, sourceFolder3, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION),
            createConnector("connectorId4", targetFolder4, sourceFolder4, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.TEST)
        )

        @JvmStatic
        @Suppress("LongMethod", "UnusedPrivateMember")
        private fun provideSameFolderConnectors(): Stream<Arguments> = Stream.of(
            Arguments.of(
                listOf(
                    createConnector("connectorId", targetFolder0, sourceFolder0, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.TEST),
                    createConnector("connectorId", targetFolder0, sourceFolder0, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION)
                ),
                DUPLICATE_SOURCE_FOLDER_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector("connectorId", targetFolder2, targetFolder2, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.TEST),
                    createConnector("connectorId3", targetFolder3, sourceFolder3, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION)
                ),
                USED_FOR_BOTH_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector("connectorId", targetFolder0, sourceFolder0, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.TEST),
                    createConnector("connectorId2", sourceFolder0, targetFolder2, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION)
                ),
                USED_FOR_BOTH_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector("connectorId2", targetFolder0, sourceFolder0, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.TEST),
                    createConnector("connectorId3", sourceFolder2, targetFolder0, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION),
                ),
                USED_FOR_BOTH_ERROR_MESSAGE_PART
            )
        )

        @JvmStatic
        @Suppress("LongMethod", "UnusedPrivateMember")
        private fun provideSameModeConnectors(): Stream<Arguments> = Stream.of(
            Arguments.of(
                listOf(
                    createConnector("connectorId", targetFolder0, sourceFolder0, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.TEST),
                    createConnector("connectorId", targetFolder2, sourceFolder2, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.TEST)
                )
            ),
            Arguments.of(
                listOf(
                    createConnector("connectorId", targetFolder0, sourceFolder0, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION),
                    createConnector("connectorId", targetFolder2, sourceFolder2, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION)
                )
            ),
            Arguments.of(
                listOf(
                    createConnector("connectorId2", targetFolder2, sourceFolder2, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION),
                    createConnector("connectorId2", targetFolder0, sourceFolder0, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION),
                    createConnector("connectorId2", targetFolder3, sourceFolder3, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.TEST),
                    createConnector("connectorId2", targetFolder4, sourceFolder4, FORUM_DATENAUSTAUSCH_MEDIA_TYPE, CdrClientConfig.Mode.PRODUCTION)
                )
            )
        )

        @JvmStatic
        @Suppress("LongMethod", "UnusedPrivateMember")
        private fun provideSameFolderConnectorsTypeFolders(): Stream<Arguments> = Stream.of(
            Arguments.of(
                listOf(
                    createConnector(
                        "connectorId",
                        targetFolder0,
                        sourceFolder0,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.TEST,
                        createDocTypeFolders(targetFolder0, sourceFolder0)
                    ),
                ),
                DUPLICATE_SOURCE_FOLDER_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector(
                        "connectorId",
                        targetFolder0,
                        sourceFolder0,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.TEST,
                        createDocTypeFolders(sourceFolder0, sourceFolder1)
                    ),
                ),
                USED_FOR_BOTH_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector(
                        "connectorId",
                        targetFolder0,
                        sourceFolder0,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.TEST,
                        createDocTypeFolders(targetFolder0, sourceFolder1)
                    ),
                    createConnector(
                        "connectorId2",
                        targetFolder2,
                        sourceFolder2,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.PRODUCTION,
                        createDocTypeFolders(targetFolder1, sourceFolder0)
                    )
                ),
                DUPLICATE_SOURCE_FOLDER_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector(
                        "connectorId",
                        targetFolder0,
                        sourceFolder0,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.TEST,
                        createDocTypeFolders(targetFolder0, sourceFolder1)
                    ),
                    createConnector(
                        "connectorId",
                        targetFolder2,
                        sourceFolder2,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.PRODUCTION,
                        createDocTypeFolders(targetFolder1, sourceFolder0)
                    )
                ),
                DUPLICATE_SOURCE_FOLDER_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector(
                        "connectorId",
                        targetFolder0,
                        sourceFolder0,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.TEST,
                        createDocTypeFolders(targetFolder0, sourceFolder1)
                    ),
                    createConnector(
                        "connectorId2",
                        targetFolder2,
                        sourceFolder2,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.PRODUCTION,
                        createDocTypeFolders(targetFolder1, sourceFolder1)
                    )
                ),
                DUPLICATE_SOURCE_FOLDER_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector(
                        "connectorId",
                        targetFolder0,
                        sourceFolder0,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.TEST,
                        createDocTypeFolders(targetFolder0, sourceFolder1)
                    ),
                    createConnector(
                        "connectorId2",
                        targetFolder2,
                        sourceFolder2,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.PRODUCTION,
                        createDocTypeFolders(sourceFolder0, sourceFolder2)
                    )
                ),
                USED_FOR_BOTH_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector(
                        "connectorId",
                        targetFolder0,
                        sourceFolder0,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.TEST,
                        createDocTypeFolders(targetFolder0, sourceFolder1)
                    ),
                    createConnector(
                        "connectorId2",
                        targetFolder2,
                        sourceFolder2,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.PRODUCTION,
                        createDocTypeFolders(sourceFolder1, sourceFolder3)
                    )
                ),
                USED_FOR_BOTH_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector(
                        "connectorId",
                        targetFolder0,
                        sourceFolder0,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.TEST,
                        createDocTypeFolders(targetFolder0, sourceFolder1)
                    ),
                    createConnector(
                        "connectorId",
                        targetFolder2,
                        sourceFolder2,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.PRODUCTION,
                        createDocTypeFolders(targetFolder1, sourceFolder3)
                    ),
                    createConnector(
                        "connectorId2",
                        targetFolder2,
                        sourceFolder4,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.PRODUCTION,
                        createDocTypeFolders(sourceFolder1, null, DocumentType.FORM)
                    )
                ),
                USED_FOR_BOTH_ERROR_MESSAGE_PART
            ),
            Arguments.of(
                listOf(
                    createConnector(
                        "connectorId",
                        targetFolder0,
                        sourceFolder0,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.TEST,
                        createDocTypeFolders(targetFolder0, sourceFolder1)
                    ),
                    createConnector(
                        "connectorId",
                        targetFolder2,
                        sourceFolder2,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.PRODUCTION,
                        createDocTypeFolders(targetFolder1, sourceFolder3)
                    ),
                    createConnector(
                        "connectorId2",
                        targetFolder2,
                        sourceFolder4,
                        FORUM_DATENAUSTAUSCH_MEDIA_TYPE,
                        CdrClientConfig.Mode.PRODUCTION,
                        createDocTypeFolders(targetFolder2, sourceFolder1, DocumentType.FORM)
                    )
                ),
                DUPLICATE_SOURCE_FOLDER_ERROR_MESSAGE_PART
            ),
        )

        @Suppress("LongParameterList")
        private fun createConnector(
            connectorId: String,
            targetFolder: Path,
            sourceFolder: Path,
            contentType: MediaType,
            mode: CdrClientConfig.Mode,
            typeFolders: Map<DocumentType, CdrClientConfig.Connector.DocTypeFolders> = emptyMap()
        ): CdrClientConfig.Connector {
            return CdrClientConfig.Connector(
                connectorId = connectorId,
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
                contentType = contentType,
                mode = mode,
                docTypeFolders = typeFolders,
            )
        }

        private fun createDocTypeFolders(
            targetFolder: Path,
            sourceFolder: Path?,
            documentType: DocumentType = DocumentType.INVOICE
        ): Map<DocumentType, CdrClientConfig.Connector.DocTypeFolders> {
            return mapOf(documentType to CdrClientConfig.Connector.DocTypeFolders(
                targetFolder = targetFolder,
                sourceFolder = sourceFolder,
            ))
        }
    }

}
