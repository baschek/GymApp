package de.baschek.gymapp.core.progression

/*
 * Add-on weights (contract v1 amendment C). The total weight of a set is
 * "main level + selected add-ons"; progression only ever sees the total.
 */

private fun r3(v: Double) = Math.round(v * 1000.0) / 1000.0

/**
 * All subsets of [addons] (at most two are expected), the empty subset first. Equal values are two
 * separate plates ([2.3, 2.3] gives +2.3 and +4.6).
 */
fun addonSubsets(addons: List<Double>): List<List<Double>> {
    val a = addons.filter { it > 0.0 }.sorted().take(2)
    var out = listOf<List<Double>>(emptyList())
    for (x in a) out = out + out.map { it + x }
    return out.distinct().sortedBy { it.size }
}

/**
 * Indices of the add-on buttons that show [selected] as chosen. Equal values are matched to the
 * buttons from left to right, so [2.3] on the machine [2.3, 2.3] lights only the first button.
 */
fun selectedAddonIndices(selected: List<Double>, addons: List<Double>): Set<Int> {
    val out = mutableSetOf<Int>()
    for (s in selected) {
        val i = addons.indices.firstOrNull { it !in out && kotlin.math.abs(addons[it] - s) < 1e-6 } ?: continue
        out += i
    }
    return out
}

/** [selected] with button [index] switched on or off; every button toggles on its own. */
fun toggleAddonIndex(selected: Set<Int>, index: Int): Set<Int> = if (index in selected) selected - index else selected + index

/** The add-on values of the chosen button [selected] indices (ascending), ignoring indices outside [addons]. */
fun addonValuesAt(selected: Set<Int>, addons: List<Double>): List<Double> =
    selected.filter { it in addons.indices }.map { addons[it] }.sorted()

/**
 * Candidate totals for progression rounding: every main level plus every subset of
 * the add-ons, sorted ascending and de-duplicated. Without levels the result is empty
 * (callers then use free steps).
 */
fun weightCandidates(levels: List<Double>, addons: List<Double>): List<Double> {
    if (levels.isEmpty()) return emptyList()
    val subsets = addonSubsets(addons).map { it.sum() }
    return levels.flatMap { l -> subsets.map { r3(l + it) } }.distinct().sorted()
}

/** Largest candidate not above [value]; the smallest candidate if all are above. */
fun roundDownToCandidate(value: Double, levels: List<Double>, addons: List<Double>): Double =
    roundDownToLevel(value, weightCandidates(levels, addons).ifEmpty { null })

/** A total weight expressed as one main level plus a subset of the add-ons. */
data class WeightSplit(val levelKg: Double, val addonsKg: List<Double>) {
    val totalKg: Double get() = r3(levelKg + addonsKg.sum())
}

/**
 * Splits [total] into a main level and add-ons. Prefers an exact match with the fewest add-ons
 * (then the highest level); otherwise the largest level not above the total with no add-ons
 * (the smallest level if all are above).
 */
fun splitTotal(total: Double, levels: List<Double>, addons: List<Double>): WeightSplit? {
    if (levels.isEmpty()) return null
    val sorted = levels.sorted()
    for (subset in addonSubsets(addons)) {
        val level = sorted.lastOrNull { kotlin.math.abs(r3(it + subset.sum()) - total) < 1e-6 }
        if (level != null) return WeightSplit(level, subset)
    }
    val level = sorted.lastOrNull { it <= total + 1e-9 } ?: sorted.first()
    return WeightSplit(level, emptyList())
}

/** Distinct sorted level index closest to [value] (ties go down). */
fun nearestLevelIndex(levels: List<Double>, value: Double): Int {
    if (levels.isEmpty()) return -1
    var best = 0
    for (i in levels.indices) if (kotlin.math.abs(levels[i] - value) < kotlin.math.abs(levels[best] - value) - 1e-9) best = i
    return best
}

/** Step size used when a gym has no machine profile for an exercise. */
const val FREE_STEP_KG = 2.5

/** Selectable weights without a machine profile: [FREE_STEP_KG] steps up to [maxKg]. */
fun freeWeights(maxKg: Double = 300.0): List<Double> {
    val n = (maxKg / FREE_STEP_KG).toInt()
    return (1..n).map { r3(it * FREE_STEP_KG) }
}

/** True when [total] is exactly one selectable total of the machine profile (a level, optionally plus add-ons). */
fun isSelectableWeight(total: Double, levels: List<Double>, addons: List<Double>): Boolean =
    weightCandidates(levels, addons).any { kotlin.math.abs(it - total) < 1e-6 }

/** The values of the ascending [sorted] list directly below and directly above [value] (strictly, null at the ends). */
fun neighborWeights(sorted: List<Double>, value: Double): Pair<Double?, Double?> =
    sorted.lastOrNull { it < value - 1e-6 } to sorted.firstOrNull { it > value + 1e-6 }
