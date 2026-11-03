package de.baschek.gymapp.core.stats

import de.baschek.gymapp.core.model.RadarArea
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.TargetSets
import de.baschek.gymapp.core.model.forArea
import java.time.Instant
import java.time.ZoneId

/** Period switch of the muscle map and the balance radar. */
enum class StatsPeriod(val days: Int) {
    TODAY(1),
    WEEK(7),
    MONTH(28),
}

/** Start of the period: local midnight of the first day (the week is the last 7 days, the month the last 28). */
fun StatsPeriod.start(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).toLocalDate().minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()

/** Share of the weekly target sets that applies to the period. */
fun goalFactor(period: StatsPeriod, trainingDaysPerWeek: Int): Double = when (period) {
    StatsPeriod.TODAY -> 1.0 / trainingDaysPerWeek.coerceIn(1, 7)
    StatsPeriod.WEEK -> 1.0
    StatsPeriod.MONTH -> 4.0
}

/** What one exercise contributed to one muscle group. */
data class ExerciseContribution(
    val exerciseId: String,
    val exerciseName: String,
    /** Completed working sets of the exercise (unweighted). */
    val sets: Int,
    /** Weighted sets: 1 per set for the primary group, 0.5 for every further group. */
    val weightedSets: Double,
    /** Newest session date (yyyy-MM-dd) with such a set. */
    val lastDate: String,
)

data class MuscleSummary(
    /** Weighted sets per muscle group (groups without sets are absent). */
    val totals: Map<String, Double>,
    val details: Map<String, List<ExerciseContribution>>,
) {
    val max: Double get() = totals.values.maxOrNull() ?: 0.0

    /** Groups sorted by weighted sets, highest first. */
    fun top(n: Int): List<Pair<String, Double>> = totals.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    companion object {
        val EMPTY = MuscleSummary(emptyMap(), emptyMap())
    }
}

/**
 * Counts completed working sets per muscle group. A set counts 1 for the primary (first) group of
 * its exercise and 0.5 for each further group (contract v1 amendment C). Only completed sessions
 * within [from] (inclusive) and [to] (exclusive, null = open) are considered.
 *
 * @param groupsOf muscle groups of an exercise id, first = primary
 */
fun summarizeMuscles(
    sessions: List<Session>,
    groupsOf: (exerciseId: String) -> List<String>,
    from: Instant,
    to: Instant? = null,
): MuscleSummary {
    val totals = HashMap<String, Double>()
    val acc = HashMap<String, MutableMap<String, ExerciseContribution>>()
    for (session in sessions) {
        if (session.status != SessionStatus.COMPLETED) continue
        val at = runCatching { Instant.parse(session.effectiveTime) }.getOrNull() ?: continue
        if (at < from || (to != null && at >= to)) continue
        for (ex in session.exercises) {
            val sets = ex.sets.count { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING }
            if (sets == 0) continue
            val groups = groupsOf(ex.exerciseId)
            groups.forEachIndexed { index, group ->
                val weight = if (index == 0) 1.0 else 0.5
                totals.merge(group, sets * weight, Double::plus)
                val byExercise = acc.getOrPut(group) { LinkedHashMap() }
                val old = byExercise[ex.exerciseId]
                val date = session.effectiveTime.take(10)
                byExercise[ex.exerciseId] = ExerciseContribution(
                    ex.exerciseId, ex.name,
                    (old?.sets ?: 0) + sets,
                    (old?.weightedSets ?: 0.0) + sets * weight,
                    maxOf(old?.lastDate.orEmpty(), date),
                )
            }
        }
    }
    return MuscleSummary(totals, acc.mapValues { (_, m) -> m.values.sortedByDescending { it.weightedSets } })
}

data class RadarAxis(val area: RadarArea, val value: Double, val goal: Double)

/** Per radar area: the weighted sets of the period against the goal scaled to the period. */
fun radarAxes(totals: Map<String, Double>, targets: TargetSets, period: StatsPeriod, trainingDaysPerWeek: Int): List<RadarAxis> {
    val factor = goalFactor(period, trainingDaysPerWeek)
    return RadarArea.entries.map { area ->
        RadarAxis(area, area.groups.sumOf { totals[it] ?: 0.0 }, targets.forArea(area) * factor)
    }
}

/** Areas with a goal that reached less than [threshold] of it ("Zu kurz gekommen"), biggest gap first. */
fun lowAreas(axes: List<RadarAxis>, threshold: Double = 0.7): List<RadarAxis> =
    axes.filter { it.goal > 0.0 && it.value < it.goal * threshold }.sortedBy { it.value / it.goal }

/** Radius (0..1) for drawing a radar value, with the largest value or goal at the rim. */
fun radarRadius(value: Double, axes: List<RadarAxis>): Double {
    val m = axes.maxOfOrNull { maxOf(it.value, it.goal) }?.times(1.05) ?: return 0.0
    return if (m <= 0.0) 0.0 else (value / m).coerceIn(0.0, 1.0)
}

/** Number formatting for set counts: "4" or "4,5". */
fun formatSets(value: Double): String =
    if (value == Math.floor(value)) value.toInt().toString() else String.format(java.util.Locale.GERMANY, "%.1f", value)
