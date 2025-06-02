package com.swisscom.health.des.cdr.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_client_communication
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import com.swisscom.health.des.cdr.client.ui.data.ShutdownResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

data class CdrConfigUiState(
    val clientServiceStatus: DTOs.StatusResponse.StatusCode = DTOs.StatusResponse.StatusCode.SYNCHRONIZING,
    val errorKey: StringResource? = null, // should be an ArrayDeque<String>, but I have not figured out how to turn that into an observable state yet
)

internal class CdrConfigViewModel(
    private val cdrClientApiClient: CdrClientApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CdrConfigUiState())
    val uiState: StateFlow<CdrConfigUiState> = _uiState.asStateFlow()

    fun asyncClientServiceShutdown() {
        if (SHUTDOWN_GUARD.tryLock()) {
            viewModelScope.launch {
                cdrClientApiClient.shutdownClientServiceProcess().let { result ->
                    when (result) {
                        is ShutdownResult.Success -> {
                            _uiState.value = _uiState.value.copy(clientServiceStatus = DTOs.StatusResponse.StatusCode.OFFLINE)
                        }

                        is ShutdownResult.Failure -> {
                            _uiState.value = _uiState.value.copy(errorKey = Res.string.error_client_communication)
                        }
                    }
                }
            }
                .invokeOnCompletion { _ -> SHUTDOWN_GUARD.unlock() }
        } else {
            logger.debug { "client shutdown is in progress, ignoring command" }
        }
    }

    suspend fun updateClientServiceStatus() {
        cdrClientApiClient.getClientServiceStatus().let { status ->
            _uiState.value = _uiState.value.copy(clientServiceStatus = status)
        }
    }

    fun errorMessageShown() {
        _uiState.update {
            it.copy(errorKey = null)
        }
    }

    companion object {
        @JvmStatic
        private val SHUTDOWN_GUARD = Mutex()

        @JvmStatic
        val STATUS_CHECK_DELAY = 1.seconds
    }

}
