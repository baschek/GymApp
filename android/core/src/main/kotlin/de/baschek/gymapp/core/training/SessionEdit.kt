package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.ModeSets
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.progression.nearestLevelIndex
import kotlin.math.roundToInt

/*
 * Pure edits of a running session: skipping sets and exercises, warm-up sets, reordering
 * and adding exercises. The repository applies them through updateSession.
 */

/** Why a set is skipped; maps to the contract's set status. */
enum class SkipReason(val status: String) {
    TIME(SetStatus.SKIPPED_TIME),
    EQUIPMENT(SetStatus.SKIPPED_EQUIPMENT),
    PAIN(SetStatus.PAIN),
    OTHER(SetStatus.SKIPPED_OTHER),
}

private fun renumber(sets: List<SetLog>): List<SetLog> = sets.mapIndexed { i, s -> s.copy(order = i + 1) }
private fun renumberExercises(list: List<SessionExercise>): List<SessionExercise> = list.mapIndexed { i, e -> e.copy(order = i + 1) }

/** Index of the exercise that is "current": the first with a pending set. */
fun currentExerciseIndex(exercises: List<SessionExercise>): Int = exercises.indexOfFirst { ex -> ex.sets.any { it.status == SetStatus.PENDING } }

/** Marks one pending set as skipped. */
fun skipSet(session: Session, exerciseId: String, setId: String, reason: SkipReason): Session =
    session.copy(
        exercises = session.exercises.map { ex ->
            if (ex.id != exerciseId) ex
            else ex.copy(sets = ex.sets.map { if (it.id == setId && it.status == SetStatus.PENDING) it.copy(status = reason.status, weightKg = null, reps = null) else it })
        },
    )

/** An exercise counts as skipped when it has sets and none is pending or completed. */
fun isSkipped(ex: SessionExercise): Boolean =
    ex.sets.isNotEmpty() && ex.sets.all { it.status == SetStatus.SKIPPED_OTHER && it.weightKg == null }

/**
 * Toggles "skipped" for an exercise that has not been started (all sets pending, or all skipped):
 * pending sets become skipped, skipped sets pending again. Started exercises are left alone.
 */
fun toggleSkipExercise(session: Session, exerciseId: String): Session =
    session.copy(
        exercises = session.exercises.map { ex ->
            if (ex.id != exerciseId) ex
            else when {
                ex.sets.all { it.status == SetStatus.PENDING } ->
                    ex.copy(sets = ex.sets.map { it.copy(status = SetStatus.SKIPPED_OTHER, weightKg = null, addonWeightsKg = null) })
                isSkipped(ex) -> ex.copy(sets = ex.sets.map { it.copy(status = SetStatus.PENDING) })
                else -> ex
            }
        },
    )

/** An exercise that can still be reordered: it has open sets or was skipped as a whole. Finished exercises stay where they are. */
fun isMovable(ex: SessionExercise): Boolean = ex.sets.any { it.status == SetStatus.PENDING } || isSkipped(ex)

/**
 * Moves an exercise by [delta] places, also in front of the current exercise (then it becomes the
 * current one). Exercises without open sets are fixed. Returns the session unchanged when the move is
 * not possible.
 */
fun moveExercise(session: Session, exerciseId: String, delta: Int): Session {
    val list = session.exercises
    val from = list.indexOfFirst { it.id == exerciseId }
    if (from < 0 || delta == 0 || !isMovable(list[from])) return session
    val to = from + delta
    if (to < 0 || to >= list.size) return session
    val m = list.toMutableList()
    val item = m.removeAt(from)
    m.add(to, item)
    return session.copy(exercises = renumberExercises(m))
}

/** Whether [moveExercise] would move the exercise. */
fun canMoveExercise(session: Session, exerciseId: String, delta: Int): Boolean =
    moveExercise(session, exerciseId, delta) != session

