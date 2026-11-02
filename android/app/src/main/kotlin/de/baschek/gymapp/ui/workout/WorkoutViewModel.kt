package de.baschek.gymapp.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetStatus
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
import java.time.Instant
import java.time.temporal.ChronoUnit

sealed interface SessionLoad {
    data object Loading : SessionLoad
    data object Missing : SessionLoad
    data class Ready(val session: Session) : SessionLoad
}

/** Values the user entered for one set. [value] is reps, seconds or meters depending on the exercise target. */
data class SetInput(val weightKg: Double?, val value: Double?, val effort: String?)

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
        viewModelScope.launch {
            val current = (load.value as? SessionLoad.Ready)?.session ?: return@launch
            val running = c.timer.state.value
            val setSeconds = if (running?.phase == TimerPhase.SET) running.elapsedSeconds() else null
            if (running != null && running.phase != TimerPhase.SET) c.timer.stop()
            val before = c.timer.consumeBeforeSet()
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

            repo.updateSession(sessionId) { s ->
                s.copy(
                    exercises = s.exercises.map { ex ->
                        if (ex.id != exerciseId) ex
                        else ex.copy(
                            sets = ex.sets.map { set ->
                                if (set.id != setId) set
                                else set.copy(
                                    status = SetStatus.COMPLETED,
                                    weightKg = input.weightKg,
                                    reps = if (ex.target.metric == Metric.REPS) input.value?.toInt() else set.reps,
                                    seconds = if (ex.target.metric == Metric.SECONDS) input.value?.toInt() else set.seconds,
                                    meters = if (ex.target.metric == Metric.METERS) input.value else set.meters,
                                    effort = input.effort,
                                    setDurationSeconds = setSeconds,
                                    restBeforeSeconds = before.restSeconds,
                                    setupBeforeSeconds = before.setupSeconds,
                                    completedAt = now,
                                )
                            },
                        )
                    },
                )
            }

            val exercise = current.exercises.firstOrNull { it.id == exerciseId }
            val pendingLeft = current.exercises.sumOf { ex ->
                ex.sets.count { it.status == SetStatus.PENDING && !(ex.id == exerciseId && it.id == setId) }
            }
            if (pendingLeft > 0 && exercise != null) {
                c.timer.start(TimerPhase.REST, exercise.name, exercise.restSeconds)
            } else {
                c.timer.stop()
            }
        }
    }

    fun finish(onDone: () -> Unit) {
        Telemetry.track("workout", "finish")
        viewModelScope.launch {
            c.timer.stop()
            repo.finishSession(sessionId)
            c.scheduler.requestSync()
            onDone()
        }
    }
}
