package de.baschek.gymapp.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.gymapp.AppContainer
import de.baschek.gymapp.core.model.Exercise
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.MuscleMapping
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.stats.MuscleSummary
import de.baschek.gymapp.core.stats.StatsPeriod
import de.baschek.gymapp.core.stats.start
import de.baschek.gymapp.core.stats.summarizeMuscles
import de.baschek.gymapp.core.model.SetupParameter
import de.baschek.gymapp.core.training.SetCorrection
import de.baschek.gymapp.core.training.SkipReason
import de.baschek.gymapp.data.AdjustmentNotice
import de.baschek.gymapp.data.MachineInfo
import de.baschek.gymapp.data.SettingKeys
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.timer.TimerState
import de.baschek.gymapp.ui.SyncUi
import de.baschek.gymapp.ui.SyncUiSource
import de.baschek.gymapp.workout.SetInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

sealed interface SessionLoad {
    data object Loading : SessionLoad
    data object Missing : SessionLoad
    data class Ready(val id: String, val session: Session) : SessionLoad
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModel(private val c: AppContainer, private val sessionId: String) : ViewModel() {
    private val repo = c.repository
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    private val sessionFlow = repo.observeSession(sessionId)

    val sync: StateFlow<SyncUi> = SyncUiSource(c, viewModelScope).state

    val load: StateFlow<SessionLoad> = sessionFlow
        .map { if (it == null) SessionLoad.Missing else SessionLoad.Ready(it.id, it.value) }
        .state(SessionLoad.Loading)

    /** Levels, add-ons and setup values per exercise id from the machine profiles of the session's gym. */
    val machines: StateFlow<Map<String, MachineInfo>> = sessionFlow
        .map { it?.value?.gymId }
        .flatMapLatest { gym -> if (gym == null) flowOf(emptyMap()) else repo.observeMachineInfo(gym) }
        .state(emptyMap())

    val gyms: StateFlow<List<Stored<Gym>>> = repo.observeAllGyms().state(emptyList())
    val catalog: StateFlow<List<Stored<Exercise>>> = repo.observeExercises().state(emptyList())
    val timer: StateFlow<TimerState?> = c.timer.state

    /** The exercise the user jumped to (null = first exercise with an open set). */
    val focus: StateFlow<String?> = c.workout.focus

    /** What "Rückgängig" would take back, or null when there is nothing to undo. */
    val undoLabel: StateFlow<String?> = c.workout.undo
        .map { it?.takeIf { p -> p.first == sessionId }?.second?.label }
        .state(null)

    /** The newest applied assistant adjustment that was not dismissed ("Claude hat angepasst: ..."), or null. */
    val adjustmentBanner: StateFlow<AdjustmentNotice?> = repo.observeAdjustmentBanner(sessionId).state(null)

    fun dismissAdjustment(id: String) {
        viewModelScope.launch { repo.dismissAdjustment(id) }
    }

    /** Takes the adjustment back (sets logged since stay); the assistant is told via the synced status. */
    fun undoAdjustment(id: String) {
        viewModelScope.launch {
            repo.undoAdjustment(id)
            c.workout.clearUndo()
            c.scheduler.requestSync()
        }
    }

    /** Weight accepted from a suggestion for the next set (exercise id, total kg). */
    val acceptedWeight: StateFlow<Pair<String, Double>?> = c.workout.acceptedWeight

    /** Latest body weight, needed by the suggestion rule for exercises with added weight. */
    val bodyWeight: StateFlow<Double?> = repo.observeBodyWeights()
        .map { list -> list.maxByOrNull { it.value.date }?.value?.weightKg }
        .state(null)
    val restVibration: StateFlow<Boolean> = c.prefs.restVibration.state(true)

    val keepScreenOn: StateFlow<Boolean> = repo.observeSetting(SettingKeys.KEEP_SCREEN_ON).map { it != "0" }.state(true)

    /** Weight (total) last used for each exercise of this session in the same gym, from earlier workouts. */
    val previousWeights: StateFlow<Map<String, Double>> = combine(repo.observeCompletedSessions(), sessionFlow) { done, current ->
        val s = current?.value ?: return@combine emptyMap<String, Double>()
        s.exercises.mapNotNull { ex ->
            val earlier = done.firstOrNull { d -> d.value.gymId == s.gymId && d.value.exercises.any { it.exerciseId == ex.exerciseId } }
            val weight = earlier?.value?.exercises?.filter { it.exerciseId == ex.exerciseId }
                ?.flatMap { it.sets }
                ?.filter { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING && it.weightKg != null }
                ?.maxOfOrNull { it.weightKg!! }
            weight?.let { ex.exerciseId to it }
        }.toMap()
    }.state(emptyMap())

    /** New working weights this workout produced (summary). */
    val newWeights: StateFlow<Int> = repo.observeMilestones()
        .map { list -> list.count { it.value.sessionId == sessionId } }
        .state(0)

    /** Muscles trained today, this workout included (summary). */
    val todayMuscles: StateFlow<MuscleSummary> = combine(repo.observeCompletedSessions(), repo.observeExercises()) { done, catalog ->
        val groups = catalog.associate { it.id to MuscleMapping.groupsOf(it.value) }
        val subs = catalog.associate { it.id to MuscleMapping.subgroupsOf(it.value) }
        val from = StatsPeriod.TODAY.start(Instant.now(), ZoneId.systemDefault())
        summarizeMuscles(done.map { it.value }, { groups[it].orEmpty() }, from, subgroupsOf = { subs[it].orEmpty() })
    }.state(MuscleSummary.EMPTY)

    private var finishing = false

    /** All sets are done: the workout ends by itself and the summary appears. */
    fun finishIfDone(session: Session) {
        if (finishing || session.status != SessionStatus.ACTIVE) return
        if (session.exercises.none { it.sets.isNotEmpty() }) return
        if (session.exercises.any { ex -> ex.sets.any { it.status == SetStatus.PENDING } }) return
        finishing = true
        viewModelScope.launch { c.workout.finish(sessionId) }
    }

    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { repo.setSetting(SettingKeys.KEEP_SCREEN_ON, if (on) "1" else "0") }
    }

