@file:Suppress("TooManyFunctions")

package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.common.Constants.EMPTY_STRING
import com.swisscom.health.des.cdr.client.common.DocumentType
import com.swisscom.health.des.cdr.client.common.DomainObjects
import com.swisscom.health.des.cdr.client.config.CdrClientConfig.RetryTemplateConfig
import org.springframework.util.unit.DataSize
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.reflect.KClass
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig as CdrClientConfigDto
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig.Connector as ConnectorDto

/*
 * BEGIN - Spring Configuration -> Configuration DTOs
 */
internal fun CdrClientConfig.toDto(): CdrClientConfigDto {
    fun List<Connector>.toDto(): List<ConnectorDto> = map { it.toDto() }
    fun FileBusyTestStrategyProperty.toDto(): CdrClientConfigDto.FileBusyTestStrategy =
        CdrClientConfigDto.FileBusyTestStrategy.entries.first { it.name == strategy.name }

    return CdrClientConfigDto(
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

internal fun ProxyConfig.toDto(): CdrClientConfigDto.ProxyConfig =
    CdrClientConfigDto.ProxyConfig(
        url = url.value,
        username = username.value,
        password = if (password == ProxyPassword.NO_PASSWORD) password.value else ProxyPassword.MASKED_PASSWORD.value,
    )

internal fun Connector.toDto(): ConnectorDto =
    ConnectorDto(
        connectorId = connectorId.id,
        targetFolder = targetFolder.toString(),
        sourceFolder = sourceFolder.toString(),
        contentType = contentType,
        sourceArchiveEnabled = sourceArchiveEnabled,
        sourceArchiveFolder = sourceArchiveFolder?.toString(),
        sourceErrorFolder = sourceErrorFolder?.toString(),
        mode = CdrClientConfigDto.Mode.entries.first { it.name == mode.name },
        docTypeFolders = effectiveDocTypeFolders.toDto(),
    )

internal fun Map<DocumentType, Connector.DocTypeFolders>.toDto():
        Map<DocumentType, ConnectorDto.DocTypeFolders> =
    map { (key, value) ->
        DocumentType.entries.first { it.name == key.name } to ConnectorDto.DocTypeFolders(
            requestResponseSplit = value.requestResponseSplit,
            sourceFolder = value.sourceFolder?.toString(),
            sourceFolderReq = value.sourceFolderReq?.toString(),
            sourceFolderResp = value.sourceFolderResp?.toString(),
            targetFolder = value.targetFolder?.toString(),
            targetFolderReq = value.targetFolderReq?.toString(),
            targetFolderResp = value.targetFolderResp?.toString(),
            errorFolder = value.errorFolder?.toString(),
            archiveFolder = value.archiveFolder?.toString(),
        )
    }.toMap()

internal fun Endpoint.toDto(): DomainObjects.ApiEndpoint =
    DomainObjects.ApiEndpoint.fromEndpointParts(protocol = scheme, port = port, host = host.fqdn)

internal fun IdpCredentials.toDto(): CdrClientConfigDto.IdpCredentials =
    CdrClientConfigDto.IdpCredentials(
        tenantId = DomainObjects.TenantId.fromTenantId(tenantId.id),
        clientId = clientId.id,
        clientSecret = if (clientSecret == ClientSecret.NO_SECRET) clientSecret.value else ClientSecret.MASKED_SECRET.value,
        scope = DomainObjects.OAuthScope.fromScope(scope.scope),
        renewCredential = renewCredential.value,
        maxCredentialAge = maxCredentialAge,
        lastCredentialRenewalTime = lastCredentialRenewalTime.instant,
    )

internal fun RetryTemplateConfig.toDto(): CdrClientConfigDto.RetryTemplateConfig =
    CdrClientConfigDto.RetryTemplateConfig(
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
internal fun CdrClientConfigDto.toCdrClientConfig(): CdrClientConfig {
    fun List<ConnectorDto>.toCdrClientConfig(): MutableList<Connector> = map { it.toCdrClientConfig() }.toMutableList()
    fun CdrClientConfigDto.FileBusyTestStrategy.toCdrClientConfig(): FileBusyTestStrategyProperty = FileBusyTestStrategyProperty.valueOf(name)

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

internal fun CdrClientConfigDto.ProxyConfig.toCdrClientConfig(): ProxyConfig {
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

internal fun ConnectorDto.toCdrClientConfig(): Connector =
    Connector(
        connectorId = ConnectorId(connectorId),
        targetFolder = Path.of(targetFolder),
        sourceFolder = Path.of(sourceFolder),
        contentType = contentType,
        sourceArchiveEnabled = sourceArchiveEnabled,
        sourceArchiveFolder = sourceArchiveFolder?.let { Path.of(it) },
        sourceErrorFolder = sourceErrorFolder?.let { Path.of(it) },
        mode = CdrClientConfig.Mode.valueOf(mode.name),
        docTypeFolders = docTypeFolders.toCdrClientConfig(),
    )

internal fun Map<DocumentType, ConnectorDto.DocTypeFolders>.toCdrClientConfig():
        Map<DocumentType, Connector.DocTypeFolders> =
    map { (key, value) ->
        DocumentType.valueOf(key.name) to Connector.DocTypeFolders(
            requestResponseSplit = value.requestResponseSplit,
            sourceFolder = value.sourceFolder?.let { Path.of(it) },
            sourceFolderReq = value.sourceFolderReq?.let { Path.of(it) },
            sourceFolderResp = value.sourceFolderResp?.let { Path.of(it) },
            targetFolder = value.targetFolder?.let { Path.of(it) },
            targetFolderReq = value.targetFolderReq?.let { Path.of(it) },
            targetFolderResp = value.targetFolderResp?.let { Path.of(it) },
            errorFolder = value.errorFolder?.let { Path.of(it) },
            archiveFolder = value.archiveFolder?.let { Path.of(it) },
        )
    }.toMap()

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


internal fun CdrClientConfigDto.IdpCredentials.toCdrClientConfig(): IdpCredentials =
    IdpCredentials(
        tenantId = TenantId(tenantId.tenantId),
        clientId = ClientId(clientId),
        clientSecret = ClientSecret(clientSecret),
        scope = Scope(scope.scope),
        renewCredential = RenewCredential(renewCredential),
        maxCredentialAge = maxCredentialAge,
        lastCredentialRenewalTime = LastCredentialRenewalTime(lastCredentialRenewalTime),
    )

internal fun CdrClientConfigDto.RetryTemplateConfig.toCdrClientConfig(): RetryTemplateConfig =
    RetryTemplateConfig(
        retries = retries,
        initialDelay = java.time.Duration.ofMillis(initialDelay.toMillis()),
        maxDelay = java.time.Duration.ofMillis(maxDelay.toMillis()),
        multiplier = multiplier
    )

/*
 * END - Configuration DTOs -> Spring Configuration
 */
