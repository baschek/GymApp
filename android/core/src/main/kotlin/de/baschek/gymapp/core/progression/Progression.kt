package de.baschek.gymapp.core.progression

import de.baschek.gymapp.core.model.EffortLowerBound
import de.baschek.gymapp.core.model.LoadBasis
import de.baschek.gymapp.core.model.LoadDirection
import de.baschek.gymapp.core.model.MeasurementType
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.Target
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Port of the progression rules of the original web app (the semantics are
 * pinned by the shared golden cases in progression_golden.json). Texts are
 * kept identical to the original; the UI should map `reason` to its own copy.
 */

data class WeightSuggestion(
    val weightKg: Double,
    val reason: String,
    val detail: String,
    val evidence: String,
)

/** The parts of an exercise definition the progression rules need. */
data class ExerciseInfo(
    val id: String,
    val name: String = "",
    val measurementType: String = MeasurementType.LOAD_REPS,
    val loadDirection: String = LoadDirection.HIGHER,
    val loadBasis: String = LoadBasis.STACK,
    val movementFamily: String? = null,
    val equipment: String? = null,
)

/** JS-like number to string (25.0 -> "25", 22.5 -> "22.5"). */
internal fun fmt(value: Double): String =
    if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()

private fun toFixed1(value: Double): String =
    BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString()

/** JS Math.round semantics (half up), then scaled. */
private fun roundNearest(value: Double, step: Double = 0.5): Double =
    max(0.0, floor(value / step + 0.5) * step)

fun nearestAvailableWeight(value: Double, weights: List<Double>?): Double {
    if (weights.isNullOrEmpty()) return roundNearest(value)
    val sorted = weights.sorted()
    return sorted.reduce { closest, item -> if (abs(item - value) < abs(closest - value)) item else closest }
}

private fun effortLb(effort: String?): Int? = effort?.let { EffortLowerBound[it] }

fun estimatedOneRepMax(set: SetLog, baseLoadKg: Double = 0.0): Double? {
    val totalLoad = (set.weightKg ?: 0.0) + baseLoadKg
    val reps = set.reps
    val lb = effortLb(set.effort)
    if (totalLoad == 0.0 || reps == null || reps == 0 || lb == null) return null
    val effectiveReps = min(20, reps + lb)
    return totalLoad * (1 + effectiveReps / 30.0)
}

