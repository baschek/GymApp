package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType

/*
 * Corrections while a workout is running: fixing a logged set, taking skipped sets back, removing an
 * exercise that was added by mistake, free jumping between exercises and a one-step undo. Pure functions
 * on the session; the repository applies them through updateSession.
 */

// ---- free jumping -----------------------------------------------------------------------

private fun hasPending(ex: SessionExercise) = ex.sets.any { it.status == SetStatus.PENDING }

/**
 * Index of the exercise the training screen shows: the one the user jumped to ([focusedId]) if it still
 * exists, otherwise the first exercise with an open set. -1 when nothing is open and nothing is focused.
 */
fun viewExerciseIndex(exercises: List<SessionExercise>, focusedId: String?): Int {
    val focused = if (focusedId == null) -1 else exercises.indexOfFirst { it.id == focusedId }
    return if (focused >= 0) focused else currentExerciseIndex(exercises)
}

/** Index of the exercise that comes after the rest: the focused one if it has open sets, else the first with open sets. */
fun upNextExerciseIndex(exercises: List<SessionExercise>, focusedId: String?): Int {
    val focused = if (focusedId == null) -1 else exercises.indexOfFirst { it.id == focusedId }
    return if (focused >= 0 && hasPending(exercises[focused])) focused else currentExerciseIndex(exercises)
}

/**
 * The session with its synced focus (amendment F) set to [focusedId], so that the server can show which exercise the user
 * has open. A focus that is not an exercise of the session counts as none. Returns the same instance if nothing changes.
 */
fun withSyncedFocus(session: Session, focusedId: String?): Session {
    val valid = focusedId?.takeIf { id -> session.exercises.any { it.id == id } }
    return if (session.focusExerciseId == valid) session else session.copy(focusExerciseId = valid)
}

// ---- correcting and resuming sets ---------------------------------------------------------

/** New values for a logged set. [value] is reps, seconds or meters depending on the exercise target. */
data class SetCorrection(
    val weightKg: Double?,
    val value: Double?,
    val effort: String?,
    val addonWeightsKg: List<Double>? = null,
)

private fun mapSet(session: Session, exerciseId: String, setId: String, change: (SessionExercise, SetLog) -> SetLog): Session =
    session.copy(
        exercises = session.exercises.map { ex ->
            if (ex.id != exerciseId) ex else ex.copy(sets = ex.sets.map { if (it.id == setId) change(ex, it) else it })
        },
    )

/** Changes weight, reps (or seconds/meters) and effort of a COMPLETED set. Timing fields stay. */
fun correctSet(session: Session, exerciseId: String, setId: String, correction: SetCorrection): Session =
    mapSet(session, exerciseId, setId) { ex, set ->
        if (set.status != SetStatus.COMPLETED) set
        else set.copy(
            weightKg = correction.weightKg,
            addonWeightsKg = correction.addonWeightsKg?.takeIf { it.isNotEmpty() },
            reps = if (ex.target.metric == Metric.REPS) correction.value?.toInt() else set.reps,
            seconds = if (ex.target.metric == Metric.SECONDS) correction.value?.toInt() else set.seconds,
            meters = if (ex.target.metric == Metric.METERS) correction.value else set.meters,
            effort = if (set.setType == SetType.WARMUP) set.effort else correction.effort,
        )
    }

private fun SetLog.reopened() = copy(
    status = SetStatus.PENDING, weightKg = null, addonWeightsKg = null, reps = null, seconds = null, meters = null,
    effort = null, setDurationSeconds = null, restBeforeSeconds = null, setupBeforeSeconds = null, completedAt = null,
)

/** Makes a logged or skipped set open again (its values are cleared). Open sets stay as they are. */
fun reopenSet(session: Session, exerciseId: String, setId: String): Session =
    mapSet(session, exerciseId, setId) { _, set -> if (set.status == SetStatus.PENDING) set else set.reopened() }

/** Takes every skipped set of the exercise back (pending again). Logged sets stay. Returns the session unchanged if none was skipped. */
fun resumeSkippedSets(session: Session, exerciseId: String): Session =
    session.copy(
        exercises = session.exercises.map { ex ->
            if (ex.id != exerciseId) ex
            else ex.copy(sets = ex.sets.map { if (it.status != SetStatus.PENDING && it.status != SetStatus.COMPLETED) it.reopened() else it })
        },
    )

/** Whether the exercise has sets that were skipped (and can be resumed). */
fun hasSkippedSets(ex: SessionExercise): Boolean = ex.sets.any { it.status != SetStatus.PENDING && it.status != SetStatus.COMPLETED }

/** Skips all open sets of the exercise with [reason] (the rest of a running exercise). */
fun skipRemainingSets(session: Session, exerciseId: String, reason: SkipReason): Session =
    session.copy(
        exercises = session.exercises.map { ex ->
            if (ex.id != exerciseId) ex
            else ex.copy(sets = ex.sets.map { if (it.status == SetStatus.PENDING) it.copy(status = reason.status, weightKg = null, reps = null) else it })
        },
    )

// ---- removing an exercise -----------------------------------------------------------------

/** Only exercises added during the workout (not from the plan) and without logged sets can be removed; plan exercises are skipped instead. */
fun canRemoveExercise(ex: SessionExercise): Boolean =
    ex.sourcePlanExerciseId == null && ex.sets.none { it.status == SetStatus.COMPLETED }

fun removeExercise(session: Session, exerciseId: String): Session {
    val ex = session.exercises.firstOrNull { it.id == exerciseId } ?: return session
    if (!canRemoveExercise(ex)) return session
    return session.copy(exercises = session.exercises.filter { it.id != exerciseId }.mapIndexed { i, e -> e.copy(order = i + 1) })
}

// ---- undo ---------------------------------------------------------------------------------

/** The state of one exercise before the last action. [label] says what "Rückgängig" would undo. */
data class UndoEntry(val label: String, val exerciseId: String, val before: SessionExercise, val index: Int)

/** Remembers the exercise as it is now; call it before applying an undoable action. */
fun captureUndo(session: Session, exerciseId: String, label: String): UndoEntry? {
    val i = session.exercises.indexOfFirst { it.id == exerciseId }
    if (i < 0) return null
    return UndoEntry(label, exerciseId, session.exercises[i], i)
}

/**
 * Restores the sets of the exercise (or puts a removed exercise back at its old position). Other
 * exercises and their order are not touched.
 */
fun applyUndo(session: Session, entry: UndoEntry): Session {
    val present = session.exercises.any { it.id == entry.exerciseId }
    if (present) {
        return session.copy(exercises = session.exercises.map { if (it.id == entry.exerciseId) it.copy(sets = entry.before.sets) else it })
    }
    val list = session.exercises.toMutableList()
    list.add(entry.index.coerceIn(0, list.size), entry.before)
    return session.copy(exercises = list.mapIndexed { i, e -> e.copy(order = i + 1) })
}