/** Appends an exercise (all sets pending) to the end of the workout. */
fun addExercise(
    session: Session,
    template: SessionExercise,
    sets: Int,
    newId: () -> String,
): Session {
    val count = sets.coerceAtLeast(1)
    val ex = template.copy(
        id = newId(),
        order = session.exercises.size + 1,
        plannedSets = count,
        sets = (1..count).map { SetLog(id = newId(), order = it, setType = SetType.WORKING, status = SetStatus.PENDING) },
    )
    return session.copy(exercises = session.exercises + ex)
}

/** Default template for an exercise that is added during the workout. */
fun templateFor(exerciseId: String, name: String, measurementType: String, loadDirection: String, loadBasis: String, rest: Int = 90): SessionExercise =
    SessionExercise(
        exerciseId = exerciseId, name = name, measurementType = measurementType, loadDirection = loadDirection,
        loadBasis = loadBasis, target = Target("reps", 8.0, 12.0, 1.0, 2.0), restSeconds = rest,
    )

/** A warm-up proposal: about half of the working weight on the nearest level, 12 reps. */
data class WarmupProposal(val weightKg: Double, val reps: Int)

fun warmupProposal(workingTotalKg: Double, levels: List<Double>): WarmupProposal {
    val half = workingTotalKg * 0.5
    val weight = if (levels.isEmpty()) (half / 2.5).roundToInt() * 2.5 else levels.sorted()[nearestLevelIndex(levels.sorted(), half)]
    return WarmupProposal(weight.coerceAtLeast(0.0), 12)
}

/**
 * Inserts a pending warm-up set before the first pending working set of the exercise. It is logged
 * with the normal flow and never counts for progression.
 */
fun insertWarmup(session: Session, exerciseId: String, newId: () -> String): Session =
    session.copy(
        exercises = session.exercises.map { ex ->
            if (ex.id != exerciseId) ex
            else {
                val at = ex.sets.indexOfFirst { it.status == SetStatus.PENDING }.let { if (it < 0) ex.sets.size else it }
                val warm = SetLog(id = newId(), order = 0, setType = SetType.WARMUP, status = SetStatus.PENDING)
                ex.copy(sets = renumber(ex.sets.take(at) + warm + ex.sets.drop(at)))
            }
        },
    )

/** Most working sets one exercise can have (same limit as the assistant's `set_sets`). */
const val MAX_WORKING_SETS = 20

/** Whether [addSet] would add a set: the exercise exists, is not skipped as a whole and has fewer than [MAX_WORKING_SETS] working sets. */
fun canAddSet(session: Session, exerciseId: String): Boolean {
    val ex = session.exercises.firstOrNull { it.id == exerciseId } ?: return false
    return !isSkipped(ex) && ex.sets.count { it.setType == SetType.WORKING } < MAX_WORKING_SETS
}

/**
 * Adds one more pending working set at the end of the exercise, the same kind of set the plan or the assistant
 * (`set_sets`) creates: it is logged, skipped and synced like any other and counts for progression only once it is
 * logged and meets the target. A weight the assistant planned for the open sets is carried over, so the new set starts like
 * its neighbours. `planned_sets` stays what was planned. Returns the session unchanged if [canAddSet] is false.
 */
fun addSet(session: Session, exerciseId: String, newId: () -> String): Session {
    if (!canAddSet(session, exerciseId)) return session
    return session.copy(
        exercises = session.exercises.map { ex ->
            if (ex.id != exerciseId) ex
            else {
                val planned = ex.sets.lastOrNull { it.status == SetStatus.PENDING && it.setType == SetType.WORKING }?.weightKg
                ex.copy(sets = renumber(ex.sets + SetLog(id = newId(), setType = SetType.WORKING, status = SetStatus.PENDING, weightKg = planned)))
            }
        },
    )
}

/** Adds a note to an exercise of the session. */
fun setExerciseNote(session: Session, exerciseId: String, note: String): Session =
    session.copy(exercises = session.exercises.map { if (it.id == exerciseId) it.copy(notes = note.ifBlank { null }) else it })

/** Mode set counts for a new plan exercise. */
val DefaultModeSets = ModeSets(normal = 3, fast = 2, ultra = 1)
