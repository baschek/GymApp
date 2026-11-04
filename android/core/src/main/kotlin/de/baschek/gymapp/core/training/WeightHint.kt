package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.LoadDirection
import de.baschek.gymapp.core.model.MeasurementType
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.progression.nearestLevelIndex
import de.baschek.gymapp.core.progression.provenWorkingWeight
import de.baschek.gymapp.core.progression.qualifyingWeights
import de.baschek.gymapp.core.progression.suggestNextWeight
import kotlin.math.abs
import kotlin.math.min

/*
 * Weight suggestion after a working set, with its origin in plain German: what the last set was, what
 * the rule concluded, and how far the exercise is from a permanent new working weight. The numbers come
 * from the progression rules (suggestNextWeight, provenWorkingWeight); only the wording lives here.
 */

/**
 * @property weightKg suggested TOTAL weight (a selectable level or level + add-ons)
 * @property trend +1 higher than the last set, 0 the same, -1 lower
 * @property reason one line, e.g. "Letzter Satz 12 × bei 1–2 übrig → nächste Stufe"
 * @property detail how the rule got there
 * @property evidence progress towards a permanent working weight, e.g. "1 von 2 Sätzen für dauerhaft 65 kg"
 */
data class WeightHint(
    val weightKg: Double,
    val trend: Int,
    val reason: String,
    val detail: String,
    val evidence: String,
) {
    val chipText: String get() = "${deNum(weightKg)} kg übernehmen"
    val arrow: String get() = when { trend > 0 -> "▲"; trend < 0 -> "▼"; else -> "" }
}

/** The last logged working set of the exercise. */
fun lastWorkingSet(ex: SessionExercise): SetLog? =
    ex.sets.lastOrNull { it.status == SetStatus.COMPLETED && it.setType == SetType.WORKING }

private fun targetRirText(ex: SessionExercise): String {
    val lo = ex.target.rirMin.toInt()
    val hi = ex.target.rirMax.toInt()
    return if (lo == hi) "$lo übrig" else "$lo–$hi übrig"
}

/**
 * Suggestion for the next set from the last working set of [ex], or null when there is none (no working
 * set logged, no effort recorded, or the rule has nothing to say). [candidates] are the selectable total
 * weights of the machine (levels and add-on combinations; empty without a profile).
 * [workingWeightKg] is the proven working weight of this gym x exercise, if any.
 */
fun weightHint(ex: SessionExercise, candidates: List<Double>, workingWeightKg: Double?, bodyWeightKg: Double? = null): WeightHint? {
    val last = lastWorkingSet(ex) ?: return null
    val lastWeight = last.weightKg ?: return null
    val sorted = candidates.sorted()
    val s = suggestNextWeight(last, ex.target, ex.measurementType, sorted.ifEmpty { null }, bodyWeightKg) ?: return null
    val chosen = s.weightKg
    val trend = when {
        abs(chosen - lastWeight) < 1e-6 -> 0
        chosen > lastWeight -> 1
        else -> -1
    }
    val metric = ex.target.metric
    val value = setValue(ex, last)?.let { deNum(it) + if (metric == Metric.SECONDS) " s" else if (metric == Metric.METERS) " m" else "" }
    val effort = effortPhrase(last.effort)
    val base = listOfNotNull("Letzter Satz", value?.let { if (metric == Metric.REPS) "$it ×" else it }, effort).joinToString(" ")

    val assisted = ex.measurementType == MeasurementType.ASSISTED_REPS || ex.loadDirection == LoadDirection.LOWER_ASSISTANCE
    val steps = if (sorted.isEmpty()) null else abs(nearestLevelIndex(sorted, chosen) - nearestLevelIndex(sorted, lastWeight))
    val verdict = when {
        trend == 0 -> "Gewicht halten"
        assisted && trend < 0 -> if (steps == 1) "weniger Unterstützung (nächste Stufe)" else "weniger Unterstützung"
        trend > 0 -> if (steps == 1) "nächste Stufe" else "mehr Gewicht"
        else -> if (steps == 1) "eine Stufe leichter" else "leichter"
    }
    val reason = "$base → $verdict"
    val detail = buildString {
        append("Basis: Arbeitssatz mit ${describeResult(ex, last)}")
        if (effort != null) append(", $effort")
        append(". Die App rechnet daraus auf dein Ziel (${describeTarget(ex).removePrefix("Ziel ")}, ${targetRirText(ex)}) und nimmt ")
        append(if (sorted.isEmpty()) "das nächste 0,5-kg-Gewicht: " else "die nächste Stufe deiner Maschine: ")
        append("${deNum(chosen)} kg.")
    }
    return WeightHint(chosen, trend, reason, detail, progressionEvidenceDe(ex, workingWeightKg))
}

/**
 * German version of the progression evidence: how many qualifying working sets (target reached with the
 * required reserve) this workout has towards a permanent new working weight.
 * "1 von 2 Sätzen für dauerhaft 65 kg" – two qualifying sets make the weight permanent when the workout ends.
 */
fun progressionEvidenceDe(ex: SessionExercise, currentWeightKg: Double?): String {
    val qualifying = qualifyingWeights(ex)
    val proven = provenWorkingWeight(ex, currentWeightKg)
    if (proven != null) {
        return "${deNum(proven)} kg ist durch zwei passende Arbeitssätze belegt und wird beim Beenden als Arbeitsgewicht gespeichert."
    }
    if (currentWeightKg == null) {
        return "${min(qualifying.size, 1)} von 2 passenden Arbeitssätzen für ein dauerhaftes Startgewicht."
    }
    val lower = ex.loadDirection == LoadDirection.LOWER_ASSISTANCE
    val better = qualifying.filter { if (lower) it < currentWeightKg else it > currentWeightKg }
    if (better.isEmpty()) {
        val dir = if (lower) "unter ${deNum(currentWeightKg)} kg Unterstützung" else "über ${deNum(currentWeightKg)} kg"
        return "0 von 2 passenden Sätzen $dir. Zwei davon in diesem Training machen das neue Gewicht dauerhaft."
    }
    val candidate = if (lower) better.min() else better.max()
    return "1 von 2 Sätzen für dauerhaft ${deNum(candidate)} kg (bisher ${deNum(currentWeightKg)} kg). Ein zweiter passender Satz macht es beim Beenden dauerhaft."
}
