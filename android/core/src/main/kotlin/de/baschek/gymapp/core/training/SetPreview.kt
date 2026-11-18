package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.EffortSource
import de.baschek.gymapp.core.model.MachineProfile
import de.baschek.gymapp.core.model.MeasurementType
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SelectableEffortBands
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.SetupParameter
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.model.effectiveAddons
import de.baschek.gymapp.core.model.effectiveWeights
import de.baschek.gymapp.core.progression.WEIGHTED_TYPES
import de.baschek.gymapp.core.progression.prefillSet
import de.baschek.gymapp.core.progression.targetEffortBand
import de.baschek.gymapp.core.progression.weightCandidates

/*
 * Data for the set and rest screens (watch design spec D, E, F), pure so phone and watch show the same: the target
 * range, the next set with its values and the weight suggestion, the machine setup before a new exercise, and the
 * effort picker shown after each logged set.
 */

/** "Fußplatte 4,5 · Sitz 3"; empty without parameters. */
fun setupText(parameters: List<SetupParameter>?): String =
    parameters.orEmpty().filter { it.name.isNotBlank() || it.value.isNotBlank() }
        .joinToString(" · ") { listOf(it.name, it.value.replace('.', ',')).filter(String::isNotBlank).joinToString(" ") }

/** "8–12" (reps), "60–90 s", "12" when min = max; empty without a target. */
fun targetRangeText(target: Target): String {
    val min = target.min.toInt()
    val max = target.max.toInt()
    if (max <= 0) return ""
    val unit = when (target.metric) { Metric.SECONDS -> " s"; Metric.METERS -> " m"; else -> "" }
    return if (min == max || min <= 0) "$max$unit" else "$min–$max$unit"
}

/** The small grey line under the values: "Ziel 8–12", "Ziel 60–90 s"; empty without a target. */
fun targetLine(target: Target): String = targetRangeText(target).let { if (it.isEmpty()) "" else "Ziel $it" }

/**
 * The set that comes next.
 * @property weightKg prefilled TOTAL weight (null for exercises without weight)
 * @property valueText "170 kg × 10–12" (the target range, as on the rest screen), "10–12 Wdh.", "45–60 s"
 * @property targetText "Ziel 8–12"
 * @property newExercise the set belongs to another exercise than the one just finished (show the name and [setupText])
 * @property setupText machine setup of that exercise, e.g. "Fußplatte 4,5"; empty if none
 * @property hint weight suggestion from the last working set of that exercise ([weightHint]); null if none
 * @property suggestionText "170 → 180 kg ▲" when the suggestion differs from the prefill, else null
 */
data class SetPreview(
    val exerciseId: String,
    val exerciseName: String,
    val setId: String,
    val setNumber: Int,
    val setTotal: Int,
    val warmup: Boolean,
    val weightKg: Double?,
    val value: Double,
    val metric: String,
    val valueText: String,
    val targetText: String,
    val newExercise: Boolean,
    val setupText: String,
    val hint: WeightHint?,
    val suggestionText: String?,
)

/**
 * Preview of the next open set after [justDoneExerciseId] (the exercise of the set just logged, or the focus): the
 * focus exercise if it still has open sets, else [nextAfter]. [machineFor] returns the machine profile of an exercise
 * (catalog exercise id) in the session's gym. Null when no set is open.
 */
fun nextSetPreview(
    session: Session,
    focusExerciseId: String?,
    justDoneExerciseId: String?,
    machineFor: (exerciseId: String) -> MachineProfile?,
): SetPreview? {
    val exercises = session.exercises
    val index = upNextExerciseIndex(exercises, focusExerciseId)
    val ex = exercises.getOrNull(index) ?: return null
    val set = ex.sets.firstOrNull { it.status == SetStatus.PENDING } ?: return null
    val machine = machineFor(ex.exerciseId)
    val levels = machine?.effectiveWeights().orEmpty()
    val addons = machine?.effectiveAddons().orEmpty()
    val prefill = prefillSet(ex, weightCandidates(levels, addons))
    val weighted = ex.measurementType in WEIGHTED_TYPES
    val warm = set.setType == SetType.WARMUP
    val hint = if (!weighted || warm) null else weightHint(ex, levels, machine?.workingWeightKg, addons = addons)
    val weight = if (weighted) prefill.weightKg else null
    val suggestionText = hint?.takeIf { weight != null && kotlin.math.abs(it.weightKg - weight) > 1e-6 }
        ?.let { "${deNum(weight!!)} → ${deNum(it.weightKg)} kg ${it.arrow}".trimEnd() }
    val working = ex.sets.count { it.setType == SetType.WORKING }
    val doneWorking = ex.sets.count { it.setType == SetType.WORKING && it.status != SetStatus.PENDING }
    return SetPreview(
        exerciseId = ex.id,
        exerciseName = ex.name,
        setId = set.id,
        setNumber = if (warm) 0 else minOf(doneWorking + 1, working),
        setTotal = working,
        warmup = warm,
        weightKg = weight,
        value = prefill.value,
        metric = ex.target.metric,
        valueText = previewValueText(ex, weight),
        targetText = targetLine(ex.target),
        newExercise = justDoneExerciseId != null && ex.id != justDoneExerciseId,
        setupText = setupText(machine?.setupParameters),
        hint = hint,
        suggestionText = suggestionText,
    )
}

private fun previewValueText(ex: SessionExercise, weightKg: Double?): String {
    val range = targetRangeText(ex.target)
    val label = when (ex.measurementType) {
        MeasurementType.ASSISTED_REPS -> " kg Hilfe"
        else -> " kg"
    }
    val value = when {
        range.isEmpty() -> ""
        ex.target.metric == Metric.REPS && weightKg == null -> "$range Wdh."
        else -> range
    }
    return listOfNotNull(weightKg?.let { deNum(it) + label }, value.takeIf { it.isNotEmpty() }).joinToString(" × ")
}

// ---- effort picker (spec E) -----------------------------------------------------------------

/** How long the effort picker waits before the preselected band is saved (as [EffortSource.AUTO]). */
const val EFFORT_PICK_TIMEOUT_MILLIS = 5_000L

/** One choice of the effort picker. */
data class EffortChoice(val band: String, val label: String, val preselected: Boolean)

/** Label of a band in the picker: "Am Limit", "1–2 übrig", "3–4 übrig", "5+ locker". */
fun effortPickLabel(band: String): String = when (band) {
    "limit" -> "Am Limit"
    "one_two" -> "1–2 übrig"
    "three_four" -> "3–4 übrig"
    "five_plus" -> "5+ locker"
    else -> effortLabel(band) ?: band
}

/** The four bands, the one matching the target reserve preselected. */
fun effortChoices(target: Target): List<EffortChoice> {
    val pre = targetEffortBand(target)
    return SelectableEffortBands.map { EffortChoice(it, effortPickLabel(it), it == pre) }
}

/** Effort and its source to save: a tapped band ([chosen]) is [EffortSource.USER]; no choice saves the preselection as [EffortSource.AUTO]. */
data class EffortEntry(val effort: String, val source: String)

fun resolveEffort(chosen: String?, target: Target): EffortEntry =
    if (chosen != null && chosen in SelectableEffortBands) EffortEntry(chosen, EffortSource.USER)
    else EffortEntry(targetEffortBand(target), EffortSource.AUTO)
