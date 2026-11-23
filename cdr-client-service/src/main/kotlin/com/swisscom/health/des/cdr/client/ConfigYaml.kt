package com.swisscom.health.des.cdr.client

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLFactory
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.dataformat.yaml.YAMLWriteFeature
import tools.jackson.module.kotlin.kotlinModule
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Reads and writes the external SpringBoot configuration file (YAML) before the SpringBoot application starts,
 * using the same formatting for every modification.
 */
internal object ConfigYaml {

    /** Shared by all writers of the configuration file so the YAML formatting stays consistent. */
    @JvmStatic
    val MAPPER: YAMLMapper =
        YAMLMapper.builder(
            YAMLFactory.builder()
                .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                .enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
                .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .build()
        )
            .addModule(kotlinModule { })
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .build()

    fun read(configFile: Path): ObjectNode =
        configFile.inputStream(StandardOpenOption.READ).use { MAPPER.readTree(it) as ObjectNode }

    fun write(configFile: Path, configRoot: ObjectNode): Unit =
        configFile.outputStream().use { os -> MAPPER.writeValue(os.writer(), configRoot) }
}
