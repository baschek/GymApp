package de.baschek.gymapp.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.telemetry.Telemetry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository

    val sessions: StateFlow<List<Stored<Session>>> =
        repo.observeCompletedSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val gyms: StateFlow<List<Stored<Gym>>> =
        repo.observeGyms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Changes the gym and recalculates the working weights of the old and the new gym. */
    fun changeGym(sessionId: String, gymId: String) {
        Telemetry.track("history", "change_gym")
        viewModelScope.launch { repo.reassignSessionGym(sessionId, gymId) }
    }

    fun markReviewed(sessionId: String) {
        Telemetry.track("history", "mark_reviewed")
        viewModelScope.launch { repo.clearReviewFlags(sessionId) }
    }
}
