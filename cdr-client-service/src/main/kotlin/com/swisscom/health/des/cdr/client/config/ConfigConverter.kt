@file:Suppress("TooManyFunctions")

package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DomainObjects
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
    fun List<Connector>.toDto(): List<DTOs.CdrClientConfig.Connector> = map { it.toDto() }
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
        retryTemplate = retryTemplate.toDto(),
        fileBusyTestInterval = fileBusyTestInterval,
        fileBusyTestTimeout = fileBusyTestTimeout,
        fileBusyTestStrategy = fileBusyTestStrategy.toDto(),
        proxyConfig = proxyConfig.toDto(),
        oldFileThreshold = oldFileThreshold,
        fileSystemCheckInterval = fileSystemCheckInterval,
    )
}

internal fun ProxyConfig.toDto(): DTOs.CdrClientConfig.ProxyConfig =
    DTOs.CdrClientConfig.ProxyConfig(
        url = url.value,
        username = username.value,
        password = if (password == ProxyPassword.NO_PASSWORD) password.value else ProxyPassword.MASKED_PASSWORD.value,
    )

internal fun Connector.toDto(): DTOs.CdrClientConfig.Connector {
    fun Map<DocumentType, Connector.DocTypeFolders>.toDto():
            Map<DTOs.CdrClientConfig.DocumentType, DTOs.CdrClientConfig.Connector.DocTypeFolders> {
        return map { (key, value) ->
            DTOs.CdrClientConfig.DocumentType.entries.first { it.name == key.name } to DTOs.CdrClientConfig.Connector.DocTypeFolders(
                sourceFolder = value.sourceFolder?.toString(),
                targetFolder = value.targetFolder?.toString(),
            )
        }.toMap()
    }

    return DTOs.CdrClientConfig.Connector(
        connectorId = connectorId.id,
        targetFolder = targetFolder.toString(),
        sourceFolder = sourceFolder.toString(),
        contentType = contentType,
        sourceArchiveEnabled = sourceArchiveEnabled,
        sourceArchiveFolder = sourceArchiveFolder?.toString(),
        sourceErrorFolder = sourceErrorFolder?.toString(),
        mode = DTOs.CdrClientConfig.Mode.entries.first { it.name == mode.name },
        docTypeFolders = effectiveDocTypeFolders.toDto(),
    )
}

internal fun Endpoint.toDto(): DomainObjects.ApiEndpoint =
    DomainObjects.ApiEndpoint.fromEndpointParts(protocol = scheme, port = port, host = host.fqdn)

internal fun IdpCredentials.toDto(): DTOs.CdrClientConfig.IdpCredentials =
    DTOs.CdrClientConfig.IdpCredentials(
        tenantId = DomainObjects.TenantId.fromTenantId(tenantId.id),
        clientId = clientId.id,
        clientSecret = if (clientSecret == ClientSecret.NO_SECRET) clientSecret.value else ClientSecret.MASKED_SECRET.value,
        scope = DomainObjects.OAuthScope.fromScope(scope.scope),
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
    fun List<DTOs.CdrClientConfig.Connector>.toCdrClientConfig(): MutableList<Connector> = map { it.toCdrClientConfig() }.toMutableList()
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
        // the credential-api endpoint is always the same as the cdr-api endpoint, only the path differs
        credentialApi = cdrApi.toCdrClientConfig(CredentialApi::class),
        retryTemplate = retryTemplate.toCdrClientConfig(),
        fileBusyTestInterval = fileBusyTestInterval,
        fileBusyTestTimeout = fileBusyTestTimeout,
        fileBusyTestStrategy = fileBusyTestStrategy.toCdrClientConfig(),
        proxyConfig = proxyConfig.toCdrClientConfig(),
        oldFileThreshold = oldFileThreshold,
        fileSystemCheckInterval = fileSystemCheckInterval,
    )
}

internal fun DTOs.CdrClientConfig.ProxyConfig.toCdrClientConfig(): ProxyConfig {
    return if (url.isBlank()) {
        ProxyConfig(
            url = ProxyUrl(EMPTY_STRING),
            username = ProxyUsername(EMPTY_STRING),
            password = ProxyPassword(EMPTY_STRING),
        )
    } else {
        ProxyConfig(
            url = ProxyUrl(url),
            username = ProxyUsername(username),
            password = ProxyPassword(password),
        )
    }
}

internal fun DTOs.CdrClientConfig.Connector.toCdrClientConfig(): Connector {
    fun Map<DTOs.CdrClientConfig.DocumentType, DTOs.CdrClientConfig.Connector.DocTypeFolders>.toCdrClientConfig():
            Map<DocumentType, Connector.DocTypeFolders> =
        map { (key, value) ->
            DocumentType.valueOf(key.name) to Connector.DocTypeFolders(
                sourceFolder = if (value.sourceFolder != null) Path.of(value.sourceFolder!!) else null,
                targetFolder = if (value.targetFolder != null) Path.of(value.targetFolder!!) else null,
            )
        }.toMap()

    return Connector(
        connectorId = ConnectorId(connectorId),
        targetFolder = Path.of(targetFolder),
        sourceFolder = Path.of(sourceFolder),
        contentType = contentType,
        sourceArchiveEnabled = sourceArchiveEnabled,
        sourceArchiveFolder = if (sourceArchiveFolder != null) Path.of(sourceArchiveFolder!!) else null,
        sourceErrorFolder = if (sourceErrorFolder != null) Path.of(sourceErrorFolder!!) else null,
        mode = CdrClientConfig.Mode.valueOf(mode.name),
        docTypeFolders = docTypeFolders.toCdrClientConfig(),
    )
}

internal inline fun <reified T : Endpoint> DomainObjects.ApiEndpoint.toCdrClientConfig(type: KClass<T>): T =
    when (type) {
        CdrApi::class -> CdrApi(
            scheme = protocol,
            host = Host(host),
            port = port,
            basePath = "api/documents"
        )

        CredentialApi::class -> CredentialApi(
            scheme = protocol,
            host = Host(host),
            port = port,
            basePath = "api/client-credentials"
        )

        else -> error("Unrecognized endpoint: $type")
    } as T


internal fun DTOs.CdrClientConfig.IdpCredentials.toCdrClientConfig(): IdpCredentials =
    IdpCredentials(
        tenantId = TenantId(tenantId.tenantId),
        clientId = ClientId(clientId),
        clientSecret = ClientSecret(clientSecret),
        scope = Scope(scope.scope),
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
