package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.progression.isSelectableWeight
import de.baschek.gymapp.core.progression.nearestLevelIndex
import de.baschek.gymapp.core.progression.neighborWeights
import de.baschek.gymapp.core.progression.addonValuesAt
import de.baschek.gymapp.core.progression.selectedAddonIndices
import de.baschek.gymapp.core.progression.toggleAddonIndex
import de.baschek.gymapp.core.progression.splitTotal
import de.baschek.gymapp.core.progression.weightCandidates
import de.baschek.gymapp.core.training.describeTarget
import de.baschek.gymapp.core.training.setValue as resultValue
import de.baschek.gymapp.core.model.SelectableEffortBands
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.components.AddonButton
import de.baschek.gymapp.ui.components.AddonOption
import de.baschek.gymapp.ui.components.EffortPicker
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymField
import de.baschek.gymapp.ui.components.LinkButton
import de.baschek.gymapp.ui.components.WheelRow
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.parseNum
import de.baschek.gymapp.ui.theme.Spacing
import androidx.compose.material3.MaterialTheme
import de.baschek.gymapp.ui.theme.gymColors
import de.baschek.gymapp.ui.tracked

/** Editable values of one set (the current set, or a logged set that is being corrected). */
internal class SetDraft(
    val initialTotal: Double, level: Double, addons: List<Double>, value: Double, effort: Int,
    private val addonList: List<Double> = emptyList(),
) {
    var level by mutableDoubleStateOf(level)

    /** Chosen add-on buttons by position, so two equal add-ons stay two separate buttons. */
    var selectedIdx by mutableStateOf(selectedAddonIndices(addons, addonList))

    /** Values of the chosen add-ons (sorted), derived from [selectedIdx]. */
    val addons: List<Double> get() = addonValuesAt(selectedIdx, addonList)
    var value by mutableDoubleStateOf(value)
    var effort by mutableIntStateOf(effort)

    /** Exact total typed by the user that is not a selectable total; null while level + add-ons define the weight. */
    var custom by mutableStateOf<Double?>(null)

    val total: Double get() = custom ?: (Math.round((level + addons.sum()) * 1000.0) / 1000.0)

    /**
     * Takes a typed [total] exactly. A selectable total is split into level and add-ons as usual; any other
     * value is kept as typed ([custom]) and [level] only follows it so the wheel shows the surrounding levels.
     */
    fun applyExact(total: Double, levels: List<Double>, addonList: List<Double>, wheel: List<Double>) {
        val exact = if (levels.isEmpty()) wheel.any { kotlin.math.abs(it - total) < 1e-6 } else isSelectableWeight(total, levels, addonList)
        if (exact) {
            applyTotal(total, levels, addonList)
        } else {
            selectedIdx = emptySet()
            level = wheel[nearestLevelIndex(wheel, total)]
            custom = Math.round(total * 1000.0) / 1000.0
        }
    }

    /**
     * Sets the total weight. With a machine profile the nearest selectable total is used and split into
     * level and add-ons; without a profile the value is taken as typed.
     */
    fun applyTotal(total: Double, levels: List<Double>, addonList: List<Double>) {
        custom = null
        if (levels.isEmpty()) {
            level = total
            selectedIdx = emptySet()
            return
        }
        val candidates = weightCandidates(levels, addonList)
        val chosen = if (candidates.isEmpty()) total else candidates[nearestLevelIndex(candidates, total)]
        val split = splitTotal(chosen, levels, addonList)
        level = split?.levelKg ?: chosen
        selectedIdx = selectedAddonIndices(split?.addonsKg ?: emptyList(), this.addonList)
    }

    /** Effort band to log (null for warm-up sets). */
    fun effortBand(warm: Boolean): String? = if (warm) null else SelectableEffortBands[effort]
}

