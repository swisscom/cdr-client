package com.swisscom.health.des.cdr.client.config

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.config.CdrClientConfig.RetryTemplateConfig
import com.swisscom.health.des.cdr.client.xml.DocumentType
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize

/*
 * BEGIN - Spring Configuration -> Configuration DTOs
 */
internal fun CdrClientConfig.toDto(): DTOs.CdrClientConfig {
    fun List<CdrClientConfig.Connector>.toDto(): List<DTOs.CdrClientConfig.Connector> = map { it.toDto() }
    fun CdrClientConfig.FileBusyTestStrategy.toDto(): DTOs.CdrClientConfig.FileBusyTestStrategy =
        DTOs.CdrClientConfig.FileBusyTestStrategy.entries.first { it.name == name }

    return DTOs.CdrClientConfig(
        fileSynchronizationEnabled = fileSynchronizationEnabled.value,
        customer = customer.toDto(),
        cdrApi = cdrApi.toDto(),
        filesInProgressCacheSize = filesInProgressCacheSize.toString(),
        idpCredentials = idpCredentials.toDto(),
        idpEndpoint = idpEndpoint,
        localFolder = localFolder,
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
                sourceFolder = value.sourceFolder,
                targetFolder = value.targetFolder
            )
        }.toMap()
    }

    return DTOs.CdrClientConfig.Connector(
        connectorId = connectorId,
        targetFolder = targetFolder,
        sourceFolder = sourceFolder,
        contentType = contentType.toString(),
        sourceArchiveEnabled = sourceArchiveEnabled,
        sourceArchiveFolder = sourceArchiveFolder,
        sourceErrorFolder = sourceErrorFolder,
        mode = DTOs.CdrClientConfig.Mode.entries.first { it.name == mode.name },
        docTypeFolders = docTypeFolders.toDto(),
    )
}

internal fun CdrClientConfig.Endpoint.toDto(): DTOs.CdrClientConfig.Endpoint =
    DTOs.CdrClientConfig.Endpoint(
        scheme = scheme,
        host = host,
        port = port,
        basePath = basePath
    )

internal fun IdpCredentials.toDto(): DTOs.CdrClientConfig.IdpCredentials =
    DTOs.CdrClientConfig.IdpCredentials(
        tenantId = tenantId,
        clientId = clientId,
        clientSecret = clientSecret.value,
        scopes = scopes,
        renewCredential = renewCredential.value,
        maxCredentialAge = maxCredentialAge,
        lastCredentialRenewalTime = lastCredentialRenewalTime.value,
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
    fun List<DTOs.CdrClientConfig.Connector>.toCdrClientConfig(): List<CdrClientConfig.Connector> = map { it.toCdrClientConfig() }
    fun DTOs.CdrClientConfig.FileBusyTestStrategy.toCdrClientConfig(): CdrClientConfig.FileBusyTestStrategy =
        CdrClientConfig.FileBusyTestStrategy.valueOf(name)

    return CdrClientConfig(
        fileSynchronizationEnabled = if (fileSynchronizationEnabled) FileSynchronization.ENABLED else FileSynchronization.DISABLED,
        customer = customer.toCdrClientConfig(),
        cdrApi = cdrApi.toCdrClientConfig(),
        filesInProgressCacheSize = DataSize.parse(filesInProgressCacheSize),
        idpCredentials = idpCredentials.toCdrClientConfig(),
        idpEndpoint = idpEndpoint,
        localFolder = localFolder,
        pullThreadPoolSize = pullThreadPoolSize,
        pushThreadPoolSize = pushThreadPoolSize,
        retryDelay = retryDelay,
        scheduleDelay = scheduleDelay,
        credentialApi = credentialApi.toCdrClientConfig(),
        retryTemplate = retryTemplate.toCdrClientConfig(),
        fileBusyTestInterval = fileBusyTestInterval,
        fileBusyTestTimeout = fileBusyTestTimeout,
        fileBusyTestStrategy = fileBusyTestStrategy.toCdrClientConfig(),
    )
}

internal fun DTOs.CdrClientConfig.Connector.toCdrClientConfig(): CdrClientConfig.Connector {
    fun Map<DTOs.CdrClientConfig.DocumentType, DTOs.CdrClientConfig.Connector.DocTypeFolders>.toCdrClientConfig():
            Map<DocumentType, CdrClientConfig.Connector.DocTypeFolders> {
        return map { (key, value) ->
            DocumentType.valueOf(key.name) to CdrClientConfig.Connector.DocTypeFolders(
                sourceFolder = value.sourceFolder,
                targetFolder = value.targetFolder
            )
        }.toMap()
    }

    return CdrClientConfig.Connector(
        connectorId = connectorId,
        targetFolder = targetFolder,
        sourceFolder = sourceFolder,
        contentType = MediaType.parseMediaType(contentType),
        sourceArchiveEnabled = sourceArchiveEnabled,
        sourceArchiveFolder = sourceArchiveFolder,
        sourceErrorFolder = sourceErrorFolder,
        mode = CdrClientConfig.Mode.valueOf(mode.name),
        docTypeFolders = docTypeFolders.toCdrClientConfig(),
    )
}

internal fun DTOs.CdrClientConfig.Endpoint.toCdrClientConfig(): CdrClientConfig.Endpoint =
    CdrClientConfig.Endpoint(
        scheme = scheme,
        host = host,
        port = port,
        basePath = basePath
    )

internal fun DTOs.CdrClientConfig.IdpCredentials.toCdrClientConfig(): IdpCredentials =
    IdpCredentials(
        tenantId = tenantId,
        clientId = clientId,
        clientSecret = ClientSecret(clientSecret),
        scopes = scopes,
        renewCredential = RenewCredential(renewCredential),
        maxCredentialAge = maxCredentialAge,
        lastCredentialRenewalTime = LastUpdatedAt(lastCredentialRenewalTime),
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
