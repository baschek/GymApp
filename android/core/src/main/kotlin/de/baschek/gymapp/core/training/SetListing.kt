package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.EffortLowerBound
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Texts for the set list of the current exercise ("1  140 kg × 12 · 1–2 übrig"). Pure and German so that
 * the wording is covered by tests.
 */

/** German number without trailing zeros: 142.5 -> "142,5", 60.0 -> "60". */
fun deNum(value: Double): String =
    BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

/** Effort band as short label: "Am Limit", "1–2 übrig", "3–4 übrig", "5+ übrig". Null for unknown values. */
fun effortLabel(effort: String?): String? = when (effort?.let { EffortLowerBound[it] }) {
    null -> null
    0 -> "Am Limit"
    1 -> "1–2 übrig"
    2 -> "2–3 übrig"
    3 -> "3–4 übrig"
    4 -> "4+ übrig"
    else -> "5+ übrig"
}

/** "bei 1–2 übrig" / "am Limit" for running text. */
fun effortPhrase(effort: String?): String? = when (val l = effortLabel(effort)) {
    null -> null
    "Am Limit" -> "am Limit"
    else -> "bei $l"
}

/** Why a set was skipped, short ("Zeit"); null when no reason is known. */
fun skipReasonLabel(status: String): String? = when (status) {
    SetStatus.SKIPPED_TIME -> "Zeit"
    SetStatus.SKIPPED_EQUIPMENT -> "Gerät besetzt"
    SetStatus.PAIN -> "Schmerz"
    else -> null
}

enum class SetRowKind { DONE, CURRENT, OPEN, SKIPPED }

/**
 * One line of the set list. [label] is "1", "2", ... for working sets and "W" for warm-up sets,
 * [primary] the values or the target, [secondary] effort (done) or the reason (skipped).
 */
data class SetRow(val setId: String, val label: String, val kind: SetRowKind, val primary: String, val secondary: String?)

private fun unitSuffix(metric: String) = when (metric) {
    Metric.SECONDS -> " s"
    Metric.METERS -> " m"
    else -> ""
}

/** Reps, seconds or meters of a logged set as number (per the target metric). */
fun setValue(ex: SessionExercise, set: SetLog): Double? = when (ex.target.metric) {
    Metric.SECONDS -> set.seconds?.toDouble()
    Metric.METERS -> set.meters
    else -> set.reps?.toDouble()
}

/** "140 kg × 12", "12 Wdh.", "45 s", "20 kg × 45 s". */
fun describeResult(ex: SessionExercise, set: SetLog): String {
    val value = setValue(ex, set)
    val valueText = value?.let { deNum(it) + unitSuffix(ex.target.metric) }
    val weight = set.weightKg?.let { deNum(it) + " kg" }
    return when {
        weight != null && valueText != null -> "$weight × $valueText"
        weight != null -> weight
        valueText != null -> if (ex.target.metric == Metric.REPS) "$valueText Wdh." else valueText
        else -> "–"
    }
}

/** "Ziel 8–12" (reps), "Ziel 30–45 s". Empty target gives "offen". */
fun describeTarget(ex: SessionExercise): String {
    val min = ex.target.min.toInt()
    val max = ex.target.max.toInt()
    if (max <= 0) return "offen"
    val unit = unitSuffix(ex.target.metric)
    return if (min == max || min <= 0) "Ziel $max$unit" else "Ziel $min–$max$unit"
}

/** The set list of an exercise; the first open set is marked as current. */
fun setRows(ex: SessionExercise): List<SetRow> {
    var working = 0
    var currentTaken = false
    return ex.sets.map { set ->
        val label = if (set.setType == SetType.WARMUP) "W" else (++working).toString()
        when (set.status) {
            SetStatus.COMPLETED -> SetRow(set.id, label, SetRowKind.DONE, describeResult(ex, set), effortLabel(set.effort))
            SetStatus.PENDING -> {
                val kind = if (!currentTaken) SetRowKind.CURRENT else SetRowKind.OPEN
                currentTaken = true
                val text = if (set.setType == SetType.WARMUP) "Aufwärmsatz" else describeTarget(ex)
                SetRow(set.id, label, kind, text, if (kind == SetRowKind.CURRENT) "aktueller Satz" else "offen")
            }
            else -> SetRow(
                set.id, label, SetRowKind.SKIPPED,
                if (set.status == SetStatus.FAILED) "nicht geschafft" else "übersprungen", skipReasonLabel(set.status),
            )
        }
    }
}
