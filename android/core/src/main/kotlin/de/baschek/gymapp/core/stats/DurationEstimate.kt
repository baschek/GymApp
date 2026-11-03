package de.baschek.gymapp.core.stats

import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import kotlin.math.roundToInt

/** Estimated workout length. [learnedFrom] is the number of real sessions it is based on (0 = formula). */
data class DurationEstimate(val minutes: Int, val learnedFrom: Int)

private const val MAX_SAMPLES = 5
private const val MIN_VALID_SECONDS = 10 * 60
private const val MAX_VALID_SECONDS = 3 * 60 * 60

private fun validDuration(s: Session): Int? =
    s.durationSeconds?.takeIf { s.status == SessionStatus.COMPLETED && it in MIN_VALID_SECONDS..MAX_VALID_SECONDS }

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private fun totalSets(exercises: List<PlanExercise>, mode: String): Int = exercises.sumOf { it.modeSets.forMode(mode).coerceAtLeast(0) }

/**
 * Duration of a plan in a mode, learned from real workouts:
 * 1. median of the last up to 5 completed sessions of the same plan and mode;
 * 2. else the same plan in other modes (last up to 5), each scaled by the ratio of the set counts;
 * 3. else the formula [estimateMinutes].
 * Durations under 10 min or over 3 h and sessions without a duration are ignored.
 */
fun estimateDuration(planId: String?, exercises: List<PlanExercise>, mode: String, sessions: List<Session>): DurationEstimate {
    if (planId != null) {
        val own = sessions.filter { it.planId == planId }.sortedByDescending { it.effectiveTime }
        val same = own.filter { it.mode == mode }.mapNotNull(::validDuration).take(MAX_SAMPLES)
        if (same.isNotEmpty()) return DurationEstimate((median(same.map { it.toDouble() }) / 60.0).roundToInt(), same.size)

        val target = totalSets(exercises, mode)
        if (target > 0) {
            val scaled = own.filter { it.mode != mode }.mapNotNull { s ->
                val d = validDuration(s) ?: return@mapNotNull null
                val source = totalSets(exercises, s.mode)
                if (source <= 0) null else d.toDouble() * target / source
            }.take(MAX_SAMPLES)
            if (scaled.isNotEmpty()) return DurationEstimate((median(scaled) / 60.0).roundToInt(), scaled.size)
        }
    }
    return DurationEstimate(estimateMinutes(exercises, mode), 0)
}

/** "≈ 52 min" / "≈ 1 h 5 min". */
fun formatEstimate(minutes: Int): String = "≈ " + if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
