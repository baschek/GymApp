package de.baschek.gymapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.baschek.gymapp.ui.theme.GymAppTheme
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing
import kotlinx.coroutines.delay

/** Segment meter above the exercise: done, current, still open. */
@Composable
fun SetMeter(total: Int, doneCount: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total.coerceAtLeast(1)) { i ->
            val color = when {
                i < doneCount -> cs.onSurfaceVariant
                i == doneCount -> cs.primary
                else -> cs.surfaceContainerHigh
            }
            Box(Modifier.weight(1f).height(5.dp).background(color, RoundedCornerShape(3.dp)))
        }
    }
}

/** One selectable add-on weight under the wheel. */
data class AddonOption(val label: String, val selected: Boolean)

/**
 * The weight block of the set card: the TOTAL weight (main level + selected add-ons) large on top, the
 * wheel over the machine's main levels, add-on weights as selection buttons (accent when selected) and
 * the reason for the suggestion. Without a machine profile there is one plain line of text and a link.
 *
 * @param newWeight pulses once (scale 1.08, one haptic tick) and shows "Neues Gewicht" for 2 s
 */
@Composable
fun WeightCard(
    totalText: String,
    previousLevel: String?,
    currentLevel: String,
    nextLevel: String?,
    onStep: (Int) -> Unit,
    addons: List<AddonOption>,
    onToggleAddon: (Int) -> Unit,
    reason: String?,
    hasProfile: Boolean,
    onCreateProfile: () -> Unit,
    modifier: Modifier = Modifier,
    machineSetting: String? = null,
    newWeight: Boolean = false,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    var pulse by remember { mutableStateOf(false) }
    var badge by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(newWeight, totalText.takeIf { newWeight }) {
        if (newWeight) {
            pulse = true
            badge = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(350)
            pulse = false
            delay(1650)
            badge = false
        }
    }
    val scale by animateFloatAsState(if (pulse) 1.08f else 1f, tween(200), label = "weightScale")

    GymCardSurface(modifier, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Overline("Gewicht")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        totalText, Modifier.scale(scale), style = GymTextStyles.displayWeight, color = cs.onSurface,
                    )
                    Text("kg", Modifier.padding(start = 6.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
            }
            if (badge) StatusBadge(BadgeKind.OK, GymIcons.Check, "Neues Gewicht")
            else if (machineSetting != null) Text(machineSetting, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        WheelRow(
            previous = previousLevel, current = currentLevel, next = nextLevel, unit = "kg", onStep = onStep, label = "Gewicht",
            modifier = Modifier.padding(top = 4.dp), rowHeight = 30.dp, stepDistance = 20.dp,
            minusEnabled = minusEnabled, plusEnabled = plusEnabled,
        )
        if (hasProfile && addons.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                addons.forEachIndexed { i, a -> AddonButton(a, { onToggleAddon(i) }, Modifier.weight(1f)) }
            }
        }
        if (!hasProfile) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Kein Maschinenprofil, daher freie Schritte von 2,5 kg. ", style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false),
                )
            }
            LinkButton("Profil anlegen", onCreateProfile, height = Spacing.Touch)
        } else if (reason != null) {
            Text(reason, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** Add-on weight: a selection button, not a switch. Tap to select (accent), tap again to deselect. */
@Composable
fun AddonButton(option: AddonOption, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Spacing.Touch)
            .semantics { stateDescription = if (option.selected) "ausgewählt" else "nicht ausgewählt"; role = Role.Switch },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (option.selected) cs.primary else cs.surfaceContainerHigh,
            contentColor = if (option.selected) cs.onPrimary else cs.onSurface,
        ),
    ) {
        Text(option.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Reps block: target in the caption, wheel with − and +. */
@Composable
fun RepsCard(
    caption: String,
    previous: String?,
    current: String,
    next: String?,
    unit: String,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Wiederholungen",
    minusEnabled: Boolean = true,
) {
    GymCardSurface(modifier, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Overline(caption)
        WheelRow(
            previous, current, next, unit, onStep, label, Modifier.padding(top = 6.dp), rowHeight = 36.dp, stepDistance = 24.dp,
            minusEnabled = minusEnabled,
        )
    }
}

/** Effort after a set: Am Limit, 1–2 übrig, 3–4 übrig, 5+ locker. */
@Composable
fun EffortPicker(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Overline("Anstrengung", Modifier.padding(bottom = 6.dp))
        Segmented(
            listOf(SegmentItem("Am Limit"), SegmentItem("1–2", "übrig"), SegmentItem("3–4", "übrig"), SegmentItem("5+", "locker")),
            selected, onSelect, tall = true,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun SetCardPreview() {
    GymAppTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SetMeter(3, 1)
            WeightCard(
                "57,5", "50", "55", "60", {}, listOf(AddonOption("+ 1,25 kg", false), AddonOption("+ 2,5 kg", true)), {},
                "+2,5 kg: zuletzt 2× am oberen Ende des Ziels.", hasProfile = true, onCreateProfile = {}, machineSetting = "Sitz 4, Lehne 2",
            )
            RepsCard("Wiederholungen · Ziel 8–12", "9", "10", "11", "Wdh.", {})
            EffortPicker(1, {})
        }
    }
}
