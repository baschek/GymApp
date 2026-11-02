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
