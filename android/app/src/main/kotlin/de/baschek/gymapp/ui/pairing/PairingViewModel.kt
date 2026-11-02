package de.baschek.gymapp.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.sync.PairResult
import de.baschek.gymapp.telemetry.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PairingFailure { URL, CODE, RATE, NETWORK, OTHER }

sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object Busy : PairingUiState
    data object Done : PairingUiState
    data class Failed(val reason: PairingFailure) : PairingUiState
}

class PairingViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    fun pair(url: String, code: String) {
        Telemetry.track("pairing", "pair")
        _state.value = PairingUiState.Busy
        viewModelScope.launch {
            _state.value = when (c.syncManager.pair(url, code)) {
                PairResult.Success -> PairingUiState.Done
                PairResult.InvalidUrl -> PairingUiState.Failed(PairingFailure.URL)
                PairResult.InvalidCode -> PairingUiState.Failed(PairingFailure.CODE)
                PairResult.RateLimited -> PairingUiState.Failed(PairingFailure.RATE)
                PairResult.Network -> PairingUiState.Failed(PairingFailure.NETWORK)
                PairResult.Other -> PairingUiState.Failed(PairingFailure.OTHER)
            }
        }
    }
}
