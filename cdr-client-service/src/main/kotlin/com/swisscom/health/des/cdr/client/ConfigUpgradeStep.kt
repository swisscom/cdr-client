package com.swisscom.health.des.cdr.client

import tools.jackson.databind.node.ObjectNode

/**
 * An atomic upgrade step from a source version to a target version of the configuration.
 * If any part of the upgrade step fails or if no upgrade is performed, the original input
 * object tree is returned as the [UpgradeStepResult.configRoot].
 */
sealed class ConfigUpgradeStep(
    private val fromVersion: Version,
    private val toVersion: Version,
) {

    private companion object {
        const val PROPERTY_NAME_CLIENT = "client"
    }

    protected abstract fun upgradeImpl(configRoot: ObjectNode): UpgradeStepResult

    /**
     * Upgrades the configuration if the current configuration version matches the expected [fromVersion]
     * of this upgrade step. If the version matches, the [upgradeImpl] method is called to perform the
     * actual upgrade. If the upgrade is successful, the configuration version is updated to the
     * [toVersion] of this upgrade step. If the version does not match, a [UpgradeStepResult.NoStep] is
     * returned, indicating that this upgrade step is not applicable to the current configuration version.
     *
     * @param configRoot the root node of the configuration to be upgraded
     * @return the result of the upgrade step, which can be a success, failure, or no step if the version
     * does not match
     */
    fun upgrade(configRoot: ObjectNode): UpgradeStepResult =
        if (fromVersion == getConfigVersion(configRoot)) {
            val newObjectTree: ObjectNode = configRoot.deepCopy()
            upgradeImpl(configRoot = newObjectTree).let { result ->
                when (result) {
                    is UpgradeStepResult.Success -> result.also {
                        result.configRoot.put(PROPERTY_NAME_VERSION, toVersion.value)
                    }

                    is UpgradeStepResult.Failure -> result.copy(configRoot = configRoot) // reset config to the version before we failed
                    is UpgradeStepResult.NoStep -> result.copy(configRoot = configRoot) // no step would normally mean no alterations, but let's be safe
                }
            }
        } else {
            UpgradeStepResult.NoStep(configRoot = configRoot)
        }


    /**
     * Add new properties:
     * * `client.proxy-url`; default value is an empty string
     */
    object V10ToV11 : ConfigUpgradeStep(fromVersion = Version("1.0"), toVersion = Version("1.1")) {

        private const val PROPERTY_NAME_PROXY_URL = "proxy-url"

        override fun upgradeImpl(configRoot: ObjectNode): UpgradeStepResult =
            configRoot[PROPERTY_NAME_CLIENT]
                ?.let { it as ObjectNode }
                ?.let { clientNode: ObjectNode ->
                    clientNode.put(PROPERTY_NAME_PROXY_URL, "")
                    UpgradeStepResult.Success(
                        configRoot = configRoot
                    )
                }
                ?: UpgradeStepResult.Failure(
                    configRoot = configRoot,
                    reason = "Failed to look up 'client' object node"
                )

    }

    /**
     * Remove property:
     * * `client.proxy-url`
     *
     * and replace it with:
     *
     * * `client.proxy-config.url` (re-apply the value of `client.proxy-url`)
     * * `client.proxy-config.username`
     * * `client.proxy-config.password`
     */
    object V11ToV12 : ConfigUpgradeStep(fromVersion = Version("1.1"), toVersion = Version("1.2")) {

        private const val PROPERTY_NAME_PROXY_URL = "proxy-url"
        private const val PROPERTY_NAME_PROXY_CONFIG = "proxy-config"
        private const val PROPERTY_NAME_PROXY_CONFIG_URL = "url"
        private const val PROPERTY_NAME_PROXY_CONFIG_USERNAME = "username"
        private const val PROPERTY_NAME_PROXY_CONFIG_PASSWORD = "password"

        override fun upgradeImpl(configRoot: ObjectNode): UpgradeStepResult =
            configRoot.at("/$PROPERTY_NAME_CLIENT/$PROPERTY_NAME_PROXY_URL")
                .takeUnless { it.isMissingNode }
                ?.stringValue()
                ?.let { proxyUrl: String ->
                    configRoot[PROPERTY_NAME_CLIENT]
                        ?.let { it as ObjectNode }
                        ?.let { clientNode: ObjectNode ->
                            clientNode.remove(PROPERTY_NAME_PROXY_URL)
                            clientNode.putObject(PROPERTY_NAME_PROXY_CONFIG).run {
                                put(PROPERTY_NAME_PROXY_CONFIG_URL, proxyUrl)
                                put(PROPERTY_NAME_PROXY_CONFIG_USERNAME, "")
                                put(PROPERTY_NAME_PROXY_CONFIG_PASSWORD, "")
                            }
                            UpgradeStepResult.Success(
                                configRoot = configRoot
                            )
                        }
                        ?: UpgradeStepResult.Failure(
                            configRoot = configRoot,
                            reason = "Failed to look up '$PROPERTY_NAME_CLIENT' object node"
                        )
                }
                ?: UpgradeStepResult.Failure(
                    configRoot = configRoot,
                    reason = "Failed to look up '/$PROPERTY_NAME_CLIENT/$PROPERTY_NAME_PROXY_URL' object node"
                )
    }

    /**
     * Replace the legacy dev and staging IdP scopes in `client.idp-credentials.scope` with their B2C equivalents.
     * Any other scope value (e.g. production) is left untouched.
     */
    object V12ToV13 : ConfigUpgradeStep(fromVersion = Version("1.2"), toVersion = Version("1.3")) {

        private const val PROPERTY_NAME_IDP_CREDENTIALS = "idp-credentials"
        private const val PROPERTY_NAME_SCOPE = "scope"
        private const val IDP_CREDENTIALS_PATH = "$PROPERTY_NAME_CLIENT.$PROPERTY_NAME_IDP_CREDENTIALS"
        private const val SCOPE_PATH = "$IDP_CREDENTIALS_PATH.$PROPERTY_NAME_SCOPE"

        private val LEGACY_TO_CURRENT_SCOPE: Map<String, String> = mapOf(
            "https://dev.identity.health.swisscom.ch/CdrApi/.default" to "https://dev-b2c.health.swisscom.ch/CdrApi/.default",
            "https://tst.identity.health.swisscom.ch/CdrApi/.default" to "https://tst-b2c.health.swisscom.ch/CdrApi/.default",
        )

        override fun upgradeImpl(configRoot: ObjectNode): UpgradeStepResult {
            val idpCredentialsNode = (configRoot[PROPERTY_NAME_CLIENT] as? ObjectNode)
                ?.get(PROPERTY_NAME_IDP_CREDENTIALS) as? ObjectNode
                ?: return UpgradeStepResult.Failure(
                    configRoot = configRoot,
                    reason = "Failed to look up '$IDP_CREDENTIALS_PATH' object node"
                )

            return idpCredentialsNode[PROPERTY_NAME_SCOPE]
                ?.takeIf { it.isString }
                ?.asString()
                ?.let { currentScope ->
                    LEGACY_TO_CURRENT_SCOPE[currentScope]?.let { idpCredentialsNode.put(PROPERTY_NAME_SCOPE, it) }
                    UpgradeStepResult.Success(configRoot = configRoot)
                }
                ?: UpgradeStepResult.Failure(
                    configRoot = configRoot,
                    reason = "Failed to look up '$SCOPE_PATH' string value"
                )
        }
    }
}

sealed interface UpgradeStepResult {
    val configRoot: ObjectNode

    data class NoStep(override val configRoot: ObjectNode) : UpgradeStepResult
    data class Success(override val configRoot: ObjectNode) : UpgradeStepResult
    data class Failure(override val configRoot: ObjectNode, val reason: String) : UpgradeStepResult
}
