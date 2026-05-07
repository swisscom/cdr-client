package com.swisscom.health.des.cdr.client

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Utility object responsible for applying necessary upgrade steps to the external SpringBoot configuration file
 * to ensure it is compatible with the current version of the application. Upgrade steps encapsulate the logic
 * for upgrading from one specific version of the configuration to the next. The upgrade process is designed to
 * be idempotent. If any upgrade step fails, the process stops, the cause is logged, and an exception is thrown,
 * which causes the service process to exit.
 */
object ConfigUpgrade {

    /**
     * The list of [upgrade steps][ConfigUpgradeStep] to be applied.
     *
     * The order matters!
     */
    @JvmStatic
    private val UPGRADE_STEPS: List<ConfigUpgradeStep> = listOf(
        ConfigUpgradeStep.V10ToV11,
        ConfigUpgradeStep.V11ToV12
    )

    @JvmStatic
    private val YAML_MAPPER: YAMLMapper =
        YAMLMapper.Builder(
            YAMLMapper(
                YAMLFactory()
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            )
        ).run {
            addModule(kotlinModule())
            build()
                .apply { setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE) }
        }

    /**
     * Applies all known upgrade steps to the external SpringBoot configuration file at [configLocation].
     * The upgrade steps assume the configuration file is in YAML format. Other formats are not supported.
     *
     * @param configLocation path to the external SpringBoot configuration file
     * @return the [UpgradeResult] of the upgrade process, containing the final configuration version and,
     * in case of failure, the reason for the failure
     */
    fun applyPendingUpgradeSteps(configLocation: Path): UpgradeResult {
        val upgradeStepResults: List<UpgradeStepResult> = UPGRADE_STEPS
            .fold(
                initial = emptyList(),
                operation = { acc: List<UpgradeStepResult>, configUpgradeStep: ConfigUpgradeStep ->
                    // if we encountered an upgrade error in a previous step, there is no point in trying further upgrades
                    if (acc.isNotEmpty() && acc.last() is UpgradeStepResult.Failure) {
                        acc
                    } else {
                        acc + configUpgradeStep.upgrade(
                            configRoot = if (acc.isEmpty()) {
                                // read original configuration file from disk
                                YAML_MAPPER.readTree(configLocation.inputStream(StandardOpenOption.READ)) as ObjectNode
                            } else {
                                // use version of the configuration containing changes from previous migration steps
                                acc.last().configRoot
                            }
                        )
                    }
                }
            )

        return when (val lastUpgradeStep = upgradeStepResults.last()) {
            is UpgradeStepResult.Success -> runCatching {
                persistConfig(configRoot = lastUpgradeStep.configRoot, configLocation = configLocation)
            }.fold(
                onSuccess = { _ -> UpgradeResult.Success(version = getConfigVersion(lastUpgradeStep.configRoot)) },
                onFailure = { t -> UpgradeResult.Failure(version = getConfigVersion(lastUpgradeStep.configRoot), reason = t.toString()) }
            )

            is UpgradeStepResult.Failure -> UpgradeResult.Failure(
                version = getConfigVersion(upgradeStepResults.last().configRoot),
                reason = lastUpgradeStep.reason
            )

            is UpgradeStepResult.NoStep -> UpgradeResult.AlreadyAtLatestVersion
        }
    }

    private fun persistConfig(configLocation: Path, configRoot: ObjectNode) {
        YAML_MAPPER.writeValue(configLocation.outputStream().writer(), configRoot)
    }
}

sealed class UpgradeResult {
    object AlreadyAtLatestVersion : UpgradeResult()
    data class Success(val version: Version) : UpgradeResult()
    data class Failure(val version: Version, val reason: String) : UpgradeResult()
}


@JvmInline
value class Version(val value: String)

fun getConfigVersion(configRoot: ObjectNode): Version =
    Version(configRoot[PROPERTY_NAME_VERSION]?.asText() ?: "1.0") // the first version of the configuration had no version property

const val PROPERTY_NAME_VERSION = "version"
