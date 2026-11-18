package de.baschek.gymapp.core.progression

import de.baschek.gymapp.core.model.Metric
import kotlin.math.ceil
import kotlin.math.floor

/*
 * Weight steps of a machine (watch design spec K, "Sprunggröße"): progression always moves by the next available step.
 * When the next main level is a very large jump, an add-on weight comes first (micro step). Per-hand exercises use the
 * per-hand levels of their profile (dumbbells), so a step is one dumbbell up or down. Without a machine profile the free
 * [FREE_STEP_KG] steps apply.
 */

/** A jump to the next main level larger than this share of the current weight is "very large" (add-on first). */
const val LARGE_STEP_RATIO = 0.10

private fun r3(v: Double) = Math.round(v * 1000.0) / 1000.0
private const val EPS = 1e-6

/**
 * The next selectable weight above [currentKg]: the next main level of [levels]; if that jump is larger than
 * [LARGE_STEP_RATIO] of [currentKg] and a level plus add-ons lies in between, the smallest such total first. Above the
 * highest level only add-on combinations remain. Without levels the next [FREE_STEP_KG] step. Null when nothing is above.
 */
fun nextStepUp(currentKg: Double, levels: List<Double>, addons: List<Double> = emptyList()): Double? {
    if (levels.isEmpty()) return r3((floor(currentKg / FREE_STEP_KG + 1e-9) + 1) * FREE_STEP_KG)
    val sorted = levels.distinct().sorted()
    val next = sorted.firstOrNull { it > currentKg + EPS }
    val micro = weightCandidates(sorted, addons).firstOrNull { it > currentKg + EPS && (next == null || it < next - EPS) }
    if (next == null) return micro
    if (micro != null && currentKg > 0 && (next - currentKg) / currentKg > LARGE_STEP_RATIO) return micro
    return next
}

/** The next main level below [currentKg] (add-ons are dropped); without levels the next lower [FREE_STEP_KG] step. Null at the bottom. */
fun nextStepDown(currentKg: Double, levels: List<Double>): Double? {
    if (levels.isEmpty()) return r3((ceil(currentKg / FREE_STEP_KG - 1e-9) - 1) * FREE_STEP_KG).takeIf { it > 0.0 }
    return levels.distinct().sorted().lastOrNull { it < currentKg - EPS }
}

/**
 * The smallest meaningful step of the target metric: 1 rep, 5 seconds, 10 meters (the same steps as the reps wheel and
 * the notification buttons). Used for "lower bound + 1" and for "clearly below the target".
 */
fun metricStep(metric: String): Double = when (metric) {
    Metric.SECONDS -> 5.0
    Metric.METERS -> 10.0
    else -> 1.0
}
