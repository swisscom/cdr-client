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

    var fileSynchronizationEnabled: Boolean
        get() = _uiState.value.clientServiceConfig.fileSynchronizationEnabled
        set(value) {
            setFileSync(value)
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

    private fun setFileSync(enabled: Boolean) {
        logger.debug { "setFileSync: $enabled" }
        _uiState.update {
            it.copy(
                clientServiceConfig = it.clientServiceConfig.copy(
                    fileSynchronizationEnabled = enabled
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
            cdrClientApiClient.getClientServiceStatus(retryStrategy).let { status ->
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
        onValidationError: ErrorHandler = reportValidationErrorHandler,
        onSuccess: SuccessHandler<U>,
    ) {
        when (this) {
            is CdrClientApiClient.Result.Success -> onSuccess(response)
            is CdrClientApiClient.Result.IOError -> onIOError(errors)
            is CdrClientApiClient.Result.ValidationError -> onValidationError(errors)
        }
    }

    private val reportIoErrorHandler: ErrorHandler = { errorMap ->
        _uiState.update {
            it.copy(
                errorMessageKey = StringResourceWithArgs(
                    resourceId = Res.string.error_client_communication,
                    *(errorMap.values.toTypedArray())
                )
            )
        }
    }

    private val reportValidationErrorHandler: ErrorHandler = { errorMap ->
        _uiState.update {
            it.copy(
                errorMessageKey = StringResourceWithArgs(
                    resourceId = Res.string.error_client_validation,
                    errorMap.entries.joinToString("\n"),
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
