package com.swisscom.health.des.cdr.client.handler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import com.swisscom.health.des.cdr.client.config.PropertyNameAware
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.origin.Origin
import org.springframework.boot.origin.OriginLookup
import org.springframework.boot.origin.PropertySourceOrigin
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.core.io.WritableResource
import org.springframework.stereotype.Service
import kotlin.reflect.full.memberProperties

private val logger = KotlinLogging.logger {}

@Service
internal class ConfigurationWriter(
    private val currentConfig: CdrClientConfig,
    private val context: ConfigurableApplicationContext,
) {

    sealed interface ConfigLookupResult {
        object NotFound : ConfigLookupResult
        object NotWritable : ConfigLookupResult
        object Writable : ConfigLookupResult
    }

    fun isWritableConfigurationItem(propertyPath: String): ConfigLookupResult =
        collectUpdatableConfigurationItems(currentConfig, currentConfig)
            .firstOrNull { it.propertyPath == propertyPath }
            .let { updatableConfigItem: UpdatableConfigurationItem? ->
                return when (updatableConfigItem) {
                    null -> ConfigLookupResult.NotFound
                    is UpdatableConfigurationItem.UnknownSourceConfigurationItem -> ConfigLookupResult.NotWritable
                    is UpdatableConfigurationItem.WritableResourceConfigurationItem -> ConfigLookupResult.Writable
                }
            }

    sealed interface Result {
        object Success : Result
        data class Failure(val errors: Map<String, Any>) : Result
    }

    fun updateClientServiceConfiguration(newConfig: CdrClientConfig): Result = runCatching {
        logger.trace { "New CDR client config: '$newConfig'" }

        validate(newConfig).let { validationErrors: Map<String, Any> ->
            if (validationErrors.isNotEmpty()) {
                Result.Failure(validationErrors)
            } else {
                val updatableItems: List<UpdatableConfigurationItem> = collectUpdatableConfigurationItems(currentConfig, newConfig)
                logger.trace { "Updatable configuration items found: '$updatableItems'" }

                updatableItems
                    .filter { updatableConfigItem ->
                        updatableConfigItem.newValue != updatableConfigItem.currentValue
                    }.filter { changedConfigItem ->
                        (changedConfigItem is UpdatableConfigurationItem.WritableResourceConfigurationItem)
                            .also { isWritable ->
                                if (!isWritable) {
                                    // Not really cool as we will create partial updates. But as we do not have a rollback strategy (yet),
                                    // the update might be partial anyway, if we do not fail on the first changed property.
                                    // TODO: Implement a rollback strategy!
                                    logger.warn {
                                        "Configuration item '${changedConfigItem.propertyPath}' was changed, but is not writable! " +
                                                "Skipping update for this item!"
                                    }
                                }
                            }
                    }.map { changedWritableConfigItem ->
                        changedWritableConfigItem as UpdatableConfigurationItem.WritableResourceConfigurationItem
                    }.forEach { changedWritableConfigItem: UpdatableConfigurationItem.WritableResourceConfigurationItem ->
                        logger.info { "Updating configuration item: '${changedWritableConfigItem.propertyPath}'" }
                        logger.trace { "Configuration item details: '$changedWritableConfigItem'" }
                        when (changedWritableConfigItem.writableResource.fileTypeFromExtension) {
                            FileType.YAML -> updateYamlSource(changedWritableConfigItem)
                            FileType.PROPERTIES -> TODO() //updatePropertySource(changedConfigItem)
                            // TODO: change to error once we have a rollback strategy
                            null -> logger.warn { "Property source has unknown file extension: ${changedWritableConfigItem.writableResource.filename}" }
                        }
                    }

                Result.Success
            }
        }
    }.getOrElse { exception ->
        Result.Failure(mapOf("error" to exception)).also {
            logger.error(exception) { "Failed to update configuration items" }
        }
    }

    private fun updateYamlSource(changedConfigItem: UpdatableConfigurationItem.WritableResourceConfigurationItem): Unit =
        YAMLMapper().run {
            val yamlNode: JsonNode = readTree(changedConfigItem.writableResource.inputStream)
            var tmpNode = yamlNode as ObjectNode
            val remainingNodeNames = ArrayDeque(changedConfigItem.propertyPath.split("."))
            val toBeUpdatedNodeName = remainingNodeNames.removeLast()
            while (remainingNodeNames.isNotEmpty()) {
                tmpNode = tmpNode.get(remainingNodeNames.removeFirst()) as ObjectNode
            }
            tmpNode.apply {
                when (changedConfigItem.newValue) {
                    is Collection<*> -> TODO() // need to handle the collection of connectors!
                    else -> {
                        // we have to go to string first as the value might be one a value class, Duration, DataSize, etc., anything that
                        // SpringBoot's unmarshalling magic creates from what was originally a string
                        val stringValue = changedConfigItem.newValue.toString()
                        val storedValue = runCatching { stringValue.toBooleanStrict().also { put(toBeUpdatedNodeName, it) } }
                            .recoverCatching { stringValue.toInt().also { put(toBeUpdatedNodeName, it) } }
                            .recoverCatching { stringValue.toFloat().also { put(toBeUpdatedNodeName, it) } }
                            .getOrElse { stringValue.also { put(toBeUpdatedNodeName, it) } }
                        logger.debug { "set '${changedConfigItem.propertyPath}' to '$storedValue' as type '${storedValue::class}'" }
                    }
                }
            }

            writeValue(changedConfigItem.writableResource.outputStream.writer(), yamlNode)
        }

//    private fun updatePropertySource(changedConfigItem: UpdatableConfigurationItem): Unit =
//        Properties().run {
//            load(propertiesResource.inputStream)
//            setProperty(CLIENT_SECRET_PROPERTY_PATH, newSecret)
//            store(propertiesResource.outputStream.writer(), null)
//        }

    private fun collectUpdatableConfigurationItems(
        currentConfigItemValue: PropertyNameAware, newConfigItemValue: PropertyNameAware,
    ): List<UpdatableConfigurationItem> {

        data class ConfigItemContainer(
            val current: PropertyNameAware,
            val new: PropertyNameAware,
            val propertyPath: List<String>,
        )

        tailrec fun walkConfigurationItemTree(
            configItems: ArrayDeque<ConfigItemContainer>,
            updatableConfigItemCollector: MutableList<UpdatableConfigurationItem>,
        ) {
            if (configItems.isEmpty()) {
                // the stack is empty; we have walked the entire tree of updatable configuration items and are done
                return
            }

            // pop the most recently added configuration node from the stack
            val (currentConfigItem, newConfigItem, propertyPath) = configItems.removeLast()

            // some sanity checks to try and make sure the object tree of the new and old configuration is the same
            require(currentConfigItem::class == newConfigItem::class) {
                "Current and new configuration items must be of the same type: '${currentConfigItem::class}' vs '${newConfigItem::class}'"
            }
            require(currentConfigItem.propertyName == newConfigItem.propertyName) {
                "Current and new configuration items must have the same property name: '${currentConfigItem.propertyName}' vs '${newConfigItem.propertyName}'"
            }

            // get the updatable configuration item children from both configuration object tree nodes, to gather both the before and after values
            val propertyNameAwareChildren: List<Pair<PropertyNameAware, PropertyNameAware>> =
                getPropertyNameAwareChildren(currentConfigItem).zip(getPropertyNameAwareChildren(newConfigItem))

            if (propertyNameAwareChildren.isEmpty()) {
                // if we have reached a leaf node in the tree of updatable configuration items (no more updatable child nodes),
                // we try to resolve its property source and store the node for potential updates
                getPropertySource(propertyPath.joinToString(separator = "."))?.let { propertySource ->
                    logger.debug { "Writable resource found for configuration item '${propertyPath.joinToString(separator = ".")}': '$propertySource'" }
                    updatableConfigItemCollector.add(
                        UpdatableConfigurationItem.WritableResourceConfigurationItem(
                            propertyPath = propertyPath.joinToString(separator = "."),
                            writableResource = propertySource,
                            currentValue = currentConfigItem,
                            newValue = newConfigItem,
                        )
                    )

                } ?: run {
                    logger.debug { "No writable resource found for configuration item '${propertyPath.joinToString(separator = ".")}'" }
                    updatableConfigItemCollector.add(
                        UpdatableConfigurationItem.UnknownSourceConfigurationItem(
                            propertyPath = propertyPath.joinToString(separator = "."),
                            currentValue = currentConfigItem,
                            newValue = newConfigItem,
                        )
                    )
                }
            } else {
                // if more updatable child nodes are present, then push all updatable child nodes onto the stack
                propertyNameAwareChildren.forEach { (currentChild, newChild) ->
                    val propertyNameAwarePair = ConfigItemContainer(
                        current = currentChild,
                        new = newChild,
                        propertyPath = propertyPath + currentChild.propertyName
                    )
                    configItems.add(propertyNameAwarePair)
                }
            }

            // continue descending into the tree of updatable configuration items until we hit a leaf node
            walkConfigurationItemTree(
                configItems = configItems,
                updatableConfigItemCollector = updatableConfigItemCollector,
            )
        }

        val updatableConfigItems = mutableListOf<UpdatableConfigurationItem>()
        // start walking the tree of updatable configuration items, starting with the root node
        walkConfigurationItemTree(
            configItems = ArrayDeque(
                listOf(
                    ConfigItemContainer(
                        current = currentConfigItemValue,
                        new = newConfigItemValue,
                        propertyPath = listOf(currentConfigItemValue.propertyName),
                    )
                )
            ),
            updatableConfigItemCollector = updatableConfigItems,
        )

        return updatableConfigItems as List<UpdatableConfigurationItem>
    }

    private fun getPropertySource(propertyPath: String): WritableResource? {
        val origin: WritableResource? = runCatching {
            findPropertyOrigin(propertyPath)
        }.mapCatching { origin ->
            origin?.fileBackedResource
        }.mapCatching { resource: Resource? ->
            resource?.writeableResource
        }.getOrThrow()

        return origin
    }

    /**
     * Currently it is not possible to determine the "effective origin" of a property value based on the precedence rules
     * for property sources in SpringBoot. But it might be in the future:
     * [SpringBoot Feature Request](https://github.com/spring-projects/spring-boot/issues/21613)
     *
     * As we cannot determine the effective origin and then check whether it is an updatable text resource, we search all
     * available property sources for the client secret property and fail if we find no origin. The local development setup
     * may report more than one origin as it uses multiple profiles. If more then once origin is found we log a warning
     * and return the first one from the list of origins.
     */
    private fun findPropertyOrigin(propertyPath: String): Origin? {
        @Suppress("UNCHECKED_CAST")
        val origins = context.environment.propertySources
            // at the time of writing, there exist only OriginLookup<String> implementations on the classpath
            .mapNotNull { if (it is OriginLookup<*>) it as OriginLookup<String> else null }
            .mapNotNull { it.getOrigin(propertyPath) }
            // if configuration files are added via the `spring.config.additional-local` property, then a property from an additional
            // location ends up in two origins, a property source origin that encapsulates the actual origin, and that origin directly.
            .map { if (it is PropertySourceOrigin) it.origin else it }
            .toSet()

        when {
            origins.isEmpty() ->
                logger.debug { "No origin found for property `$propertyPath`" }

            origins.size > 1 -> {
                logger.warn { "Multiple origins found for property `$propertyPath`: '$origins'; picking first one, your mileage might vary!" }
            }
        }

        return origins.firstOrNull()
    }

    private fun getPropertyNameAwareChildren(value: PropertyNameAware): List<PropertyNameAware> =
        value::class.memberProperties
            .mapNotNull { kProperty -> kProperty.call(value) }
            .filter { propertyValue -> propertyValue is PropertyNameAware }
            .map { propertyValue -> propertyValue as PropertyNameAware }
            .toList()

    /**
     * TODO: Implement!
     */
    private fun validate(config: CdrClientConfig): Map<String, Any> {
        logger.debug { "config to validate: '$config'" }
//        return mapOf("fakeError1" to "fake Error 1 Value", "fakeError2" to "fake Error 2 Value")
        return emptyMap()
    }

    private val Origin.fileBackedResource: Resource?
        get() =
            when (this) {
                is TextResourceOrigin -> this.resource
                else -> null.also { logger.warn { "Don't know how to get file resource for origin type: '${this::class.qualifiedName}'" } }
            }

    private val Resource.writeableResource: WritableResource?
        get() = FileSystemResource(this.file).run {
            when (isWritable) {
                true -> this.also { logger.debug { "Resource is writable: '$this'" } }
                false -> null.also { logger.warn { "Resource is not writable: '$this'" } }
            }
        }

    private val Resource.fileTypeFromExtension: FileType?
        get() =
            when (this.file.extension) {
                "yml", "yaml" -> FileType.YAML
                "properties" -> FileType.PROPERTIES
                else -> null.also { logger.warn { "Don't know file type for extension: '${this.file.extension}'; resource: '$this'" } }
            }

    private enum class FileType {
        YAML,
        PROPERTIES
    }

    internal sealed interface UpdatableConfigurationItem {
        val propertyPath: String
        val currentValue: Any
        val newValue: Any

        data class WritableResourceConfigurationItem(
            override val propertyPath: String,
            override val currentValue: Any,
            override val newValue: Any,
            val writableResource: WritableResource,
        ) : UpdatableConfigurationItem

        data class UnknownSourceConfigurationItem(
            override val propertyPath: String,
            override val currentValue: Any,
            override val newValue: Any,
        ) : UpdatableConfigurationItem
    }
}
