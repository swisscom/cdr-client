package com.swisscom.health.des.cdr.client.handler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import io.micrometer.tracing.Tracer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.origin.Origin
import org.springframework.boot.origin.OriginLookup
import org.springframework.boot.origin.PropertySourceOrigin
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.core.io.WritableResource
import org.springframework.stereotype.Service
import java.util.Properties

@Service
@ConditionalOnProperty(prefix = "client.idp-credentials", name = ["renew-credential-at-startup"], havingValue = "true")
class ClientSecretRenewalService(
    private val context: ConfigurableApplicationContext,
    private val cdrApiClient: CdrApiClient,
    private val tracer: Tracer
) {

    fun renewClientSecret(): RenewClientSecretResult =
        runCatching {
            findSecretOrigin()
        }.mapCatching { origin ->
            origin.fileBackedResource
        }.mapCatching { resource: Resource ->
            resource.writeableResource
        }.mapCatching { writeableResource: WritableResource ->
            // make API call lazy so it only happens after it is confirmed that the text origin is either a yaml or properties file
            val clientSecret: String by lazy(LazyThreadSafetyMode.NONE) { getNewSecret() }

            when (writeableResource.fileTypeFromExtension) {
                FileType.YAML -> updateYamlSource(writeableResource, clientSecret)
                FileType.PROPERTIES -> updatePropertySource(writeableResource, clientSecret)
            }
            writeableResource
        }.fold(
            onSuccess = { updatedResource: WritableResource -> RenewClientSecretResult.Success(updatedResource) },
            onFailure = { error: Throwable -> RenewClientSecretResult.RenewError(error) }
        )

    /**
     * Currently it is not possible to determine the "effective origin" of a property value based on the precedence rules
     * for property sources in SpringBoot. But it might be in the future: [ticket](https://github.com/spring-projects/spring-boot/issues/21613)
     *
     * As we cannot determine the effective origin and then check whether it is an updatable text resource we search all
     * available property sources for the client secret property and fail if we find more than one origin or none at all.
     */
    private fun findSecretOrigin(): Origin {
        @Suppress("UNCHECKED_CAST")
        val clientCredentialOrigins = context.environment.propertySources
            // at the time of writing there exist only OriginLookup<String> implementations on the classpath
            .mapNotNull { if (it is OriginLookup<*>) it as OriginLookup<String> else null }
            .mapNotNull { it.getOrigin(CLIENT_SECRET_PROPERTY_PATH) }
            // if configuration files are added via the `spring.config.additional-local` property, then a property from an additional,
            // location ends up in two origins, a property source origin that encapsulates the actual origin, and that origin directly.
            .map { if (it is PropertySourceOrigin) it.origin else it }
            .toSet()

        when {
            clientCredentialOrigins.isEmpty() ->
                error("No origin found for client secret `$CLIENT_SECRET_PROPERTY_PATH`")

            clientCredentialOrigins.size > 1 ->
                error(
                    "Multiple origins found for client secret `$CLIENT_SECRET_PROPERTY_PATH`: '$clientCredentialOrigins'"
                )

            else -> return clientCredentialOrigins.first()
        }
    }

    private val Origin.fileBackedResource: Resource
        get() =
            when (this) {
                is TextResourceOrigin -> this.resource
                else -> error("Don't know how to get file resource for origin type: '${this::class.qualifiedName}'")
            }

    private val Resource.writeableResource: WritableResource
        get() = FileSystemResource(this.file).apply {
            require(isWritable) { "Resource is not writable: '$this'" }
        }

    private val Resource.fileTypeFromExtension: FileType
        get() =
            when (this.file.extension) {
                "yml", "yaml" -> FileType.YAML
                "properties" -> FileType.PROPERTIES
                else -> error("Don't know file type for extension: '${this.file.extension}'; resource: '$this'")
            }

    private fun updateYamlSource(yamlResource: WritableResource, newSecret: String): Unit =
        YAMLMapper().run {
            val yamlNode: JsonNode = readTree(yamlResource.inputStream)
            (yamlNode.get(CLIENT_PROPERTY).get(IDP_CREDENTIALS_PROPERTY) as ObjectNode).apply {
                put(CLIENT_SECRET_PROPERTY, newSecret)
            }
            writeValue(yamlResource.outputStream.writer(), yamlNode)
        }

    private fun updatePropertySource(propertiesResource: WritableResource, newSecret: String): Unit =
        Properties().run {
            load(propertiesResource.inputStream)
            setProperty(CLIENT_SECRET_PROPERTY_PATH, newSecret)
            store(propertiesResource.outputStream.writer(), null)
        }

    private fun getNewSecret(): String =
        cdrApiClient.renewClientCredential(
            traceId = tracer.currentSpan()?.context()?.traceId() ?: ""
        ).run {
            when (this) {
                is CdrApiClient.RenewClientSecretResult.Success -> this.clientSecret
                is CdrApiClient.RenewClientSecretResult.RenewHttpErrorResponse -> error("http error; status code: $code")
                is CdrApiClient.RenewClientSecretResult.RenewError -> throw this.cause
            }
        }

    private enum class FileType {
        YAML,
        PROPERTIES
    }

    companion object {
        const val CLIENT_PROPERTY = "client"
        const val IDP_CREDENTIALS_PROPERTY = "idp-credentials"
        const val CLIENT_SECRET_PROPERTY = "client-secret"
        const val CLIENT_SECRET_PROPERTY_PATH = "$CLIENT_PROPERTY.$IDP_CREDENTIALS_PROPERTY.$CLIENT_SECRET_PROPERTY"
    }

    sealed class RenewClientSecretResult {
        data class Success(val secretSource: Resource) : RenewClientSecretResult()
        data class RenewError(val cause: Throwable) : RenewClientSecretResult()
    }
}
