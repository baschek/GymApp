package de.baschek.gymapp.core.progression

import de.baschek.gymapp.core.model.EffortLowerBound
import de.baschek.gymapp.core.model.EffortSource
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

/**
 * Suggestion for the next set during a workout (watch design spec K, "Nächster Satz im Training"; double progression):
 * - value at or above the upper bound of the target AND reserve at least the target RIR: the next available step
 *   ([nextStepUp]: next main level, an add-on first when that jump is very large; assisted exercises: less assistance);
 * - value inside the target range, or reserve too low: hold the weight;
 * - clear miss (below the lower bound at the limit, or two steps of the metric short): one step lighter (assisted: more
 *   assistance).
 * Seconds and meters work like reps. Only a suggestion: the prefill of the next set does not change by itself.
 * [availableWeightsKg] are the main levels of the machine (or every selectable total when [addonWeightsKg] is null),
 * [addonWeightsKg] its add-on weights. Null for warm-up and non-completed sets, without effort, or without a weight.
 */
@Suppress("UNUSED_PARAMETER") // bodyWeightKg: kept for callers; the step rule does not need the body weight
fun suggestNextWeight(
    set: SetLog,
    target: Target,
    measurementType: String,
    availableWeightsKg: List<Double>? = null,
    bodyWeightKg: Double? = null,
    addonWeightsKg: List<Double>? = null,
): WeightSuggestion? {
    if (set.status != SetStatus.COMPLETED || set.setType == SetType.WARMUP) return null
    val effort = effortLb(set.effort) ?: return null
    val levels = availableWeightsKg.orEmpty()
    val addons = addonWeightsKg.orEmpty()
    val weight = set.weightKg ?: return null
    val achieved: Double = when (target.metric) {
        Metric.SECONDS -> set.seconds?.toDouble()
        Metric.METERS -> set.meters
        else -> set.reps?.toDouble()
    } ?: return null
    if (achieved <= 0.0) return null
    val assisted = measurementType == MeasurementType.ASSISTED_REPS
    val loaded = measurementType == MeasurementType.LOAD_REPS || measurementType == MeasurementType.ADDED_WEIGHT_REPS ||
        measurementType == MeasurementType.LOAD_DURATION || measurementType == MeasurementType.LOAD_DISTANCE_DURATION
    if (!assisted && !loaded) return null
    if (weight <= 0.0 && measurementType != MeasurementType.ADDED_WEIGHT_REPS) return null

    val step = metricStep(target.metric)
    val unit = when (target.metric) { Metric.SECONDS -> " s"; Metric.METERS -> " m"; else -> " reps" }
    val result = "${fmt(achieved)}$unit at ${fmt(weight)} kg with $effort or more reps in reserve"
    val evidence = "Current working set: $result."
    val topReached = achieved >= target.max && effort >= target.rirMin
    val clearMiss = achieved < target.min && (effort == 0 || achieved <= target.min - 2 * step)
    val timed = measurementType == MeasurementType.LOAD_DURATION || measurementType == MeasurementType.LOAD_DISTANCE_DURATION

    if (topReached) {
        val next = if (assisted) nextStepDown(weight, levels) else nextStepUp(weight, levels, addons)
        if (next != null) {
            return WeightSuggestion(
                weightKg = next,
                reason = when {
                    assisted -> "Try less assistance"
                    timed -> "Try the next available load"
                    else -> "This set supports a heavier trial"
                },
                detail = "The top of the target (${fmt(target.max)}) was reached with at least ${fmt(target.rirMin)} reps in reserve; " +
                    "${fmt(next)} kg is the next available step.",
                evidence = evidence,
            )
        }
    }
    if (clearMiss) {
        val lighter = if (assisted) nextStepUp(weight, levels) else nextStepDown(weight, levels)
        if (lighter != null) {
            return WeightSuggestion(
                weightKg = lighter,
                reason = if (assisted) "Use more assistance" else "Use one step less",
                detail = "Clearly below the target (${fmt(target.min)}–${fmt(target.max)}); ${fmt(lighter)} kg is one step ${if (assisted) "more assistance" else "lighter"}.",
                evidence = evidence,
            )
        }
    }
    return WeightSuggestion(
        weightKg = weight,
        reason = "Use this target",
        detail = if (achieved >= target.max) "The top of the target was reached without the required reserve; keep ${fmt(weight)} kg."
        else "Keep ${fmt(weight)} kg until the top of the target (${fmt(target.max)}) is reached with reserve.",
        evidence = evidence,
    )
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

private fun achievedValue(set: SetLog, target: Target): Double? = when (target.metric) {
    Metric.REPS -> set.reps?.toDouble()
    Metric.SECONDS -> set.seconds?.toDouble()
    else -> set.meters
}

internal fun setMeetsTarget(set: SetLog, target: Target): Boolean {
    if (set.status != SetStatus.COMPLETED || set.setType != SetType.WORKING) return false
    val lb = effortLb(set.effort) ?: return false
    val actual = achievedValue(set, target) ?: return false
    if (actual < target.min) return false
    return lb >= target.rirMin
}

internal fun qualifyingWeights(exercise: SessionExercise): List<Double> =
    exercise.sets.filter { it.weightKg != null && setMeetsTarget(it, exercise.target) }.map { it.weightKg!! }

/** Days without this exercise after which the first working set cannot prove a new weight alone. */
const val LONG_BREAK_DAYS = 14L

private fun improves(weight: Double, currentWeightKg: Double, lower: Boolean) =
    if (lower) weight < currentWeightKg - 1e-9 else weight > currentWeightKg + 1e-9

/** A later set at the same weight that speaks against a proof: failed, below the target, or (entered) too little reserve. */
private fun contradicts(set: SetLog, target: Target): Boolean {
    if (set.setType != SetType.WORKING) return false
    if (set.status == SetStatus.FAILED) return true
    if (set.status != SetStatus.COMPLETED) return false
    val actual = achievedValue(set, target) ?: return false
    if (actual < target.min) return true
    val lb = effortLb(set.effort)
    return set.effortSource == EffortSource.USER && lb != null && lb < target.rirMin
}

/**
 * Single-set proof (watch design spec K, "Nächstes Training"): a weight beyond [currentWeightKg] counts as proven by ONE
 * working set when its value is at least the lower bound plus one step ([metricStep]: 9 at 8–12), its reserve reaches
 * the target RIR, and that reserve was entered by the person ([EffortSource.USER]; a preselected band does not count).
 * Guards: the first working set after a break of more than [LONG_BREAK_DAYS] days ([afterLongBreak]) is no proof, and a
 * later set at the same weight in this session must not contradict it. Needs an existing working weight. Returns the
 * best such weight (highest; assisted: lowest) or null.
 */
fun singleSetProvenWeight(exercise: SessionExercise, currentWeightKg: Double?, afterLongBreak: Boolean = false): Double? {
    if (currentWeightKg == null) return null
    val target = exercise.target
    val lower = exercise.loadDirection == LoadDirection.LOWER_ASSISTANCE
    val sets = exercise.sets
    val firstWorking = sets.indexOfFirst { it.setType == SetType.WORKING && it.status == SetStatus.COMPLETED }
    val proofs = sets.withIndex().filter { (i, set) ->
        val weight = set.weightKg
        val actual = achievedValue(set, target)
        weight != null && actual != null &&
            set.status == SetStatus.COMPLETED && set.setType == SetType.WORKING &&
            set.effortSource == EffortSource.USER && (effortLb(set.effort) ?: -1) >= target.rirMin &&
            actual >= target.min + metricStep(target.metric) &&
            improves(weight, currentWeightKg, lower) &&
            !(afterLongBreak && i == firstWorking) &&
            sets.drop(i + 1).none { later -> later.weightKg != null && abs(later.weightKg - weight) < 1e-9 && contradicts(later, target) }
    }.map { it.value.weightKg!! }
    if (proofs.isEmpty()) return null
    return if (lower) proofs.min() else proofs.max()
}

/** The classic rule: two qualifying working sets in one session; the second best qualifying weight. */
private fun twoSetProvenWeight(exercise: SessionExercise, currentWeightKg: Double?): Double? {
    val qualifying = qualifyingWeights(exercise)
    if (qualifying.size < 2) return null
    val sorted = qualifying.sorted()
    val lower = exercise.loadDirection == LoadDirection.LOWER_ASSISTANCE
    val candidate = if (lower) sorted[1] else sorted[sorted.size - 2]
    if (currentWeightKg == null) return candidate
    return if (improves(candidate, currentWeightKg, lower)) candidate else null
}

/**
 * The new working weight this session proves, or null: the better of the two-set rule and the single-set rule
 * ([singleSetProvenWeight], only with entered reserve). [afterLongBreak]: the previous session with this exercise (same
 * gym) is more than [LONG_BREAK_DAYS] days ago.
 */
fun provenWorkingWeight(exercise: SessionExercise, currentWeightKg: Double?, afterLongBreak: Boolean = false): Double? {
    val two = twoSetProvenWeight(exercise, currentWeightKg)
    val one = singleSetProvenWeight(exercise, currentWeightKg, afterLongBreak)
    val lower = exercise.loadDirection == LoadDirection.LOWER_ASSISTANCE
    return when {
        two == null -> one
        one == null -> two
        lower -> minOf(one, two)
        else -> maxOf(one, two)
    }
}

/** Whether the proven weight comes from the single-set rule (for the evidence texts). */
fun provenBySingleSet(exercise: SessionExercise, currentWeightKg: Double?, afterLongBreak: Boolean = false): Boolean {
    val proven = provenWorkingWeight(exercise, currentWeightKg, afterLongBreak) ?: return false
    val two = twoSetProvenWeight(exercise, currentWeightKg)
    return two == null || abs(two - proven) > 1e-9
}

fun progressionEvidence(exercise: SessionExercise, currentWeightKg: Double?, afterLongBreak: Boolean = false): String {
    val qualifying = qualifyingWeights(exercise)
    val proven = provenWorkingWeight(exercise, currentWeightKg, afterLongBreak)
    if (proven != null) {
        if (provenBySingleSet(exercise, currentWeightKg, afterLongBreak)) {
            return "${fmt(proven)} kg is proven by one strong working set with reported reserve and will be saved when the workout finishes."
        }
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