/** Draft for the next open set; [accepted] is a weight the user took over from a suggestion. */
internal fun buildDraft(
    ex: SessionExercise?, set: SetLog?, levels: List<Double>, addons: List<Double>, wheel: List<Double>, accepted: Double? = null,
    reps: Double? = null,
): SetDraft {
    if (ex == null || set == null) return SetDraft(0.0, wheel.first(), emptyList(), 10.0, 1, addons)
    val warm = set.setType == SetType.WARMUP
    val total = accepted?.takeIf { !warm } ?: prefillTotal(ex, set, levels)
    val split = if (levels.isNotEmpty() && !warm) splitTotal(total, levels, addons) else null
    val level = split?.levelKg ?: wheel[nearestLevelIndex(wheel, total)]
    val last = lastWorking(ex)
    val lastValue = last?.let { resultValue(ex, it) }
    // reps set with "−1 / +1" in the notification win over the proposal
    val value = reps ?: if (warm) 12.0 else lastValue ?: ex.target.max
    val effort = if (warm) 1 else bandIndex(last?.effort).takeIf { it >= 0 } ?: prefillBand(ex)
    return SetDraft(total, level, split?.addonsKg ?: emptyList(), value.coerceAtLeast(valueStep(ex.target.metric)), effort, addons)
}

/** Draft from a logged set, for correcting it. Values are taken exactly as logged. */
internal fun buildDraftFromSet(ex: SessionExercise, set: SetLog, levels: List<Double>, addons: List<Double>, wheel: List<Double>): SetDraft {
    val total = set.weightKg ?: 0.0
    val stored = set.addonWeightsKg?.takeIf { it.isNotEmpty() }
    val split = if (levels.isNotEmpty() && total > 0.0 && stored == null) splitTotal(total, levels, addons) else null
    val level = when {
        stored != null -> Math.round((total - stored.sum()) * 1000.0) / 1000.0
        split != null -> split.levelKg
        levels.isEmpty() -> total
        else -> wheel[nearestLevelIndex(wheel, total)]
    }
    val chosenAddons = stored?.sorted() ?: split?.addonsKg ?: emptyList()
    val value = (resultValue(ex, set) ?: ex.target.max).coerceAtLeast(valueStep(ex.target.metric))
    val effort = bandIndex(set.effort).takeIf { it >= 0 } ?: 1
    return SetDraft(total, level, chosenAddons, value, effort, addons)
}

private enum class EntryDialog { WEIGHT, VALUE }

/**
 * The input block of a set: weight wheel (tap the number to type it), add-on buttons, [afterWeight]
 * (suggestion chip), reps/seconds/meters wheel and the effort bands. The wheels keep their mechanics
 * (drag, − and +) but are lower than before so the whole set screen fits one display.
 */
