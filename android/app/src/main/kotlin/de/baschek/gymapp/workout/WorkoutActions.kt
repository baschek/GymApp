package de.baschek.gymapp.workout

import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.progression.SetPrefill
import de.baschek.gymapp.core.training.NotificationReps
import de.baschek.gymapp.core.training.RepsOverride
import de.baschek.gymapp.data.SettingKeys
import de.baschek.gymapp.data.Stored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.progression.prefillSet
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.sync.SyncScheduler
import de.baschek.gymapp.timer.TimerController
import de.baschek.gymapp.timer.TimerPhase
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.training.SetCorrection
import de.baschek.gymapp.core.training.SkipReason
import de.baschek.gymapp.core.training.UndoEntry
import de.baschek.gymapp.core.training.applyUndo
import de.baschek.gymapp.core.training.clearPlannedWeights
import de.baschek.gymapp.core.training.captureUndo
import de.baschek.gymapp.core.training.correctSet as coreCorrectSet
import de.baschek.gymapp.core.training.removeExercise as coreRemoveExercise
import de.baschek.gymapp.core.training.reopenSet as coreReopenSet
import de.baschek.gymapp.core.training.resumeSkippedSets as coreResumeSkippedSets
import de.baschek.gymapp.core.training.skipRemainingSets as coreSkipRemainingSets
import de.baschek.gymapp.core.training.addExercise as coreAddExercise
import de.baschek.gymapp.core.training.addSet as coreAddSet
import de.baschek.gymapp.core.training.insertWarmup as coreInsertWarmup
import de.baschek.gymapp.core.training.moveExercise as coreMoveExercise
import de.baschek.gymapp.core.training.setExerciseNote as coreSetExerciseNote
import de.baschek.gymapp.core.training.skipSet as coreSkipSet
import de.baschek.gymapp.core.training.templateFor
import de.baschek.gymapp.core.training.upNextExerciseIndex
import de.baschek.gymapp.core.training.withSyncedFocus
import de.baschek.gymapp.core.training.toggleSkipExercise as coreToggleSkipExercise
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Values the user entered for one set. [value] is reps, seconds or meters depending on the exercise target. */
data class SetInput(
    val weightKg: Double?,
    val value: Double?,
    val effort: String?,
    /** Add-ons that are part of [weightKg] (the total). */
    val addonWeightsKg: List<Double>? = null,
)

