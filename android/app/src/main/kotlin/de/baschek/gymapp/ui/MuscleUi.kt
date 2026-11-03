package de.baschek.gymapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.model.MuscleGroup
import de.baschek.gymapp.core.stats.MuscleSummary
import de.baschek.gymapp.core.stats.formatSets
import de.baschek.gymapp.ui.components.BodySide
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.GymSheet
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.MuscleBody
import de.baschek.gymapp.ui.components.MuscleShare
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Bottom sheet for one tapped muscle: the exercises (and sets) that trained it in the selected period. */
@Composable
fun MuscleDetailSheet(group: String, summary: MuscleSummary, periodLabel: String, onDismiss: () -> Unit) {
    val details = summary.details[group].orEmpty()
    GymSheet(onDismiss, title = MuscleGroup.label(group)) {
        Text(
            if (details.isEmpty()) "Für diesen Muskel gibt es $periodLabel keine Sätze."
            else "${formatSets(summary.totals[group] ?: 0.0)} Sätze $periodLabel (Hauptmuskel zählt voll, weitere Muskeln halb).",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (details.isNotEmpty()) {
            Group {
                details.forEachIndexed { i, d ->
                    if (i > 0) Hairline()
                    ListRow(
                        d.exerciseName, subtitle = "${d.sets} Sätze · zuletzt ${LocalDate.parse(d.lastDate).let { "%02d.%02d.".format(it.dayOfMonth, it.monthValue) }}",
                        trailing = { Text(formatSets(d.weightedSets), style = MaterialTheme.typography.titleMedium) },
                    )
                }
            }
        }
    }
}

/** Preview of the share card (front and back) with the button that opens the Android share sheet. */
@Composable
fun MuscleShareSheet(title: String, subtitle: String, summary: MuscleSummary, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    var busy by remember { mutableStateOf(false) }
    GymSheet(onDismiss, title = "Karte teilen") {
        Row(
            Modifier.fillMaxWidth().padding(vertical = Spacing.s2), horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            MuscleBody(BodySide.FRONT, summary, Modifier.weight(1f))
            MuscleBody(BodySide.BACK, summary, Modifier.weight(1f))
        }
        PrimaryButton(
            if (busy) "Bereite vor …" else "Bild teilen",
            onClick = tracked("teilen", "share") {
                busy = true
                scope.launch {
                    try {
                        MuscleShare.share(context, title, subtitle, summary, accent)
                    } finally {
                        busy = false
                    }
                    onDismiss()
                }
            },
            icon = GymIcons.Share, height = 52.dp, enabled = !busy,
        )
    }
}