@Composable
internal fun SetEntryCard(
    ex: SessionExercise,
    draft: SetDraft,
    weighted: Boolean,
    warm: Boolean,
    levels: List<Double>,
    addonList: List<Double>,
    wheel: List<Double>,
    screen: String,
    modifier: Modifier = Modifier,
    onCreateProfile: (() -> Unit)? = null,
    /** Called when the user changes reps/seconds/meters by hand (drops the notification override). */
    onValueEdited: (() -> Unit)? = null,
    afterWeight: @Composable ColumnScope.() -> Unit = {},
) {
    var dialog by remember { mutableStateOf<EntryDialog?>(null) }
    val hasProfile = levels.isNotEmpty()
    GymCardSurface(modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        if (weighted) {
            val i = nearestLevelIndex(wheel, draft.level)
            val addonSum = if (warm) 0.0 else draft.addons.sum()
            val custom = draft.custom
            // A typed value that is no level: the wheel shows the levels directly around it.
            val around = custom?.let { neighborWeights(wheel, it) }
            WheelRow(
                previous = if (around != null) around.first?.let(::fmtNum) else wheel.getOrNull(i - 1)?.let { fmtNum(it + addonSum) },
                current = fmtNum(draft.total),
                next = if (around != null) around.second?.let(::fmtNum) else wheel.getOrNull(i + 1)?.let { fmtNum(it + addonSum) },
                unit = "kg",
                onStep = { d ->
                    Telemetry.track(screen, "weight_step")
                    if (around != null) {
                        (if (d < 0) around.first else around.second)?.let { draft.custom = null; draft.selectedIdx = emptySet(); draft.level = it }
                    } else {
                        draft.level = wheel[(i + d).coerceIn(0, wheel.lastIndex)]
                    }
                },
                label = "Gewicht",
                rowHeight = 40.dp, stepDistance = 20.dp,
                minusEnabled = if (around != null) around.first != null else i > 0,
                plusEnabled = if (around != null) around.second != null else i < wheel.lastIndex,
                neighborHeight = 16.dp, neighborSize = 12.sp, valueSize = 28.sp,
                onTapValue = tracked(screen, "weight_type") { dialog = EntryDialog.WEIGHT },
            )
            if (hasProfile && draft.custom != null && !isSelectableWeight(draft.total, levels, addonList)) {
                Text(
                    "Keine Stufe im Profil – trotzdem übernommen.", Modifier.padding(top = Spacing.s2),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.gymColors.skipped,
                )
            }
            if (!warm && hasProfile && addonList.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.s3), horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    val chosen = draft.selectedIdx
                    addonList.forEachIndexed { i, a ->
                        val on = i in chosen
                        AddonButton(
                            AddonOption("+ ${fmtNum(a)} kg", on),
                            {
                                Telemetry.track(screen, "toggle_addon")
                                draft.custom = null
                                // by button position, so two equal add-ons toggle separately
                                draft.selectedIdx = toggleAddonIndex(draft.selectedIdx, i)
                            },
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            if (!hasProfile && onCreateProfile != null) {
                LinkButton("Kein Maschinenprofil, freie 2,5-kg-Schritte. Stufen anlegen", onCreateProfile, height = 40.dp)
            }
            afterWeight()
        }

        val metric = ex.target.metric
        val step = valueStep(metric)
        WheelRow(
            previous = (draft.value - step).takeIf { it >= step }?.let(::fmtNum),
            current = fmtNum(draft.value),
            next = fmtNum(draft.value + step),
            unit = if (warm) valueUnit(metric) else "${valueUnit(metric)} · ${describeTarget(ex)}",
            onStep = { d ->
                Telemetry.track(screen, "reps_step")
                draft.value = (draft.value + d * step).coerceIn(step, 999.0)
                onValueEdited?.invoke()
            },
            label = when (metric) { Metric.SECONDS -> "Sekunden"; Metric.METERS -> "Meter"; else -> "Wiederholungen" },
            modifier = Modifier.padding(top = if (weighted) Spacing.s5 else 0.dp),
            rowHeight = 40.dp, stepDistance = 24.dp,
            minusEnabled = draft.value > step,
            neighborHeight = 16.dp, neighborSize = 12.sp, valueSize = 28.sp,
            onTapValue = tracked(screen, "value_type") { dialog = EntryDialog.VALUE },
        )
        if (!warm) {
            EffortPicker(draft.effort, { Telemetry.track(screen, "effort"); draft.effort = it }, Modifier.padding(top = Spacing.s5))
        }
    }

    when (dialog) {
        EntryDialog.WEIGHT -> NumberEntryDialog(
            title = "Gewicht eingeben", unit = "kg", initial = fmtNum(draft.total), decimal = true,
            note = if (hasProfile) "Der Wert wird exakt übernommen." else null,
            warn = { v -> if (hasProfile && !isSelectableWeight(v, levels, addonList)) "Keine Stufe im Profil – wird trotzdem übernommen." else null },
            onConfirm = { draft.applyExact(it, levels, addonList, wheel); dialog = null },
            onDismiss = { dialog = null },
        )
        EntryDialog.VALUE -> NumberEntryDialog(
            title = when (ex.target.metric) { Metric.SECONDS -> "Sekunden eingeben"; Metric.METERS -> "Meter eingeben"; else -> "Wiederholungen eingeben" },
            unit = valueUnit(ex.target.metric), initial = fmtNum(draft.value), decimal = ex.target.metric == Metric.METERS,
            note = null,
            onConfirm = {
                draft.value = if (ex.target.metric == Metric.METERS) it else Math.round(it).toDouble().coerceAtLeast(1.0)
                onValueEdited?.invoke()
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** Direct entry with the number keyboard. */
@Composable
internal fun NumberEntryDialog(
    title: String,
    unit: String,
    initial: String,
    decimal: Boolean,
    note: String?,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
    warn: ((Double) -> String?)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val parsed = parseNum(text)?.takeIf { it > 0.0 && it < 1000.0 }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                GymField(
                    text, { text = it.filter { c -> c.isDigit() || (decimal && (c == ',' || c == '.')) }.take(7) }, unit,
                    modifier = Modifier.focusRequester(focus),
                    keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                )
                parsed?.let { v -> warn?.invoke(v) }?.let {
                    Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = MaterialTheme.gymColors.skipped)
                }
                if (note != null) Text(note, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = tracked("training", "number_ok") { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = tracked("training", "number_cancel", onDismiss)) { Text("Abbrechen") } },
    )
}
