package com.swisscom.health.des.cdr.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_client_communication
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_client_configuration
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_client_validation
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.time.delay
import org.jetbrains.compose.resources.StringResource
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

private typealias ErrorHandler = suspend (Map<String, Any>) -> Unit
private typealias SuccessHandler<T> = suspend (T) -> Unit

/**
 * State of the various UI elements.
 *
 * @param clientServiceStatus The current status of the CDR Client service.
 * @param clientServiceConfig The CDR Client service configuration.
 * @param errorMessageKey An optional error message key to display in a Snackbar.
 */
internal data class CdrConfigUiState(
    val clientServiceStatus: DTOs.StatusResponse.StatusCode = DTOs.StatusResponse.StatusCode.UNKNOWN,
    val clientServiceConfig: DTOs.CdrClientConfig = DTOs.CdrClientConfig.EMPTY,
    val errorMessageKey: StringResourceWithArgs? = null, // should be an ArrayDeque<String>, but I have not figured out how to turn that into an observable state yet
)

/**
 * ViewModel for the CDR client configuration screen. Unfortunately, the way this works is by creating
 * side effects on the [CdrConfigUiState]. Changes to the state trigger the recomposition of the UI.
 *
 * @param cdrClientApiClient The client to communicate with the CDR Client service.
 */
