package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.config.CdrClientConfig.RetryTemplateConfig
import com.swisscom.health.des.cdr.client.xml.DocumentType
import org.springframework.util.unit.DataSize
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.reflect.KClass

/*
 * BEGIN - Spring Configuration -> Configuration DTOs
 */
internal fun CdrClientConfig.toDto(): DTOs.CdrClientConfig {
    fun List<CdrClientConfig.Connector>.toDto(): List<DTOs.CdrClientConfig.Connector> = map { it.toDto() }
    fun FileBusyTestStrategyProperty.toDto(): DTOs.CdrClientConfig.FileBusyTestStrategy =
        DTOs.CdrClientConfig.FileBusyTestStrategy.entries.first { it.name == strategy.name }

    return DTOs.CdrClientConfig(
        fileSynchronizationEnabled = fileSynchronizationEnabled.value,
        customer = customer.toDto(),
        cdrApi = cdrApi.toDto(),
        filesInProgressCacheSize = filesInProgressCacheSize.toString(),
        idpCredentials = idpCredentials.toDto(),
        idpEndpoint = idpEndpoint,
        localFolder = localFolder.path.absolutePathString(),
        pullThreadPoolSize = pullThreadPoolSize,
        pushThreadPoolSize = pushThreadPoolSize,
        retryDelay = retryDelay,
        scheduleDelay = scheduleDelay,
        credentialApi = credentialApi.toDto(),
        retryTemplate = retryTemplate.toDto(),
        fileBusyTestInterval = fileBusyTestInterval,
        fileBusyTestTimeout = fileBusyTestTimeout,
        fileBusyTestStrategy = fileBusyTestStrategy.toDto(),
    )
}

internal fun CdrClientConfig.Connector.toDto(): DTOs.CdrClientConfig.Connector {
    fun Map<DocumentType, CdrClientConfig.Connector.DocTypeFolders>.toDto():
            Map<DTOs.CdrClientConfig.DocumentType, DTOs.CdrClientConfig.Connector.DocTypeFolders> {
        return map { (key, value) ->
            DTOs.CdrClientConfig.DocumentType.entries.first { it.name == key.name } to DTOs.CdrClientConfig.Connector.DocTypeFolders(
                sourceFolder = value.sourceFolder?.absolutePathString(),
                targetFolder = value.targetFolder?.absolutePathString(),
            )
        }.toMap()
    }

    return DTOs.CdrClientConfig.Connector(
        connectorId = connectorId,
        targetFolder = targetFolder.absolutePathString(),
        sourceFolder = sourceFolder.absolutePathString(),
        contentType = contentType,
        sourceArchiveEnabled = sourceArchiveEnabled,
        sourceArchiveFolder = sourceArchiveFolder.absolutePathString(),
        sourceErrorFolder = sourceErrorFolder?.absolutePathString(),
        mode = DTOs.CdrClientConfig.Mode.entries.first { it.name == mode.name },
        docTypeFolders = docTypeFolders.toDto(),
    )
}

internal fun Endpoint.toDto(): DTOs.CdrClientConfig.Endpoint =
    DTOs.CdrClientConfig.Endpoint(
        scheme = scheme,
        host = host.fqdn,
        port = port,
        basePath = basePath
    )

internal fun IdpCredentials.toDto(): DTOs.CdrClientConfig.IdpCredentials =
    DTOs.CdrClientConfig.IdpCredentials(
        tenantId = tenantId.id,
        clientId = clientId.id,
        clientSecret = clientSecret.value,
        scopes = scopes,
        renewCredential = renewCredential.value,
        maxCredentialAge = maxCredentialAge,
        lastCredentialRenewalTime = lastCredentialRenewalTime.instant,
    )

internal fun RetryTemplateConfig.toDto(): DTOs.CdrClientConfig.RetryTemplateConfig =
    DTOs.CdrClientConfig.RetryTemplateConfig(
        retries = retries,
        initialDelay = initialDelay,
        maxDelay = maxDelay,
        multiplier = multiplier
    )
/*
 * END - Spring Configuration -> Configuration DTOs
 */

/*
 * BEGIN - Configuration DTOs -> Spring Configuration
 */
