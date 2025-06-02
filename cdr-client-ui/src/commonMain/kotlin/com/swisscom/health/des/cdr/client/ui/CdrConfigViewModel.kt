package com.swisscom.health.des.cdr.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_client_communication
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import com.swisscom.health.des.cdr.client.ui.data.ShutdownResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration.Companion.seconds

data class CdrConfigUiState(
    val clientServiceStatus: DTOs.StatusResponse.StatusCode = DTOs.StatusResponse.StatusCode.SYNCHRONIZING,
    val errorKey: StringResource? = null, // should be an ArrayDeque<String>, but I have not figured out how to turn that into an observable state yet
)

internal class CdrConfigViewModel(
    private val cdrClientApiClient: CdrClientApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CdrConfigUiState())

    val uiState: StateFlow<CdrConfigUiState> = _uiState.asStateFlow()

    fun shutdownClientService(): Job = viewModelScope.launch {
        cdrClientApiClient.shutdownClientServiceProcess().let { result ->
            when (result) {
                is ShutdownResult.Success -> {
                    _uiState.value = _uiState.value.copy(clientServiceStatus = DTOs.StatusResponse.StatusCode.UNKNOWN)
                }

                is ShutdownResult.Failure -> {
                    _uiState.value = _uiState.value.copy(errorKey = Res.string.error_client_communication)
                }
            }
        }
    }

    fun updateClientServiceStatus(): Job = viewModelScope.launch {
        while (true) {
            cdrClientApiClient.getClientServiceStatus().let { status ->
                _uiState.value = _uiState.value.copy(clientServiceStatus = status)
            }
            delay(STATUS_CHECK_DELAY)
        }
    }

    fun errorMessageShown() {
        _uiState.update {
            it.copy(errorKey = null)
        }
    }

    private companion object {
        @JvmStatic
        private val STATUS_CHECK_DELAY = 1.seconds
    }
}
