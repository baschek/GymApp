package de.baschek.gymapp.core.stats

import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.progression.estimatedOneRepMax

enum class TrendStatus { NOT_ENOUGH_DATA, RISING, FLAT, STAGNATING }

/** Per session (oldest first): best working weight and best estimated one-rep max of one exercise. */
data class ExerciseTrend(
    val dates: List<String>,
    val weights: List<Double>,
    val oneRepMax: List<Double>,
    val status: TrendStatus,
) {
    val best: Double? get() = weights.maxOrNull()
    val bestOneRepMax: Double? get() = oneRepMax.maxOrNull()
}

/** Minimum number of sessions before a trend is shown. */
const val MIN_TREND_SESSIONS = 3

/** Estimated one-rep max of a set: the progression formula, else Epley with no reserve. */
fun oneRepMaxOf(set: de.baschek.gymapp.core.model.SetLog): Double? {
    estimatedOneRepMax(set)?.let { return it }
    val w = set.weightKg ?: return null
    val r = set.reps ?: return null
    return if (w > 0 && r > 0) w * (1 + minOf(20, r) / 30.0) else null
}

/**
 * The trend of one exercise from completed sessions, optionally only those in one gym (weights of
 * different gyms are not comparable). "Stagniert" means: the last three sessions brought no new best
 * compared to everything before them (needs at least four sessions).
 */
fun buildTrend(sessions: List<Session>, exerciseId: String, gymId: String? = null): ExerciseTrend {
    val rows = sessions.asSequence()
        .filter { it.status == SessionStatus.COMPLETED && (gymId == null || it.gymId == gymId) }
        .sortedBy { it.effectiveTime }
        .mapNotNull { s ->
            val sets = s.exercises.filter { it.exerciseId == exerciseId }.flatMap { it.sets }
                .filter { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING && (it.weightKg ?: 0.0) > 0.0 }
            if (sets.isEmpty()) null
            else Triple(s.effectiveTime.take(10), sets.maxOf { it.weightKg!! }, sets.mapNotNull { oneRepMaxOf(it) }.maxOrNull() ?: 0.0)
        }.toList()
    val weights = rows.map { it.second }
    val status = when {
        weights.size < MIN_TREND_SESSIONS -> TrendStatus.NOT_ENOUGH_DATA
        weights.size >= 4 && weights.takeLast(3).max() <= weights.dropLast(3).max() -> TrendStatus.STAGNATING
        weights.last() > weights.first() -> TrendStatus.RISING
        else -> TrendStatus.FLAT
    }
    return ExerciseTrend(rows.map { it.first }, weights, rows.map { it.third }, status)
}
