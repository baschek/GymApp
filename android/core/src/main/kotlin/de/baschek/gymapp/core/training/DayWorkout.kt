package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.DayWorkout
import de.baschek.gymapp.core.model.DayWorkoutStatus
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.PackItem
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.WorkoutMode

/*
 * One-off workout for one day (contract v1 amendment E). Pure rules; the repository does the I/O.
 */

/** The workout is offered on its day only; a day workout of an earlier day is expired whatever its stored status says. */
fun DayWorkout.isExpired(today: String): Boolean = status == DayWorkoutStatus.OFFERED && date < today

/** Offered for [today]: status `offered`, dated today, with at least one exercise to do in the normal mode. */
fun DayWorkout.isOfferedOn(today: String): Boolean =
    status == DayWorkoutStatus.OFFERED && date == today && exercises.any { it.modeSets.forMode(WorkoutMode.NORMAL) > 0 }

/** The day workout to show on "Heute": offered for [today]; with several (should not happen) the newest one wins. */
fun <T> pickOfferedDayWorkout(all: List<Pair<T, DayWorkout>>, today: String): Pair<T, DayWorkout>? =
    all.filter { it.second.isOfferedOn(today) }.maxByOrNull { it.second.createdAt }

/** The day workout as a (never stored) plan, so a session is built by the same code as for a plan. */
fun DayWorkout.asPlan(): Plan = Plan(name = title, notes = note, createdAt = createdAt, exercises = exercises)

/** The extra packing items of this workout, sorted; their checked state is local like the gym packing list. */
fun DayWorkout.extraPackItems(): List<PackItem> = packlist.orEmpty().sortedBy { it.order }

/** Local-only key of the checked state of a day workout's packing items (kept in the settings table). */
fun dayPackKey(documentId: String): String = "day:$documentId"

fun DayWorkout.markStarted(sessionId: String, nowIso: String): DayWorkout =
    copy(status = DayWorkoutStatus.STARTED, sessionId = sessionId, resolvedAt = nowIso)

fun DayWorkout.markDismissed(nowIso: String): DayWorkout =
    copy(status = DayWorkoutStatus.DISMISSED, resolvedAt = nowIso)

fun DayWorkout.markExpired(nowIso: String): DayWorkout =
    copy(status = DayWorkoutStatus.EXPIRED, resolvedAt = nowIso)

private fun num(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

/** "Bankdrücken · 4 × 6–8" (sets of the normal mode, target range; seconds/meters get their unit). */
fun describeDayExercise(pe: PlanExercise, name: String?): String {
    val sets = pe.modeSets.forMode(WorkoutMode.NORMAL)
    val unit = when (pe.target.metric) {
        Metric.SECONDS -> " s"
        Metric.METERS -> " m"
        else -> ""
    }
    val range = when {
        pe.target.max <= 0.0 -> null
        pe.target.min <= 0.0 || pe.target.min == pe.target.max -> num(pe.target.max) + unit
        else -> num(pe.target.min) + "–" + num(pe.target.max) + unit
    }
    val volume = listOfNotNull(sets.takeIf { it > 0 }?.toString(), range).joinToString(" × ")
    return listOfNotNull(name ?: pe.exerciseId, volume.takeIf { it.isNotEmpty() }).joinToString(" · ")
}