/** Workout operations shared by the UI and the notification actions. */
class WorkoutActions(
    private val repository: GymRepository,
    private val timer: TimerController,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
) {
    private val _focus = MutableStateFlow<String?>(null)

    /** The exercise the user jumped to (session exercise id); null = the first exercise with an open set. */
    val focus: StateFlow<String?> = _focus.asStateFlow()

    fun setFocus(exerciseId: String?) {
        if (_focus.value != exerciseId) clearRepsOverride()
        _focus.value = exerciseId
    }

    private val _repsOverride = MutableStateFlow<RepsOverride?>(null)

    /**
     * Reps (seconds, meters) the user set with "−1 / +1" in the notification for the next open set. Shared by the
     * notification (text and "Satz geschafft") and the set entry of the app; persisted in the settings table; dropped when
     * the set is logged, on a change of set or exercise, on a manual change in the app and when the workout ends.
     */
    val repsOverride: StateFlow<RepsOverride?> = _repsOverride.asStateFlow()

    private val overrideMutex = Mutex()
    private val persistMutex = Mutex()

    /** Brings the persisted override back after process death (only while a session is active). */
    suspend fun restoreRepsOverride() {
        val stored = NotificationReps.decode(repository.getSetting(SettingKeys.NOTIFICATION_REPS))
        _repsOverride.value = stored?.takeIf { repository.getActiveSession()?.id == it.sessionId }
        if (stored != null && _repsOverride.value == null) persistRepsOverride()
    }

    private fun setRepsOverride(value: RepsOverride?) {
        if (_repsOverride.value == value) return
        _repsOverride.value = value
        persistRepsOverride()
    }

    /** Discards the override (set logged, set/exercise changed, manual change in the app). */
    fun clearRepsOverride() = setRepsOverride(null)

    /** Writes the latest value (read when the write runs, so rapid changes cannot reorder). */
    private fun persistRepsOverride() {
        scope.launch {
            persistMutex.withLock {
                repository.setSetting(SettingKeys.NOTIFICATION_REPS, _repsOverride.value?.let(NotificationReps::encode))
            }
        }
    }

    /**
     * Writes the current focus into the active session (`focus_exercise_id`, amendment F) when it changed, so that the next
     * sync tells the server which exercise is open. Called by the sync loop before each run; a no-op if unchanged.
     */
    suspend fun publishFocus() {
        val active = repository.getActiveSession() ?: return
        val focus = _focus.value
        if (withSyncedFocus(active.value, focus) !== active.value) {
            repository.updateSession(active.id) { withSyncedFocus(it, focus) }
        }
    }

    private val _undo = MutableStateFlow<Pair<String, UndoEntry>?>(null)

    /** Session id and entry of the last undoable action ("Rückgängig"). */
    val undo: StateFlow<Pair<String, UndoEntry>?> = _undo.asStateFlow()

    private val _accepted = MutableStateFlow<Pair<String, Double>?>(null)

    /** Weight (exercise id, total kg) the user accepted from a suggestion for the next set. */
    val acceptedWeight: StateFlow<Pair<String, Double>?> = _accepted.asStateFlow()

    fun acceptWeight(exerciseId: String, totalKg: Double?) {
        _accepted.value = totalKg?.let { exerciseId to it }
    }

    /** Runs [block] and remembers the state of [exerciseId] before it, so that "Rückgängig" can restore it. */
    private suspend fun recorded(sessionId: String, exerciseId: String, label: String, block: suspend () -> Unit) {
        val before = repository.getSession(sessionId)?.let { captureUndo(it, exerciseId, label) }
        block()
        _undo.value = before?.let { sessionId to it }
        clearRepsOverride()
    }

    /** Forgets the one-step undo (an assistant adjustment changed the session underneath it). */
    fun clearUndo() {
        _undo.value = null
    }

    /** Takes the last action back. Returns its label, or null if there was nothing to undo. */
    suspend fun undoLast(sessionId: String): String? {
        val (id, entry) = _undo.value ?: return null
        if (id != sessionId) return null
        _undo.value = null
        repository.updateSession(sessionId) { applyUndo(it, entry) }
        if (timer.state.value?.phase == TimerPhase.REST) timer.stop()
        _accepted.value = null
        clearRepsOverride()
        _focus.value = entry.exerciseId
        return entry.label
    }

    suspend fun logSet(sessionId: String, exerciseId: String, setId: String, input: SetInput) =
        recorded(sessionId, exerciseId, "Satz geloggt") { doLogSet(sessionId, exerciseId, setId, input) }

    private suspend fun doLogSet(sessionId: String, exerciseId: String, setId: String, input: SetInput) {
        val current = repository.getSession(sessionId) ?: return
        val running = timer.state.value
        val setSeconds = if (running?.phase == TimerPhase.SET) running.elapsedSeconds() else null
        if (running != null && running.phase != TimerPhase.SET) timer.stop()
        val before = timer.consumeBeforeSet()
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

        repository.updateSession(sessionId) { s ->
            s.copy(
                exercises = s.exercises.map { ex ->
                    if (ex.id != exerciseId) ex
                    else clearPlannedWeights(ex.copy(
                        sets = ex.sets.map { set ->
                            if (set.id != setId) set
                            else set.copy(
                                status = SetStatus.COMPLETED,
                                weightKg = input.weightKg,
                                addonWeightsKg = input.addonWeightsKg?.takeIf { it.isNotEmpty() },
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
                    ), setId)
                },
            )
        }

        val exercise = current.exercises.firstOrNull { it.id == exerciseId }
        if (_accepted.value?.first == exerciseId) _accepted.value = null
        // last open set of a jumped-to exercise: carry on with the first exercise that still has open sets
        if (exercise != null && exercise.sets.count { it.status == SetStatus.PENDING && it.id != setId } == 0) _focus.value = null
        val pendingLeft = current.exercises.sumOf { ex ->
            ex.sets.count { it.status == SetStatus.PENDING && !(ex.id == exerciseId && it.id == setId) }
        }
        if (pendingLeft > 0 && exercise != null) timer.start(TimerPhase.REST, exercise.name, exercise.restSeconds) else timer.stop()
    }

    /** The set "Satz geschafft" in the notification would log: the set, and the values it would save. */
    data class NextLog(val sessionId: String, val exercise: SessionExercise, val set: SetLog, val prefill: SetPrefill)

    /**
     * The next open set of [active] (the focus exercise if it has open sets, else the first with open sets) with the
     * prefill that would be logged, including the notification reps [override] for exactly that set. Null if no set is open.
     */
    suspend fun nextLogFor(active: Stored<Session>, focus: String?, override: RepsOverride?): NextLog? {
        val exercises = active.value.exercises
        val exercise = exercises.getOrNull(upNextExerciseIndex(exercises, focus))?.takeIf { ex -> ex.sets.any { it.status == SetStatus.PENDING } }
            ?: nextExercise(exercises) ?: return null
        val set = exercise.sets.first { it.status == SetStatus.PENDING }
        val weights = repository.getMachineWeights(active.value.gymId)[exercise.exerciseId].orEmpty()
        val pre = prefillSet(exercise, weights)
        val value = NotificationReps.valueFor(override, active.id, exercise.id, set.id) ?: pre.value
        return NextLog(active.id, exercise, set, pre.copy(value = value))
    }

    /** Logs the next pending set with the prefilled values, reps from the notification override (notification action). */
    suspend fun logNextPrefilled(): Boolean {
        val next = overrideMutex.withLock {
            val active = repository.getActiveSession() ?: return false
            nextLogFor(active, _focus.value, _repsOverride.value) ?: return false
        }
        val pre = next.prefill
        logSet(next.sessionId, next.exercise.id, next.set.id, SetInput(pre.weightKg, pre.value, pre.effort))
        return true
    }

    /** "−1 / +1" of the notification: moves the reps (seconds: 5, meters: 10) of the next open set's prefill by [direction]. */
    suspend fun adjustNextReps(direction: Int): Boolean {
        overrideMutex.withLock {
            val active = repository.getActiveSession() ?: return false
            val next = nextLogFor(active, _focus.value, _repsOverride.value) ?: return false
            setRepsOverride(
                NotificationReps.press(
                    _repsOverride.value, next.sessionId, next.exercise.id, next.set.id,
                    next.prefill.value, direction, next.exercise.target.metric,
                ),
            )
        }
        return true
    }

    /** Skips one set with a reason and ends a running rest. */
    suspend fun skipSet(sessionId: String, exerciseId: String, setId: String, reason: SkipReason) {
        recorded(sessionId, exerciseId, "Überspringen") { repository.updateSession(sessionId) { coreSkipSet(it, exerciseId, setId, reason) } }
        timer.stop()
    }

    /** Skips all open sets of the exercise. */
    suspend fun skipRemaining(sessionId: String, exerciseId: String, reason: SkipReason) {
        recorded(sessionId, exerciseId, "Überspringen") { repository.updateSession(sessionId) { coreSkipRemainingSets(it, exerciseId, reason) } }
        _focus.value = null
        timer.stop()
    }

    /** Changes weight, reps and effort of a logged set while the workout runs. */
    suspend fun correctSet(sessionId: String, exerciseId: String, setId: String, correction: SetCorrection) =
        recorded(sessionId, exerciseId, "Korrektur") { repository.updateSession(sessionId) { coreCorrectSet(it, exerciseId, setId, correction) } }

    /** Makes a logged or skipped set open again. */
    suspend fun reopenSet(sessionId: String, exerciseId: String, setId: String) =
        recorded(sessionId, exerciseId, "Satz wieder offen") { repository.updateSession(sessionId) { coreReopenSet(it, exerciseId, setId) } }

    /** Takes all skipped sets of the exercise back. */
    suspend fun resumeSkipped(sessionId: String, exerciseId: String) =
        recorded(sessionId, exerciseId, "Sätze wieder aufgenommen") { repository.updateSession(sessionId) { coreResumeSkippedSets(it, exerciseId) } }

    /** Removes an exercise that was added during the workout (plan exercises are skipped instead). */
    suspend fun removeExercise(sessionId: String, exerciseId: String) {
        recorded(sessionId, exerciseId, "Übung entfernt") { repository.updateSession(sessionId) { coreRemoveExercise(it, exerciseId) } }
        if (_focus.value == exerciseId) _focus.value = null
    }

    /**
     * Adds one more open working set to the exercise (the user wants to do an extra set). It is a normal set: logged, skipped,
     * undone and synced like the others. The exercise becomes the focus, so the extra set comes next even if the exercise was
     * already finished.
     */
    suspend fun addSet(sessionId: String, exerciseId: String) {
        recorded(sessionId, exerciseId, "Satz hinzugefügt") { repository.updateSession(sessionId) { coreAddSet(it, exerciseId) { UUID.randomUUID().toString() } } }
        _focus.value = exerciseId
    }

    /** Inserts a warm-up set before the first pending working set of the exercise. */
    suspend fun addWarmup(sessionId: String, exerciseId: String) {
        _undo.value = null
        clearRepsOverride()
        repository.updateSession(sessionId) { coreInsertWarmup(it, exerciseId) { UUID.randomUUID().toString() } }
    }

    suspend fun moveExercise(sessionId: String, exerciseId: String, delta: Int) {
        clearRepsOverride()
        repository.updateSession(sessionId) { coreMoveExercise(it, exerciseId, delta) }
    }

    suspend fun toggleSkipExercise(sessionId: String, exerciseId: String) =
        recorded(sessionId, exerciseId, "Übung überspringen") { repository.updateSession(sessionId) { coreToggleSkipExercise(it, exerciseId) } }

    suspend fun setNote(sessionId: String, exerciseId: String, note: String) =
        repository.updateSession(sessionId) { coreSetExerciseNote(it, exerciseId, note) }

    /** Appends a catalog exercise (three sets) to the running workout. */
    suspend fun addExercise(sessionId: String, catalogExerciseId: String) {
        val exercise = repository.getExercise(catalogExerciseId) ?: return
        val template = templateFor(catalogExerciseId, exercise.name, exercise.measurementType, exercise.loadDirection, exercise.loadBasis)
        repository.updateSession(sessionId) { s: Session -> coreAddExercise(s, template, 3) { UUID.randomUUID().toString() } }
    }

    fun skipRest() {
        if (timer.state.value?.phase == TimerPhase.REST) timer.stop()
    }

    suspend fun finish(sessionId: String) {
        timer.reset()
        _undo.value = null
        _focus.value = null
        _accepted.value = null
        clearRepsOverride()
        repository.finishSession(sessionId)
        scheduler.requestSync()
    }

    companion object {
        fun nextExercise(exercises: List<SessionExercise>): SessionExercise? =
            exercises.firstOrNull { ex -> ex.sets.any { it.status == SetStatus.PENDING } }
    }
}
