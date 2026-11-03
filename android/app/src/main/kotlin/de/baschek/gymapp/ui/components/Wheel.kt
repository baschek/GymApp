package de.baschek.gymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.baschek.gymapp.ui.theme.GymAppTheme
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing

/**
 * Number wheel for reps and weight: the current value in the middle with its neighbours faded above and
 * below. Dragging up or down steps by one value per [stepDistance] (one haptic tick per step); the
 * round − and + buttons next to it ([WheelRow]) do the same without swiping.
 *
 * TalkBack gets the current value plus "erhöhen"/"verringern" actions.
 */
@Composable
fun Wheel(
    previous: String?,
    current: String,
    next: String?,
    unit: String?,
    onStep: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 34.dp,
    stepDistance: Dp = 20.dp,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val stepPx = with(density) { stepDistance.toPx() }
    val step by rememberUpdatedState(onStep)
    val haptic = LocalHapticFeedback.current
    val hapticState by rememberUpdatedState(haptic)
    val small = (rowHeight.value * 0.42f).sp
    val big = (rowHeight.value * 0.62f).sp

    Box(
        modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: $current ${unit.orEmpty()}".trim()
                customActions = listOf(
                    CustomAccessibilityAction("$label erhöhen") { step(1); true },
                    CustomAccessibilityAction("$label verringern") { step(-1); true },
                )
            }
            .pointerInput(stepPx) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    acc -= dragAmount // dragging up raises the value
                    while (acc >= stepPx) {
                        acc -= stepPx
                        step(1)
                        hapticState.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    while (acc <= -stepPx) {
                        acc += stepPx
                        step(-1)
                        hapticState.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(rowHeight).background(cs.surfaceContainerHigh, RoundedCornerShape(Spacing.RadiusMd)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                if (previous != null) Text(previous, style = GymTextStyles.number.copy(fontSize = small), color = cs.onSurfaceVariant.copy(alpha = 0.7f))
            }
            Box(Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(current, style = GymTextStyles.number.copy(fontSize = big, fontWeight = FontWeight.Bold), color = cs.onSurface)
                    if (unit != null) {
                        Text(unit, Modifier.padding(start = 5.dp, bottom = 2.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            Box(Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                if (next != null) Text(next, style = GymTextStyles.number.copy(fontSize = small), color = cs.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

/** Wheel with the round − button on the left and + on the right (56 dp each). */
@Composable
fun WheelRow(
    previous: String?,
    current: String,
    next: String?,
    unit: String?,
    onStep: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 34.dp,
    stepDistance: Dp = 20.dp,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
        RoundIconButton(GymIcons.Minus, "$label verringern", { onStep(-1) }, enabled = minusEnabled)
        Wheel(previous, current, next, unit, onStep, label, Modifier.weight(1f), rowHeight, stepDistance)
        RoundIconButton(GymIcons.Plus, "$label erhöhen", { onStep(1) }, enabled = plusEnabled)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF171717)
@Composable
private fun WheelPreview() {
    GymAppTheme {
        Column(Modifier.padding(16.dp)) {
            WheelRow("50", "55", "60", "kg", {}, "Gewicht")
        }
    }
}
