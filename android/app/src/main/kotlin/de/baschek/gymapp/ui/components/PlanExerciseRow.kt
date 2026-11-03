package de.baschek.gymapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.ui.theme.GymAppTheme
import de.baschek.gymapp.ui.theme.Spacing

/**
 * One exercise in the plan editor (72 dp): name with its superset tag, a one-line summary, and up/down
 * arrows for reordering. Tapping the row opens the stepper sheet.
 */
@Composable
fun PlanExerciseRow(
    name: String,
    summary: String,
    supersetTag: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = onClick).padding(start = Spacing.s4, top = Spacing.s2, bottom = Spacing.s2)
                .heightIn(min = 72.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
                if (supersetTag != null) Tag(supersetTag)
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GhostIconButton(GymIcons.Up, "$name nach oben", onMoveUp, enabled = canMoveUp)
        GhostIconButton(GymIcons.Down, "$name nach unten", onMoveDown, enabled = canMoveDown)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF171717)
@Composable
private fun PlanRowPreview() {
    GymAppTheme {
        Column {
            PlanExerciseRow("Brustpresse (Maschine)", "N 3 · S 2 · U 1 · 8–12 Wdh. · 55 kg", "A", false, true, {}, {}, {})
            Hairline()
            PlanExerciseRow("Schulterpresse", "N 3 · S 2 · U 1 · 8–10 Wdh. · 25 kg", null, true, true, {}, {}, {})
        }
    }
}
