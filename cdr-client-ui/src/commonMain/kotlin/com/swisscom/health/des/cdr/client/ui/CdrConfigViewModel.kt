package com.swisscom.health.des.cdr.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.error_client_communication
import com.swisscom.health.des.cdr.client.ui.data.CdrClientApiClient
import com.swisscom.health.des.cdr.client.ui.data.ShutdownResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

data class CdrConfigUiState(
    val clientServiceRunning: Boolean = true,
    val clientServiceEnabled: Boolean = true,
    val busy: Boolean = false,
    val errorKey: StringResource? = null, // should be an ArrayDeque<String>, but I have not figured out how to turn that into an observable state yet
)

class CdrConfigViewModel(
    private val cdrClientApiClient: CdrClientApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CdrConfigUiState())
    val uiState: StateFlow<CdrConfigUiState> = _uiState.asStateFlow()

    fun shutdownClientService() = viewModelScope.launch {
        cdrClientApiClient.shutdownClientServiceProcess().let { result ->
            when (result) {
                is ShutdownResult.Success -> {
                    _uiState.value = _uiState.value.copy(clientServiceRunning = false)
                }

                is ShutdownResult.Failure -> {
                    _uiState.value = _uiState.value.copy(errorKey = Res.string.error_client_communication)
                }
            }
        }
    }

    fun errorMessageShown() {
        _uiState.update {
            it.copy(errorKey = null)
        }
    }
}