internal fun DTOs.CdrClientConfig.toCdrClientConfig(): CdrClientConfig {
    fun List<DTOs.CdrClientConfig.Connector>.toCdrClientConfig(): MutableList<CdrClientConfig.Connector> = map { it.toCdrClientConfig() }.toMutableList()
    fun DTOs.CdrClientConfig.FileBusyTestStrategy.toCdrClientConfig(): FileBusyTestStrategyProperty = FileBusyTestStrategyProperty.valueOf(name)

    return CdrClientConfig(
        fileSynchronizationEnabled = if (fileSynchronizationEnabled) FileSynchronization.ENABLED else FileSynchronization.DISABLED,
        customer = Customer(customer.toCdrClientConfig()),
        cdrApi = cdrApi.toCdrClientConfig(CdrApi::class),
        filesInProgressCacheSize = DataSize.parse(filesInProgressCacheSize),
        idpCredentials = idpCredentials.toCdrClientConfig(),
        idpEndpoint = idpEndpoint,
        localFolder = TempDownloadDir(localFolder),
        pullThreadPoolSize = pullThreadPoolSize,
        pushThreadPoolSize = pushThreadPoolSize,
        retryDelay = retryDelay,
        scheduleDelay = scheduleDelay,
        credentialApi = credentialApi.toCdrClientConfig(CredentialApi::class),
        retryTemplate = retryTemplate.toCdrClientConfig(),
        fileBusyTestInterval = fileBusyTestInterval,
        fileBusyTestTimeout = fileBusyTestTimeout,
        fileBusyTestStrategy = fileBusyTestStrategy.toCdrClientConfig(),
    )
}

internal fun DTOs.CdrClientConfig.Connector.toCdrClientConfig(): CdrClientConfig.Connector {
    fun Map<DTOs.CdrClientConfig.DocumentType, DTOs.CdrClientConfig.Connector.DocTypeFolders>.toCdrClientConfig():
            Map<DocumentType, CdrClientConfig.Connector.DocTypeFolders> =
        map { (key, value) ->
            DocumentType.valueOf(key.name) to CdrClientConfig.Connector.DocTypeFolders(
                sourceFolder = if (value.sourceFolder != null) Path.of(value.sourceFolder!!) else null,
                targetFolder = if (value.targetFolder != null) Path.of(value.targetFolder!!) else null,
            )
        }.toMap()

    return CdrClientConfig.Connector(
        connectorId = connectorId,
        targetFolder = Path.of(targetFolder),
        sourceFolder = Path.of(sourceFolder),
        contentType = contentType,
        sourceArchiveEnabled = sourceArchiveEnabled,
        sourceArchiveFolder = Path.of(sourceArchiveFolder),
        sourceErrorFolder = if (sourceErrorFolder != null) Path.of(sourceErrorFolder!!) else null,
        mode = CdrClientConfig.Mode.valueOf(mode.name),
        docTypeFolders = docTypeFolders.toCdrClientConfig(),
    )
}

internal inline fun <reified T : Endpoint> DTOs.CdrClientConfig.Endpoint.toCdrClientConfig(type: KClass<T>): T =
    when (type) {
        CdrApi::class -> CdrApi(
            scheme = scheme,
            host = Host(host),
            port = port,
            basePath = basePath
        )

        CredentialApi::class -> CredentialApi(
            scheme = scheme,
            host = Host(host),
            port = port,
            basePath = basePath
        )

        else -> error("Unrecognized endpoint: $type")
    } as T


internal fun DTOs.CdrClientConfig.IdpCredentials.toCdrClientConfig(): IdpCredentials =
    IdpCredentials(
        tenantId = TenantId(tenantId),
        clientId = ClientId(clientId),
        clientSecret = ClientSecret(clientSecret),
        scopes = scopes,
        renewCredential = RenewCredential(renewCredential),
        maxCredentialAge = maxCredentialAge,
        lastCredentialRenewalTime = LastCredentialRenewalTime(lastCredentialRenewalTime),
    )

internal fun DTOs.CdrClientConfig.RetryTemplateConfig.toCdrClientConfig(): RetryTemplateConfig =
    RetryTemplateConfig(
        retries = retries,
        initialDelay = java.time.Duration.ofMillis(initialDelay.toMillis()),
        maxDelay = java.time.Duration.ofMillis(maxDelay.toMillis()),
        multiplier = multiplier
    )

/*
 * END - Configuration DTOs -> Spring Configuration
 */
