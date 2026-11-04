package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.topInset
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked

private const val BANNER_SCREEN = "training"

/**
 * "Claude hat angepasst: ... (Begründung)" with "Rückgängig" and "OK" (contract v1 amendment D). Shown over the
 * training and rest screens while the newest applied assistant adjustment of the workout has not been dismissed.
 */
@Composable
internal fun AdjustmentBanner(vm: WorkoutViewModel, modifier: Modifier = Modifier) {
    val banner by vm.adjustmentBanner.collectAsStateWithLifecycle()
    val shown = banner ?: return
    LaunchedEffect(shown.id) { Telemetry.track(BANNER_SCREEN, "adjustment_shown") }
    GymCardSurface(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter).padding(top = topInset() + Spacing.s2),
        color = MaterialTheme.colorScheme.secondaryContainer,
        padding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.s4, end = Spacing.s2, top = Spacing.s3, bottom = Spacing.s1),
    ) {
        Text(shown.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = tracked(BANNER_SCREEN, "adjustment_undo") { vm.undoAdjustment(shown.id) },
                modifier = Modifier.heightIn(min = Spacing.Touch),
            ) { Text("Rückgängig") }
            TextButton(
                onClick = tracked(BANNER_SCREEN, "adjustment_dismiss") { vm.dismissAdjustment(shown.id) },
                modifier = Modifier.heightIn(min = Spacing.Touch),
            ) { Text("OK") }
        }
    }
}
