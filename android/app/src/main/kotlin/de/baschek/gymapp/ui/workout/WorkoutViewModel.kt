package de.baschek.gymapp.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.workout.SetInput
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.data.SettingKeys
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.timer.TimerPhase
import de.baschek.gymapp.timer.TimerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SessionLoad {
    data object Loading : SessionLoad
    data object Missing : SessionLoad
    data class Ready(val session: Session) : SessionLoad
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModel(private val c: AppContainer, private val sessionId: String) : ViewModel() {
    private val repo = c.repository

    val load: StateFlow<SessionLoad> = repo.observeSession(sessionId)
        .map { if (it == null) SessionLoad.Missing else SessionLoad.Ready(it.value) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionLoad.Loading)

    /** Selectable weights per exercise id from the machine profiles of the session's gym. */
    val weights: StateFlow<Map<String, List<Double>>> = repo.observeSession(sessionId)
        .map { it?.value?.gymId }
        .flatMapLatest { gym -> if (gym == null) flowOf(emptyMap()) else repo.observeMachineWeights(gym) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val gyms: StateFlow<List<Stored<Gym>>> = repo.observeGyms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val timer: StateFlow<TimerState?> = c.timer.state

    val keepScreenOn: StateFlow<Boolean> = repo.observeSetting(SettingKeys.KEEP_SCREEN_ON)
        .map { it != "0" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setKeepScreenOn(on: Boolean) {
        Telemetry.track("workout", "keep_screen_on")
        viewModelScope.launch { repo.setSetting(SettingKeys.KEEP_SCREEN_ON, if (on) "1" else "0") }
    }

    fun startTimer(phase: TimerPhase, exercise: SessionExercise?) {
        Telemetry.track("workout", "timer_${phase.name.lowercase()}")
        c.timer.start(phase, exercise?.name.orEmpty(), exercise?.restSeconds ?: 0)
    }

    fun stopTimer() {
        Telemetry.track("workout", "timer_stop")
        c.timer.stop()
    }

    fun logSet(exerciseId: String, setId: String, input: SetInput) {
        Telemetry.track("workout", "log_set")
        viewModelScope.launch { c.workout.logSet(sessionId, exerciseId, setId, input) }
    }

    fun finish(onDone: () -> Unit) {
        Telemetry.track("workout", "finish")
        viewModelScope.launch {
            c.workout.finish(sessionId)
            onDone()
        }
    }
}
