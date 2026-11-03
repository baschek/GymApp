package de.baschek.gymapp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.model.Exercise
import de.baschek.gymapp.core.model.MuscleGroup
import de.baschek.gymapp.core.model.MuscleMapping
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.ui.theme.Spacing

/** Search + pick an exercise from the catalog (used for "Übung hinzufügen"). */
@Composable
fun ExercisePickerSheet(
    exercises: List<Stored<Exercise>>,
    onPick: (Stored<Exercise>) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Übung hinzufügen",
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(exercises, query) {
        val q = query.trim().lowercase()
        exercises.filter { q.isEmpty() || it.value.name.lowercase().contains(q) || it.value.aliases.orEmpty().any { a -> a.lowercase().contains(q) } }
            .sortedBy { it.value.name.lowercase() }
    }
    GymSheet(onDismiss, title = title) {
        GymField(query, { query = it }, "Übung suchen")
        if (filtered.isEmpty()) {
            Text("Keine Übung gefunden", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.s2))
        } else {
            Group {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(filtered.take(100), key = { it.id }) { ex ->
                        ListRow(
                            ex.value.name,
                            subtitle = MuscleMapping.groupsOf(ex.value).firstOrNull()?.let { MuscleGroup.label(it) },
                            minHeight = 56.dp,
                            onClick = { onPick(ex) },
                        )
                        Hairline()
                    }
                }
            }
        }
    }
}