fun suggestNextWeight(
    set: SetLog,
    target: Target,
    measurementType: String,
    availableWeightsKg: List<Double>? = null,
    bodyWeightKg: Double? = null,
): WeightSuggestion? {
    if (set.status != SetStatus.COMPLETED || set.setType == SetType.WARMUP) return null
    val effort = effortLb(set.effort) ?: return null
    val weights = availableWeightsKg

    if (measurementType == MeasurementType.LOAD_REPS || measurementType == MeasurementType.ADDED_WEIGHT_REPS) {
        val baseLoadKg = if (measurementType == MeasurementType.ADDED_WEIGHT_REPS) (bodyWeightKg ?: 0.0) else 0.0
        val estimate = estimatedOneRepMax(set, baseLoadKg) ?: return null
        val reps = set.reps ?: return null
        val desiredEffectiveReps = target.max + (target.rirMin + target.rirMax) / 2
        val calculated = max(0.0, estimate / (1 + desiredEffectiveReps / 30) - baseLoadKg)
        val chosen = nearestAvailableWeight(calculated, weights)
        return WeightSuggestion(
            weightKg = chosen,
            reason = if (chosen > (set.weightKg ?: 0.0)) "This set supports a heavier trial" else "Use this target",
            detail = "Calculated ${toFixed1(calculated)} kg from $reps reps and at least $effort reps in reserve; ${fmt(chosen)} kg is the nearest available weight.",
            evidence = "Current working set: $reps reps at ${fmt(set.weightKg ?: 0.0)} kg with $effort or more reps in reserve.",
        )
    }

    val weight = set.weightKg
    if (measurementType == MeasurementType.ASSISTED_REPS && weight != null && weight != 0.0 && (set.reps ?: 0) != 0) {
        val sorted = (weights ?: emptyList()).sortedDescending()
        val idx = sorted.indexOfFirst { it == weight }
        if ((set.reps ?: 0) >= target.max && effort >= target.rirMin && idx >= 0 && idx < sorted.size - 1) {
            return WeightSuggestion(
                weightKg = sorted[idx + 1],
                reason = "Try less assistance",
                detail = "You reached the top of the target with reserve. Lower assistance is harder.",
                evidence = "Current working set reached ${set.reps} reps with at least $effort reps in reserve.",
            )
        }
    }

    if (weight != null && weight != 0.0 &&
        (measurementType == MeasurementType.LOAD_DURATION || measurementType == MeasurementType.LOAD_DISTANCE_DURATION)
    ) {
        val achieved: Double? = if (target.metric == Metric.METERS) set.meters else set.seconds?.toDouble()
        val sorted = (weights ?: emptyList()).sorted()
        val idx = sorted.indexOfFirst { it == weight }
        if (achieved != null && achieved != 0.0 && achieved >= target.max && effort >= target.rirMin &&
            idx >= 0 && idx < sorted.size - 1
        ) {
            return WeightSuggestion(
                weightKg = sorted[idx + 1],
                reason = "Try the next available load",
                detail = "The target duration or distance was reached with reserve.",
                evidence = "Current working set reached ${fmt(achieved)} ${target.metric} with at least $effort reps in reserve.",
            )
        }
    }
    return null
}

private fun normalizeTotalLoad(weightKg: Double, basis: String) =
    if (basis == LoadBasis.PER_HAND) weightKg * 2 else weightKg

private fun normalizeTargetLoad(weightKg: Double, basis: String) =
    if (basis == LoadBasis.PER_HAND) weightKg / 2 else weightKg

private fun dateOf(iso: String) = iso.take(10)

fun guidedStartingEstimate(
    target: ExerciseInfo,
    sessions: List<Session>,
    catalog: List<ExerciseInfo>,
    availableWeightsKg: List<Double>? = null,
): WeightSuggestion? {
    if (target.movementFamily == null ||
        target.measurementType !in listOf(MeasurementType.LOAD_REPS, MeasurementType.ADDED_WEIGHT_REPS)
    ) return null

    val byId = catalog.associateBy { it.id }
    val newestFirst = sessions
        .filter { it.status == SessionStatus.COMPLETED }
        .sortedByDescending { it.effectiveTime }

    for (session in newestFirst) {
        for (performed in session.exercises) {
            val source = byId[performed.exerciseId]
            if (source == null || source.id == target.id || source.movementFamily != target.movementFamily) continue
            val latest = performed.sets.asReversed().firstOrNull {
                it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING &&
                    it.weightKg != null && it.weightKg > 0
            } ?: continue
            val latestKg = latest.weightKg ?: continue
            val relatedTotal = normalizeTotalLoad(latestKg, performed.loadBasis)
            val conservative = normalizeTargetLoad(relatedTotal * 0.6, target.loadBasis)
            val chosen = nearestAvailableWeight(conservative, availableWeightsKg)
            return WeightSuggestion(
                weightKg = chosen,
                reason = "Conservative starting point from ${performed.name}",
                detail = "Uses 60% of the related ${fmt(relatedTotal)} kg total load (${toFixed1(conservative)} kg calculated) and selects the nearest available ${fmt(chosen)} kg. Treat this as a first-set check, not a strength prediction.",
                evidence = "Latest related working set: ${performed.name} on ${dateOf(session.effectiveTime)}.",
            )
        }
    }
    return null
}

