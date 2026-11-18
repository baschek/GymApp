package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.LoadDirection
import de.baschek.gymapp.core.model.MeasurementType
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.progression.nearestLevelIndex
import de.baschek.gymapp.core.progression.provenBySingleSet
import de.baschek.gymapp.core.progression.weightCandidates
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
 * set logged, no effort recorded, or the rule has nothing to say). [levels] are the main levels of the machine
 * (empty without a profile: free 2.5 kg steps) and [addons] its add-on weights (micro steps before a very large jump).
 * [workingWeightKg] is the proven working weight of this gym x exercise, if any. The rule is [suggestNextWeight]
 * (top of the range with reserve: next step; inside the range: hold; clear miss: one step lighter).
 */
fun weightHint(
    ex: SessionExercise,
    levels: List<Double>,
    workingWeightKg: Double?,
    bodyWeightKg: Double? = null,
    addons: List<Double> = emptyList(),
    afterLongBreak: Boolean = false,
): WeightHint? {
    val last = lastWorkingSet(ex) ?: return null
    val lastWeight = last.weightKg ?: return null
    val sorted = levels.distinct().sorted()
    val s = suggestNextWeight(last, ex.target, ex.measurementType, sorted, bodyWeightKg, addons) ?: return null
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
    val candidates = weightCandidates(sorted, addons)
    val steps = if (candidates.isEmpty()) null else abs(nearestLevelIndex(candidates, chosen) - nearestLevelIndex(candidates, lastWeight))
    val micro = trend > 0 && !assisted && sorted.none { abs(it - chosen) < 1e-6 } && candidates.any { abs(it - chosen) < 1e-6 }
    val verdict = when {
        trend == 0 -> "Gewicht halten"
        assisted && trend < 0 -> if (steps == 1) "weniger Unterstützung (nächste Stufe)" else "weniger Unterstützung"
        assisted -> "mehr Unterstützung"
        micro -> "Zusatzgewicht"
        trend > 0 -> if (steps == 1) "nächste Stufe" else "mehr Gewicht"
        else -> if (steps == 1) "eine Stufe leichter" else "leichter"
    }
    val reason = "$base → $verdict"
    val target = describeTarget(ex).removePrefix("Ziel ")
    val detail = buildString {
        append("Basis: Arbeitssatz mit ${describeResult(ex, last)}")
        if (effort != null) append(", $effort")
        append(". Ziel $target bei ${targetRirText(ex)}: ")
        when {
            trend == 0 -> append("im Zielbereich bleibt das Gewicht, bis du die Obergrenze mit genug Reserve schaffst (${deNum(chosen)} kg).")
            (trend > 0) != assisted -> {
                append("Obergrenze mit genug Reserve geschafft, deshalb ")
                append(if (sorted.isEmpty()) "der nächste 2,5-kg-Schritt: " else if (micro) "zuerst ein Zusatzgewicht: " else "die nächste Stufe deiner Maschine: ")
                append("${deNum(chosen)} kg.")
            }
            else -> append("deutlich unter dem Ziel, deshalb eine Stufe ${if (assisted) "mehr Unterstützung" else "leichter"}: ${deNum(chosen)} kg.")
        }
    }
    return WeightHint(chosen, trend, reason, detail, progressionEvidenceDe(ex, workingWeightKg, afterLongBreak))
}

/**
 * German version of the progression evidence: how many qualifying working sets (target reached with the
 * required reserve) this workout has towards a permanent new working weight, or that one strong set with entered
 * reserve already proves it. "1 von 2 Sätzen für dauerhaft 65 kg".
 */
fun progressionEvidenceDe(ex: SessionExercise, currentWeightKg: Double?, afterLongBreak: Boolean = false): String {
    val qualifying = qualifyingWeights(ex)
    val proven = provenWorkingWeight(ex, currentWeightKg, afterLongBreak)
    if (proven != null) {
        if (provenBySingleSet(ex, currentWeightKg, afterLongBreak)) {
            return "${deNum(proven)} kg ist durch einen starken Satz mit angegebener Reserve belegt und wird beim Beenden als Arbeitsgewicht gespeichert."
        }
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