internal class CdrConfigViewModel(
    private val cdrClientApiClient: CdrClientApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CdrConfigUiState())
    val uiStateFlow: StateFlow<CdrConfigUiState> = _uiState.asStateFlow()

    init {
        queryClientServiceConfiguration()
    }

    /**
     * Sends the current client configuration to the CDR Client service. If the service accepts the
     * configuration, the service is told to restart so it applies the new configuration.
     */
    fun applyClientServiceConfiguration(): Job =
        viewModelScope.launch {
            cdrClientApiClient.updateClientServiceConfiguration(_uiState.value.clientServiceConfig).handle { response: DTOs.CdrClientConfig ->
                _uiState.update {
                    it.copy(
                        clientServiceConfig = response,
                    )
                }
                asyncClientServiceRestart().join()
            }
        }

    /**
     * Retrieves the CDR Client service configuration from the client service. This is the
     * configuration as it is currently applied in the client service. This state might differ
     * from the state of the configuration file if the configuration has been updated, but the
     * client service has not been restarted yet.
     */
    fun queryClientServiceConfiguration(): Job =
        viewModelScope.launch {
            cdrClientApiClient.getClientServiceConfiguration().handle { config: DTOs.CdrClientConfig ->
                _uiState.update {
                    it.copy(
                        clientServiceConfig = config
                    )
                }
            }
        }

    /**
     * Sets the file synchronization enabled state in the client service configuration.
     *
     * @param enabled Whether file synchronization is enabled or not.
     */
    fun setFileSync(enabled: Boolean) {
        logger.debug { "setFileSync: '$enabled'" }
        _uiState.update {
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    fileSynchronizationEnabled = enabled
                )
            )
        }
    }

    /**
     * Sets the file busy test strategy in the client service configuration.
     *
     * @param strategy The file busy test strategy to use. Must be one of the values defined in
     * [DTOs.CdrClientConfig.FileBusyTestStrategy].
     * @see DTOs.CdrClientConfig.FileBusyTestStrategy
     */
    fun setFileBusyTestStrategy(strategy: String) {
        logger.debug { "setFileBusyTestStrategy: '$strategy'" }
        _uiState.update {
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    fileBusyTestStrategy = DTOs.CdrClientConfig.FileBusyTestStrategy.valueOf(strategy)
                )
            )
        }
    }

    /**
     * Sets the CDR API host in the client service configuration.
     *
     * @param host The (fully qualified) host name of the CDR API.
     */
    fun setCdrApiHost(host: String) {
        logger.debug { "setCdrApiHost: '$host'" }
        _uiState.update {
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    cdrApi = it.clientServiceConfig.cdrApi.copy(
                        host = host
                    )
                )
            )
        }
    }

    /**
     * Sets the temporary download directory in the client service configuration.
     *
     * @param path The temporary directory to use for file downloads.
     */
    fun setLocalPath(path: String) {
        logger.debug { "setLocalPath: '$path'" }
        _uiState.update {
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    localFolder = path
                )
            )
        }
    }

    /**
     * Sets the IDP tenant ID in the client service configuration.
     *
     * @param id The IDP tenant ID to use.
     */
    fun setIdpTenantId(id: String) {
        logger.debug { "setTenantId: '$id'" }
        _uiState.update {
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    idpCredentials = it.clientServiceConfig.idpCredentials.copy(
                        tenantId = id
                    )
                )
            )
        }
    }

    /**
     * Sets the IDP client ID in the client service configuration.
     *
     * @param id The IDP client ID to use.
     */
    fun setIdpClientId(id: String) {
        logger.debug { "setClientId: '$id'" }
        _uiState.update {
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    idpCredentials = it.clientServiceConfig.idpCredentials.copy(
                        clientId = id
                    )
                )
            )
        }
    }

    /**
     * Sets the IDP client password in the client service configuration.
     *
     * @param password The IDP client password to use.
     */
    fun setIdpClientPassword(password: String) {
        logger.debug { "setClientPassword: '$password'" }
        _uiState.update {
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    idpCredentials = it.clientServiceConfig.idpCredentials.copy(
                        clientSecret = password
                    )
                )
            )
        }
    }

    /**
     * Sets whether the IDP client secret should be renewed automatically after 365 days.
     *
     * @param renew Whether to renew the client secret automatically.
     */
    fun setIdpRenewClientSecret(renew: Boolean) {
        logger.debug { "setIdpRenewClientSecret: '$renew'" }
        _uiState.update {
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    idpCredentials = it.clientServiceConfig.idpCredentials.copy(
                        renewCredential = renew
                    )
                )
            )
        }
    }

    /**
     * Sets the connector ID in the client service configuration.
     *
     * @param connectorId The ID of the connector to set.
     */
    fun setConnectorId(connectorId: String) {
        logger.debug { "setConnectorId: '$connectorId'" }
        _uiState.update {
            val connector =
                if (it.clientServiceConfig.customer.isEmpty()) {
                    DTOs.CdrClientConfig.Connector.EMPTY.copy(connectorId = connectorId)
                } else {
                    it.clientServiceConfig.customer[0].copy(connectorId = connectorId)
                }

            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    customer = listOf(connector)
                )
            )
        }
    }

    /**
     * Sets the connector mode in the client service configuration.
     *
     * @param mode The mode of the connector to set. Must be one of the values defined in
     * [DTOs.CdrClientConfig.Mode].
     * @see DTOs.CdrClientConfig.Mode
     */
    fun setConnectorMode(mode: String) {
        logger.debug { "setConnectorMode: '$mode'" }
        _uiState.update {
            val connector =
                if (it.clientServiceConfig.customer.isEmpty()) {
                    DTOs.CdrClientConfig.Connector.EMPTY.copy(mode = DTOs.CdrClientConfig.Mode.valueOf(mode))
                } else {
                    it.clientServiceConfig.customer[0].copy(mode = DTOs.CdrClientConfig.Mode.valueOf(mode))
                }

            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    customer = listOf(connector)
                )
            )
        }
    }

    /**
     * Sets the base archive directory for the connector in the client service configuration.
     *
     * @param archiveDir The base directory to use for archiving successfully uploaded files.
     */
    fun setConnectorArchiveDir(archiveDir: String) {
        logger.debug { "setConnectorArchiveDir: '$archiveDir'" }
        _uiState.update {
            val connector =
                if (it.clientServiceConfig.customer.isEmpty()) {
                    DTOs.CdrClientConfig.Connector.EMPTY.copy(sourceArchiveFolder = archiveDir)
                } else {
                    it.clientServiceConfig.customer[0].copy(sourceArchiveFolder = archiveDir)
                }

            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    customer = listOf(connector)
                )
            )
        }
    }

    /**
     * Sets whether the connector archive is enabled in the client service configuration.
     *
     * @param enabled Whether the connector archive is enabled or not.
     */
    fun setConnectorArchiveEnabled(enabled: Boolean) {
        logger.debug { "setConnectorArchiveEnabled: '$enabled'" }
        _uiState.update {
            val connector =
                if (it.clientServiceConfig.customer.isEmpty()) {
                    DTOs.CdrClientConfig.Connector.EMPTY.copy(sourceArchiveEnabled = enabled)
                } else {
                    it.clientServiceConfig.customer[0].copy(sourceArchiveEnabled = enabled)
                }

            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    customer = listOf(connector)
                )
            )
        }
    }

    /**
     * Sets the base error directory for the connector in the client service configuration.
     *
     * @param errorDir The base directory to use for storing files that could not be processed.
     */
    fun setConnectorErrorDir(errorDir: String) {
        logger.debug { "setConnectorErrorDir: '$errorDir'" }
        _uiState.update {
            val connector =
                if (it.clientServiceConfig.customer.isEmpty()) {
                    DTOs.CdrClientConfig.Connector.EMPTY.copy(sourceErrorFolder = errorDir)
                } else {
                    it.clientServiceConfig.customer[0].copy(sourceErrorFolder = errorDir)
                }

            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    customer = listOf(connector)
                )
            )
        }
    }

    /**
     * Sets the base download directory for the connector in the client service configuration.
     *
     * @param targetDir The base directory to use for storing files that have been downloaded
     * @see [setConnectorDocTypeTargetDir]
     */
    fun setConnectorBaseTargetDir(targetDir: String) {
        logger.debug { "setConnectorBaseTargetDir: '$targetDir'" }
        _uiState.update {
            val connector =
                if (it.clientServiceConfig.customer.isEmpty()) {
                    DTOs.CdrClientConfig.Connector.EMPTY.copy(targetFolder = targetDir)
                } else {
                    it.clientServiceConfig.customer[0].copy(targetFolder = targetDir)
                }

            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    customer = listOf(connector)
                )
            )
        }
    }

    /**
     * Sets the download directory for a specific document type in the connector in the client
     * service configuration. The document type specific target directory can either be relative
     * (to the base target directory) or absolute.
     *
     * @param docType The document type for which to set the target directory.
     * @param targetDir The target directory to use for this document type.
     * @see DTOs.CdrClientConfig.DocumentType
     * @see [setConnectorBaseTargetDir]
     */
    fun setConnectorDocTypeTargetDir(docType: DTOs.CdrClientConfig.DocumentType, targetDir: String) {
        logger.debug { "setConnectorDocTypeTargetDir: '$docType' -> '$targetDir'" }
        _uiState.update {
            val connector: DTOs.CdrClientConfig.Connector = it.clientServiceConfig.customer.firstOrNull() ?: DTOs.CdrClientConfig.Connector.EMPTY
            val docTypeFolders: DTOs.CdrClientConfig.Connector.DocTypeFolders =
                connector.docTypeFolders[docType]?.copy(targetFolder = targetDir) ?: DTOs.CdrClientConfig.Connector.DocTypeFolders(targetFolder = targetDir)
            val updatedConnector: DTOs.CdrClientConfig.Connector =
                if (docTypeFolders == DTOs.CdrClientConfig.Connector.DocTypeFolders.EMPTY) {
                    // if the target and source dir are both empty, we remove the docType from the map
                    connector.copy(docTypeFolders = (connector.docTypeFolders as MutableMap).apply { remove(docType) })
                } else {
                    // otherwise, we add or update the docType directories map
                    connector.copy(docTypeFolders = connector.docTypeFolders + (docType to docTypeFolders))
                }
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    customer = listOf(updatedConnector)
                )
            )
        }
    }

    /**
     * Sets the base source directory for the connector in the client service configuration.
     *
     * @param sourceDir The base directory to use for processing files that are uploaded to the CDR API.
     * @see [setConnectorDocTypeSourceDir]
     */
    fun setConnectorBaseSourceDir(sourceDir: String) {
        logger.debug { "setConnectorBaseSourceDir: '$sourceDir'" }
        _uiState.update {
            val connector =
                if (it.clientServiceConfig.customer.isEmpty()) {
                    DTOs.CdrClientConfig.Connector.EMPTY.copy(sourceFolder = sourceDir)
                } else {
                    it.clientServiceConfig.customer[0].copy(sourceFolder = sourceDir)
                }

            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    customer = listOf(connector)
                )
            )
        }
    }

    /**
     * Sets the source directory for a specific document type in the connector in the client
     * service configuration. The document type specific source directory can either be relative
     * (to the base source directory) or absolute.
     *
     * @param docType The document type for which to set the source directory.
     * @param sourceDir The source directory to use for this document type.
     * @see DTOs.CdrClientConfig.DocumentType
     * @see [setConnectorBaseSourceDir]
     */
    fun setConnectorDocTypeSourceDir(docType: DTOs.CdrClientConfig.DocumentType, sourceDir: String) {
        logger.debug { "setConnectorDocTypeSourceDir: '$docType' -> '$sourceDir'" }
        _uiState.update {
            val connector: DTOs.CdrClientConfig.Connector = it.clientServiceConfig.customer.firstOrNull() ?: DTOs.CdrClientConfig.Connector.EMPTY
            val docTypeFolders: DTOs.CdrClientConfig.Connector.DocTypeFolders =
                connector.docTypeFolders[docType]?.copy(sourceFolder = sourceDir) ?: DTOs.CdrClientConfig.Connector.DocTypeFolders(sourceFolder = sourceDir)
            val updatedConnector: DTOs.CdrClientConfig.Connector =
                if (docTypeFolders == DTOs.CdrClientConfig.Connector.DocTypeFolders.EMPTY) {
                    // if the target and source dir are both empty, we remove the docType from the map
                    connector.copy(docTypeFolders = (connector.docTypeFolders as MutableMap).apply { remove(docType) })
                } else {
                    // otherwise, we add or update the docType directories map
                    connector.copy(docTypeFolders = connector.docTypeFolders + (docType to docTypeFolders))
                }
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    customer = listOf(updatedConnector)
                )
            )
        }
    }


    /**
     * The intention is to restart the client service, but all we have to do is command it to shut down
     * with the correct reason, which translates into an exit code. It is the responsibility of the
     * service supervisor process (e.g., `systemd`) to then restart the service.
     *
     * Side effect: Sets the service status to [DTOs.StatusResponse.StatusCode.UNKNOWN]. The status will
     * be updated by the next call to
     * [queryClientServiceStatus]. (Yes, we have a race condition here.)
     */
    fun asyncClientServiceRestart(): Job = asyncClientServiceShutdown()

    /**
     * Queries the CDR Client service status.
     */
    fun queryClientServiceStatus(retryStrategy: CdrClientApiClient.RetryStrategy): Job =
        viewModelScope.launch {
            cdrClientApiClient.getClientServiceStatus(retryStrategy).let { status: DTOs.StatusResponse.StatusCode ->
                if (status.isOnlineState && _uiState.value.clientServiceStatus.isOfflineState) {
                    // if we went from an offline state to an online state, we also need to refresh the client service configuration
                    queryClientServiceConfiguration()
                }
                if (status == DTOs.StatusResponse.StatusCode.ERROR && _uiState.value.clientServiceStatus.isNotError()) {
                    queryClientServiceConfiguration()
                    reportError(Res.string.error_client_configuration)
                }
                _uiState.update {
                    it.copy(
                        clientServiceStatus = status
                    )
                }
            }
        }

    private fun DTOs.StatusResponse.StatusCode.isNotError(): Boolean =
        this != DTOs.StatusResponse.StatusCode.ERROR

    /**
     * Clears the error message key from the UI state.
     */
    fun clearErrorMessage() =
        _uiState.update {
            it.copy(
                errorMessageKey = null
            )
        }

    /**
     * Instructs the CDR Client service process to shut itself down. The received
     * [DTOs.ShutdownResponse] contains the time when the shutdown is scheduled for. We delay until
     * that time and then update the UI state and set the service status to
     * [DTOs.StatusResponse.StatusCode.UNKNOWN].
     *
     * The shutdown is guarded by a [Mutex] to prevent multiple shutdowns from being triggered at
     * the same time. If a shutdown is already in progress, then a completed job is returned immediately.
     *
     * @return A [Job] that completes when the shutdown is complete. If a shutdown is already in
     * progress, a completed job is returned immediately.
     */
    private fun asyncClientServiceShutdown(): Job =
        if (SHUTDOWN_GUARD.tryLock()) {
            viewModelScope.launch {
                cdrClientApiClient.shutdownClientServiceProcess().handle { response: DTOs.ShutdownResponse ->
                    // wait to set the client service status on the UI to try minimizing the chances of the status update
                    // process flipping the status before the service process restarts and temporarily goes offline
                    val untilStatusUpdateOnUi = Duration.between(Instant.now(), response.shutdownScheduledFor).coerceAtLeast(Duration.ZERO)
                    delay(untilStatusUpdateOnUi)
                    _uiState.update {
                        it.copy(
                            // set the status to UNKNOWN here and use retries during status update if remote status is OFFLINE,
                            // hoping the service is back before the retries run out; this way the user sees the state go from
                            // <state_before_shutdown> to UNKNOWN to <state_after_start>, without ever showing OFFLINE (which
                            // normally indicates a fatal problem in the service configuration that prevents it from starting)
                            clientServiceStatus = DTOs.StatusResponse.StatusCode.UNKNOWN
                        )
                    }
                }
            }.apply {
                invokeOnCompletion { _ -> SHUTDOWN_GUARD.unlock() }
            }
        } else {
            logger.debug { "client service shutdown is already in progress, ignoring command" }
            Job().apply { complete() }
        }

    private suspend fun <U> CdrClientApiClient.Result<U>.handle(
        onIOError: ErrorHandler = reportIoErrorHandler,
        onServiceError: ErrorHandler = reportServiceErrorHandler,
        onSuccess: SuccessHandler<U>,
    ) {
        when (this) {
            is CdrClientApiClient.Result.Success -> onSuccess(response)
            is CdrClientApiClient.Result.IOError -> onIOError(errors)
            is CdrClientApiClient.Result.ServiceError -> onServiceError(errors)
        }
    }

    private val reportIoErrorHandler: ErrorHandler = { errorMap ->
        reportError(
            messageKey = Res.string.error_client_communication,
            formatArgs = errorMap.values.toTypedArray()
        )
    }

    private val reportServiceErrorHandler: ErrorHandler = { errorMap ->
        reportError(
            messageKey = Res.string.error_client_validation,
            formatArgs = arrayOf(errorMap.entries.joinToString("\n"))
        )
    }

    internal fun reportError(messageKey: StringResource, vararg formatArgs: Any) {
        logger.trace { "reportError: '$messageKey', args: '$formatArgs'" }
        _uiState.update {
            it.copy(
                errorMessageKey = StringResourceWithArgs(
                    resourceId = messageKey,
                    formatArgs = formatArgs
                )
            )
        }
    }

    companion object {
        @JvmStatic
        private val SHUTDOWN_GUARD = Mutex()

        @JvmStatic
        val STATUS_CHECK_DELAY = 1.seconds

    }

}