fun historicalWorkingWeight(
    exerciseId: String,
    sessions: List<Session>,
    gymId: String,
    legacyDefaultGymId: String = "basic_fit",
): WeightSuggestion? {
    val latest = sessions
        .filter { s ->
            s.status == SessionStatus.COMPLETED && (s.gymId ?: legacyDefaultGymId) == gymId &&
                s.exercises.any { it.exerciseId == exerciseId }
        }
        .maxByOrNull { it.effectiveTime } ?: return null
    val performed = latest.exercises.firstOrNull { it.exerciseId == exerciseId }
    val weighted = performed?.sets?.filter {
        it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING && it.weightKg != null
    } ?: emptyList()
    if (weighted.isEmpty()) return null

    val counts = HashMap<Double, Int>()
    weighted.forEach { counts.merge(it.weightKg!!, 1, Int::plus) }
    val highest = counts.values.max()
    val representative = weighted.asReversed().first { counts[it.weightKg!!] == highest }.weightKg ?: return null
    return WeightSuggestion(
        weightKg = representative,
        reason = "Based on your latest workout for this exercise",
        detail = "Uses the most frequently recorded working weight from ${dateOf(latest.effectiveTime)} at the selected gym.",
        evidence = "${weighted.size} completed weighted working ${if (weighted.size == 1) "set" else "sets"} from that workout.",
    )
}

internal fun setMeetsTarget(set: SetLog, target: Target): Boolean {
    if (set.status != SetStatus.COMPLETED || set.setType != SetType.WORKING) return false
    val lb = effortLb(set.effort) ?: return false
    val actual: Double = when (target.metric) {
        Metric.REPS -> set.reps?.toDouble()
        Metric.SECONDS -> set.seconds?.toDouble()
        else -> set.meters
    } ?: return false
    if (actual < target.min) return false
    return lb >= target.rirMin
}

internal fun qualifyingWeights(exercise: SessionExercise): List<Double> =
    exercise.sets.filter { it.weightKg != null && setMeetsTarget(it, exercise.target) }.map { it.weightKg!! }

fun provenWorkingWeight(exercise: SessionExercise, currentWeightKg: Double?): Double? {
    val qualifying = qualifyingWeights(exercise)
    if (qualifying.size < 2) return null
    val sorted = qualifying.sorted()
    val lower = exercise.loadDirection == LoadDirection.LOWER_ASSISTANCE
    val candidate = if (lower) sorted[1] else sorted[sorted.size - 2]
    if (currentWeightKg == null) return candidate
    return if (lower) {
        if (candidate < currentWeightKg) candidate else null
    } else {
        if (candidate > currentWeightKg) candidate else null
    }
}

fun progressionEvidence(exercise: SessionExercise, currentWeightKg: Double?): String {
    val qualifying = qualifyingWeights(exercise)
    val proven = provenWorkingWeight(exercise, currentWeightKg)
    if (proven != null) {
        return "${fmt(proven)} kg is proven by two qualifying working sets and will be saved when the workout finishes."
    }
    if (currentWeightKg == null) {
        return "${min(qualifying.size, 1)} of 2 qualifying weighted working sets recorded for a permanent starting level."
    }
    val lower = exercise.loadDirection == LoadDirection.LOWER_ASSISTANCE
    val improving = qualifying.count { if (lower) it < currentWeightKg else it > currentWeightKg }
    val direction = if (lower) "below ${fmt(currentWeightKg)} kg assistance" else "above ${fmt(currentWeightKg)} kg"
    return "${min(improving, 1)} of 2 qualifying working sets $direction; two are required in this workout for a permanent change."
}

fun totalVolume(exercise: SessionExercise): Double =
    exercise.sets.fold(0.0) { sum, set ->
        if (set.status != SetStatus.COMPLETED) sum
        else sum + (set.weightKg ?: 0.0) * (set.reps ?: 0) * (if (exercise.loadBasis == LoadBasis.PER_HAND) 2 else 1)
    }
