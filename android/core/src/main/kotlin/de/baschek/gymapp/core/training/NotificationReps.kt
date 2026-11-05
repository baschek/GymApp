package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.Metric
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reps (seconds, meters) the user set with "−1 / +1" in the workout notification for the next open set. It is keyed to
 * one set of one exercise of one session, so it stops applying by itself as soon as another set is next (logged, jumped
 * to another exercise, undone, session over); the app also discards it explicitly. Persisted as JSON in the settings
 * table, shared by the notification action and the set entry of the app.
 */
@Serializable
data class RepsOverride(val sessionId: String, val exerciseId: String, val setId: String, val value: Double)

/** Pure rules for the notification buttons "−1 Wdh" / "+1 Wdh" and the prefill text. */
object NotificationReps {
    const val MAX_VALUE = 999.0

    /** Step of one button press: 1 rep, 5 seconds, 10 meters (same as the wheel in the app). */
    fun step(metric: String): Double = when (metric) {
        Metric.SECONDS -> 5.0
        Metric.METERS -> 10.0
        else -> 1.0
    }

    /** [current] moved by [direction] (−1/+1) steps, kept within one step and [MAX_VALUE]; null if already at the bound. */
    fun adjusted(current: Double, direction: Int, metric: String): Double? {
        val step = step(metric)
        val moved = (current + direction * step).coerceIn(step, MAX_VALUE)
        return moved.takeIf { Math.abs(it - current) > 1e-9 }
    }

    /** True if [override] belongs to exactly this open set. */
    fun applies(override: RepsOverride?, sessionId: String, exerciseId: String, setId: String): Boolean =
        override != null && override.sessionId == sessionId && override.exerciseId == exerciseId && override.setId == setId

    /** The override value for this set, or null (none, or it belongs to another set). */
    fun valueFor(override: RepsOverride?, sessionId: String, exerciseId: String, setId: String): Double? =
        override?.takeIf { applies(it, sessionId, exerciseId, setId) }?.value

    /**
     * The override after one button press: [base] is the value that would be logged right now (current override or
     * prefill). Returns the unchanged existing override (or null) when the bound is reached.
     */
    fun press(
        existing: RepsOverride?, sessionId: String, exerciseId: String, setId: String,
        base: Double, direction: Int, metric: String,
    ): RepsOverride? {
        val kept = existing?.takeIf { applies(it, sessionId, exerciseId, setId) }
        val moved = adjusted(base, direction, metric) ?: return kept
        return RepsOverride(sessionId, exerciseId, setId, moved)
    }

    /**
     * Third notification action: while a rest is really running (planned end still ahead, or a rest without planned end)
     * it is "Pause überspringen", otherwise (no rest, or the planned rest is over) "Satz geschafft".
     */
    fun showsSkipRest(isRest: Boolean, endsAtMillis: Long?, nowMillis: Long): Boolean =
        isRest && (endsAtMillis == null || endsAtMillis > nowMillis)

    fun encode(override: RepsOverride): String = GymJson.encodeToString(RepsOverride.serializer(), override)

    fun decode(raw: String?): RepsOverride? =
        raw?.let { runCatching { GymJson.decodeFromString(RepsOverride.serializer(), it) }.getOrNull() }
            ?.takeIf { it.value.isFinite() && it.value > 0.0 }

    /** "170", "17,5". */
    fun formatNumber(value: Double): String =
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

    /**
     * Notification text for what "Satz geschafft" would save, e.g. "170 kg × 11 · 1–2 übrig"; without weight "11 Wdh" /
     * "45 s". The unit of reps is left out behind a weight ("170 kg × 11").
     */
    fun summary(weightKg: Double?, value: Double, metric: String, effort: String?, labels: PrefillLabels): String {
        val number = formatNumber(value)
        val unit = when (metric) {
            Metric.SECONDS -> labels.seconds
            Metric.METERS -> labels.meters
            else -> labels.reps
        }
        val result = when {
            weightKg != null && metric == Metric.REPS -> "${formatNumber(weightKg)} ${labels.kg} × $number"
            weightKg != null -> "${formatNumber(weightKg)} ${labels.kg} × $number $unit"
            else -> "$number $unit"
        }
        val effortText = when (effort) {
            "limit" -> labels.effortLimit
            "one", "one_two" -> labels.effortOneTwo
            "two_three", "three_four" -> labels.effortThreeFour
            "four_plus", "five_plus" -> labels.effortFivePlus
            else -> null
        }
        return listOfNotNull(result, effortText).joinToString(" · ")
    }
}

/** Words used by [NotificationReps.summary] (from string resources in the app). */
data class PrefillLabels(
    val kg: String,
    val reps: String,
    val seconds: String,
    val meters: String,
    val effortLimit: String,
    val effortOneTwo: String,
    val effortThreeFour: String,
    val effortFivePlus: String,
)
