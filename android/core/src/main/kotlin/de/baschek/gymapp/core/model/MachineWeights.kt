package de.baschek.gymapp.core.model

/**
 * Selectable weights of a machine: the explicit list if present, otherwise
 * generated from the configured ranges (sorted, de-duplicated, capped).
 */
fun MachineProfile.effectiveWeights(maxValues: Int = 400): List<Double> {
    availableWeightsKg?.takeIf { it.isNotEmpty() }?.let { return it.distinct().sorted() }
    val out = sortedSetOf<Double>()
    for (r in weightRanges.orEmpty()) {
        if (r.stepKg <= 0.0 || r.maxKg < r.minKg) continue
        var i = 0
        while (out.size < maxValues) {
            val w = r.minKg + i * r.stepKg
            if (w > r.maxKg + 1e-9) break
            out += Math.round(w * 1000.0) / 1000.0
            i++
        }
    }
    return out.toList()
}

/** Add-on weights of the machine (0 to 2 positive values, sorted, de-duplicated; amendment C). */
fun MachineProfile.effectiveAddons(): List<Double> =
    addonWeightsKg.orEmpty().filter { it > 0.0 }.map { Math.round(it * 1000.0) / 1000.0 }.distinct().sorted().take(MAX_ADDONS)

const val MAX_ADDONS = 2

/** The levels of a machine as "from / to / step" for the editor. */
data class RangeSpec(val minKg: Double, val maxKg: Double, val stepKg: Double) {
    val count: Int get() = if (stepKg <= 0.0 || maxKg < minKg) 0 else Math.floor((maxKg - minKg) / stepKg + 1e-9).toInt() + 1
    val levels: List<Double>
        get() = if (count == 0) emptyList() else (0 until count).map { Math.round((minKg + it * stepKg) * 1000.0) / 1000.0 }
}

/**
 * Range for the machine editor: the single configured range, else inferred from the effective
 * levels (smallest gap as the step). Null when the machine has no levels.
 */
fun MachineProfile.rangeSpec(): RangeSpec? {
    if (availableWeightsKg.isNullOrEmpty()) {
        weightRanges.orEmpty().singleOrNull()?.takeIf { it.stepKg > 0 }?.let { return RangeSpec(it.minKg, it.maxKg, it.stepKg) }
    }
    val levels = effectiveWeights()
    if (levels.isEmpty()) return null
    val step = levels.zipWithNext { a, b -> b - a }.filter { it > 0 }.minOrNull() ?: 5.0
    return RangeSpec(levels.first(), levels.last(), Math.round(step * 1000.0) / 1000.0)
}

/** Whether the levels of the machine are exactly the evenly spaced [spec] (so editing it loses nothing). */
fun MachineProfile.levelsMatch(spec: RangeSpec): Boolean = effectiveWeights() == spec.levels
