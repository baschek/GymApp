package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetType

/*
 * Left/right sets (contract v1 amendment I). An exercise marked `per_side` has two rep counts per set (`reps_left`,
 * `reps_right`) and ONE weight. Both sides are mandatory. `reps` always holds the minimum of the two, so progression,
 * statistics, the notification and every reader that knows nothing about sides keep working, and the weaker side decides.
 * Only exercises measured in reps can be per side.
 */

/** The exercise is done per side: its sets have a left and a right rep count. */
val SessionExercise.isPerSide: Boolean get() = perSide == true && target.metric == Metric.REPS

/** Reps that count for progression: the minimum of both sides, else plain [SetLog.reps] (also if a foreign writer only set one side). */
val SetLog.countedReps: Int?
    get() {
        val l = repsLeft
        val r = repsRight
        return if (l != null && r != null) minOf(l, r) else reps
    }

/** Total reps over both sides, for volume ("Tonnage"): left plus right, else plain [SetLog.reps]. */
val SetLog.totalReps: Int?
    get() {
        val l = repsLeft
        val r = repsRight
        return if (l != null && r != null) l + r else reps
    }

/**
 * Writes the reps of a logged set. For a per-side exercise [left] and [right] are stored together with
 * `reps = min(left, right)`; a missing side takes the value of the other one (the UI always sends both). For an ordinary
 * reps exercise [left] is the reps and the side fields are cleared. Other metrics leave the set untouched.
 */
fun SetLog.withReps(ex: SessionExercise, left: Double?, right: Double?): SetLog {
    if (ex.target.metric != Metric.REPS) return this
    // warm-up sets are never entered per side
    if (!ex.isPerSide || setType == SetType.WARMUP) return copy(reps = left?.toInt(), repsLeft = null, repsRight = null)
    val l = (left ?: right)?.toInt()
    val r = (right ?: left)?.toInt()
    return copy(reps = if (l != null && r != null) minOf(l, r) else null, repsLeft = l, repsRight = r)
}

/** "L 10 / R 12" (left first). */
fun describeSides(left: Int, right: Int): String = "L $left / R $right"

/** Which side of a left/right set a correction step changes. */
enum class RepSide { LEFT, RIGHT }

/**
 * One step (+1/-1) of the reps of a COMPLETED set in the history editor, 0..200. For a left/right exercise only [side] changes
 * and `reps` follows as the minimum of both sides, so the other side keeps its value exactly. Without a side (or for an
 * ordinary exercise) the plain reps change.
 */
fun SetLog.stepReps(ex: SessionExercise, direction: Int, side: RepSide?): SetLog {
    fun step(v: Int?) = ((v ?: 0) + direction).coerceIn(0, 200)
    if (!ex.isPerSide || side == null || setType == SetType.WARMUP) return copy(reps = step(reps), repsLeft = null, repsRight = null)
    val l = repsLeft ?: reps ?: 0
    val r = repsRight ?: reps ?: 0
    val nl = if (side == RepSide.LEFT) step(l) else l
    val nr = if (side == RepSide.RIGHT) step(r) else r
    return copy(reps = minOf(nl, nr), repsLeft = nl, repsRight = nr)
}
