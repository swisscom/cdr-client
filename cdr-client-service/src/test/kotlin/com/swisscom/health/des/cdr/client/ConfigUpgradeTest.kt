package com.swisscom.health.des.cdr.client

import tools.jackson.dataformat.yaml.YAMLFactory
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.dataformat.yaml.YAMLWriteFeature
import tools.jackson.module.kotlin.kotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.inputStream
import kotlin.io.path.name

class ConfigUpgradeTest {

    @TempDir
    private lateinit var tempDir: Path

    private val yamlMapper: YAMLMapper =
        YAMLMapper.builder(
            YAMLFactory.builder()
                .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                .enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
                .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .build()
        )
            .addModule(kotlinModule { })
            .build()

    @BeforeEach
    fun copyResources() {
        listOf(
            "config_versions/application-customer-1.0.yaml",
            "config_versions/application-customer-1.1.yaml",
            "config_versions/application-customer-1.2.yaml",
            "config_versions/application-customer-1.1_invalid.yaml"
        ).forEach { resourcePath: String ->
            Path.of(this.javaClass.classLoader.getResource(resourcePath)!!.path)
                .let { source: Path ->
                    source.copyTo(target = tempDir.resolve(source.name), overwrite = false)
                }
        }
    }

    @Test
    fun `migrate 1_0 to 1_2`() {
        val configFile = tempDir.resolve("application-customer-1.0.yaml")
        ConfigUpgrade.applyPendingUpgradeSteps(configLocation = configFile)
            .also { upgradeResult: UpgradeResult ->
                assertInstanceOf<UpgradeResult.Success>(upgradeResult)
                assertEquals("1.2", upgradeResult.version.value)
                val afterMigration = yamlMapper.readTree(configFile.inputStream())
                assertEquals("1.2", afterMigration.get("version").asString())
                assertTrue(afterMigration.at("/client/proxy-url").isMissingNode)
                assertEquals("", afterMigration.at("/client/proxy-config/url").asString())
                assertEquals("", afterMigration.at("/client/proxy-config/username").asString())
                assertEquals("", afterMigration.at("/client/proxy-config/password").asString())
            }
    }

    @Test
    fun `migrate 1_1 to 1_2`() {
        val configFile = tempDir.resolve("application-customer-1.1.yaml")
        ConfigUpgrade.applyPendingUpgradeSteps(configLocation = configFile)
            .also { upgradeResult: UpgradeResult ->
                assertInstanceOf<UpgradeResult.Success>(upgradeResult)
                assertEquals("1.2", upgradeResult.version.value)
                val afterMigration = yamlMapper.readTree(configFile.inputStream())
                assertEquals("1.2", afterMigration.get("version").asString())
                assertTrue(afterMigration.at("/client/proxy-url").isMissingNode)
                assertEquals("https://proxy.internal:8080", afterMigration.at("/client/proxy-config/url").asString())
                assertEquals("", afterMigration.at("/client/proxy-config/username").asString())
                assertEquals("", afterMigration.at("/client/proxy-config/password").asString())
            }
    }

    @Test
    fun `migrate 1_2 to 1_2`() {
        val configFile = tempDir.resolve("application-customer-1.2.yaml")
        ConfigUpgrade.applyPendingUpgradeSteps(configLocation = configFile)
            .also { upgradeResult: UpgradeResult ->
                assertInstanceOf<UpgradeResult.AlreadyAtLatestVersion>(upgradeResult)
                val beforeMigration =
                    yamlMapper.readTree(this.javaClass.classLoader.getResource("config_versions/application-customer-1.2.yaml")!!.openStream())
                val afterMigration = yamlMapper.readTree(configFile.inputStream())
                assertEquals(beforeMigration, afterMigration)
            }
    }

    @Test
    fun `failed migration must not change config file`() {
        val configFile = tempDir.resolve("application-customer-1.1_invalid.yaml")
        ConfigUpgrade.applyPendingUpgradeSteps(configLocation = configFile)
            .also { upgradeResult: UpgradeResult ->
                assertInstanceOf<UpgradeResult.Failure>(upgradeResult)
                val beforeMigration =
                    yamlMapper.readTree(this.javaClass.classLoader.getResource("config_versions/application-customer-1.1_invalid.yaml")!!.openStream())
                val afterMigration = yamlMapper.readTree(configFile.inputStream())
                assertEquals(beforeMigration, afterMigration)
            }
    }

}
