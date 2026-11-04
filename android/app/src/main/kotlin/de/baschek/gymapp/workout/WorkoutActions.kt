package de.baschek.gymapp.workout

import de.baschek.gymapp.core.model.Metric
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
import de.baschek.gymapp.core.training.captureUndo
import de.baschek.gymapp.core.training.correctSet as coreCorrectSet
import de.baschek.gymapp.core.training.removeExercise as coreRemoveExercise
import de.baschek.gymapp.core.training.reopenSet as coreReopenSet
import de.baschek.gymapp.core.training.resumeSkippedSets as coreResumeSkippedSets
import de.baschek.gymapp.core.training.skipRemainingSets as coreSkipRemainingSets
import de.baschek.gymapp.core.training.addExercise as coreAddExercise
import de.baschek.gymapp.core.training.insertWarmup as coreInsertWarmup
import de.baschek.gymapp.core.training.moveExercise as coreMoveExercise
import de.baschek.gymapp.core.training.setExerciseNote as coreSetExerciseNote
import de.baschek.gymapp.core.training.skipSet as coreSkipSet
import de.baschek.gymapp.core.training.templateFor
import de.baschek.gymapp.core.training.upNextExerciseIndex
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
) {
    private val _focus = MutableStateFlow<String?>(null)

    /** The exercise the user jumped to (session exercise id); null = the first exercise with an open set. */
    val focus: StateFlow<String?> = _focus.asStateFlow()

    fun setFocus(exerciseId: String?) {
        _focus.value = exerciseId
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
    }

    /** Takes the last action back. Returns its label, or null if there was nothing to undo. */
    suspend fun undoLast(sessionId: String): String? {
        val (id, entry) = _undo.value ?: return null
        if (id != sessionId) return null
        _undo.value = null
        repository.updateSession(sessionId) { applyUndo(it, entry) }
        if (timer.state.value?.phase == TimerPhase.REST) timer.stop()
        _accepted.value = null
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
                    else ex.copy(
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
                    )
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

    /** Logs the next pending set with the prefilled values (used by the notification action). */
    suspend fun logNextPrefilled(): Boolean {
        val active = repository.getActiveSession() ?: return false
        val focused = upNextExerciseIndex(active.value.exercises, _focus.value).let { active.value.exercises.getOrNull(it) }
        val exercise = focused ?: nextExercise(active.value.exercises) ?: return false
        val set = exercise.sets.first { it.status == SetStatus.PENDING }
        val weights = repository.getMachineWeights(active.value.gymId)[exercise.exerciseId].orEmpty()
        val pre = prefillSet(exercise, weights)
        logSet(active.id, exercise.id, set.id, SetInput(pre.weightKg, pre.value, pre.effort))
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

    /** Inserts a warm-up set before the first pending working set of the exercise. */
    suspend fun addWarmup(sessionId: String, exerciseId: String) {
        _undo.value = null
        repository.updateSession(sessionId) { coreInsertWarmup(it, exerciseId) { UUID.randomUUID().toString() } }
    }

    suspend fun moveExercise(sessionId: String, exerciseId: String, delta: Int) =
        repository.updateSession(sessionId) { coreMoveExercise(it, exerciseId, delta) }

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
        repository.finishSession(sessionId)
        scheduler.requestSync()
    }

    companion object {
        fun nextExercise(exercises: List<SessionExercise>): SessionExercise? =
            exercises.firstOrNull { ex -> ex.sets.any { it.status == SetStatus.PENDING } }
    }
}
