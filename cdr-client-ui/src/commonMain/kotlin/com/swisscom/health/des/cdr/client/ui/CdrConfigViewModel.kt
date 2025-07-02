package com.swisscom.health.des.cdr.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_client_communication
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

internal data class CdrConfigUiState(
    val clientServiceStatus: DTOs.StatusResponse.StatusCode = DTOs.StatusResponse.StatusCode.UNKNOWN,
    val clientServiceConfig: DTOs.CdrClientConfig = DTOs.CdrClientConfig.EMPTY,
    val errorMessageKey: StringResourceWithArgs? = null, // should be an ArrayDeque<String>, but I have not figured out how to turn that into an observable state yet
)

/**
 * ViewModel for the CDR client configuration screen. Unfortunately, the way this works is by creating
 * side effects on the [CdrConfigUiState]. Changes to the state trigger the recomposition of the UI.
 */
internal class CdrConfigViewModel(
    private val cdrClientApiClient: CdrClientApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CdrConfigUiState())
    val uiStateFlow: StateFlow<CdrConfigUiState> = _uiState.asStateFlow()

    init {
        queryClientServiceConfiguration()
    }

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
     * Retrieves the client service configuration from the client service. This is the configuration as it is currently
     * applied in the client service. This state might differ from the state of the configuration file.
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

    fun setConnectorMode(mode:String) {
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
     * Effect: Updates the [uiStateFlow] and sets ths service status to [DTOs.StatusResponse.StatusCode.UNKNOWN].
     * The status will be updated by the next call to [queryClientServiceStatus]. (Yes, we have a race
     * condition here.)
     */
    fun asyncClientServiceRestart(): Job = asyncClientServiceShutdown()

    /**
     * Queries the client service status and updates the [uiStateFlow] with the result.
     */
    fun queryClientServiceStatus(retryStrategy: CdrClientApiClient.RetryStrategy): Job =
        viewModelScope.launch {
            cdrClientApiClient.getClientServiceStatus(retryStrategy).let { status: DTOs.StatusResponse.StatusCode ->
                if (status.isOnlineState && _uiState.value.clientServiceStatus.isOfflineState) {
                    // if we went from an offline state to an online state, we also need to refresh the client service configuration
                    queryClientServiceConfiguration()
                }
                _uiState.update {
                    it.copy(
                        clientServiceStatus = status
                    )
                }
            }
        }

    fun clearErrorMessage() =
        _uiState.update {
            it.copy(
                errorMessageKey = null
            )
        }

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
            logger.debug { "client shutdown is in progress, ignoring command" }
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