    fun logSet(exerciseId: String, setId: String, input: SetInput) {
        viewModelScope.launch { c.workout.logSet(sessionId, exerciseId, setId, input) }
    }

    fun skipSet(exerciseId: String, setId: String, reason: SkipReason) {
        viewModelScope.launch { c.workout.skipSet(sessionId, exerciseId, setId, reason) }
    }

    fun focusExercise(exerciseId: String?) {
        c.workout.setFocus(exerciseId)
    }

    fun acceptWeight(exerciseId: String, totalKg: Double?) {
        c.workout.acceptWeight(exerciseId, totalKg)
    }

    fun undo() {
        viewModelScope.launch { c.workout.undoLast(sessionId) }
    }

    fun skipRemaining(exerciseId: String, reason: SkipReason) {
        viewModelScope.launch { c.workout.skipRemaining(sessionId, exerciseId, reason) }
    }

    fun correctSet(exerciseId: String, setId: String, correction: SetCorrection) {
        viewModelScope.launch { c.workout.correctSet(sessionId, exerciseId, setId, correction) }
    }

    fun reopenSet(exerciseId: String, setId: String) {
        viewModelScope.launch { c.workout.reopenSet(sessionId, exerciseId, setId) }
    }

    fun resumeSkipped(exerciseId: String) {
        viewModelScope.launch { c.workout.resumeSkipped(sessionId, exerciseId) }
    }

    fun removeExercise(exerciseId: String) {
        viewModelScope.launch { c.workout.removeExercise(sessionId, exerciseId) }
    }

    /** Writes the setup values (seat, backrest, ...) into the machine profile of this gym x exercise. */
    fun saveSetup(gymId: String, exerciseId: String, params: List<SetupParameter>) {
        viewModelScope.launch {
            repo.saveMachineProfile(gymId, exerciseId) { it.copy(setupParameters = params.takeIf { p -> p.isNotEmpty() }) }
        }
    }

    fun addWarmup(exerciseId: String) {
        viewModelScope.launch { c.workout.addWarmup(sessionId, exerciseId) }
    }

    fun setNote(exerciseId: String, note: String) {
        viewModelScope.launch { c.workout.setNote(sessionId, exerciseId, note) }
    }

    fun moveExercise(exerciseId: String, delta: Int) {
        viewModelScope.launch { c.workout.moveExercise(sessionId, exerciseId, delta) }
    }

    fun toggleSkipExercise(exerciseId: String) {
        viewModelScope.launch { c.workout.toggleSkipExercise(sessionId, exerciseId) }
    }

    fun addExercise(catalogExerciseId: String) {
        viewModelScope.launch { c.workout.addExercise(sessionId, catalogExerciseId) }
    }

    fun plus15() {
        c.timer.extendRest(15)
    }

    fun skipRest() {
        c.workout.skipRest()
    }

    /** "Training beenden": open sets count as skipped. */
    fun finishNow() {
        Telemetry.track("training", "finish")
        finishing = true
        viewModelScope.launch { c.workout.finish(sessionId) }
    }
}
