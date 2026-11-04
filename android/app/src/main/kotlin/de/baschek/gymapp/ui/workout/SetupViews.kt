package de.baschek.gymapp.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.model.SetupParameter
import de.baschek.gymapp.ui.components.GhostIconButton
import de.baschek.gymapp.ui.components.GymField
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.GymSheet
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked
import java.util.UUID

/** "Sitz 4 · Lehne 2" from the setup values, or null when there are none. */
internal fun setupSummary(setup: List<SetupParameter>?): String? =
    setup.orEmpty().filter { it.name.isNotBlank() }.joinToString(" · ") { "${it.name} ${it.value}".trim() }.takeIf { it.isNotBlank() }

/** The seat/setup values right under the exercise name; a tap opens [SetupSheet]. */
@Composable
internal fun SetupLine(setup: List<SetupParameter>?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val summary = setupSummary(setup)
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button, onClickLabel = "Geräteeinstellung ändern", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (summary != null) "$summary – antippen zum Ändern" else "Sitz und Einstellung am Gerät – antippen zum Anlegen",
            Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
            color = if (summary != null) cs.onSurface else cs.onSurfaceVariant, maxLines = 2,
        )
    }
}

private class SetupDraft(val id: String, name: String, value: String) {
    var name by mutableStateOf(name)
    var value by mutableStateOf(value)
}

/**
 * Edits the setup values (seat, backrest, ...) of the machine. They are saved in the machine profile of
 * this gym, so they are there next time. Without a profile one is created.
 */
@Composable
internal fun SetupSheet(exerciseName: String, initial: List<SetupParameter>?, screen: String, onSave: (List<SetupParameter>) -> Unit, onDismiss: () -> Unit) {
    val drafts = remember { mutableStateListOf<SetupDraft>().apply { initial.orEmpty().forEach { add(SetupDraft(it.id.ifBlank { UUID.randomUUID().toString() }, it.name, it.value)) } } }
    GymSheet(onDismiss, title = "Einstellung: $exerciseName") {
        Text(
            "Gilt für diese Maschine in diesem Studio und ist beim nächsten Mal wieder da.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        drafts.forEachIndexed { i, d ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                Box(Modifier.weight(1f)) { GymField(d.name, { d.name = it }, "Name") }
                Box(Modifier.width(96.dp)) { GymField(d.value, { d.value = it }, "Wert") }
                GhostIconButton(GymIcons.Close, "Parameter ${d.name} entfernen", tracked(screen, "setup_remove") { drafts.removeAt(i) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            listOf("Sitz", "Lehne").filter { n -> drafts.none { it.name.equals(n, ignoreCase = true) } }.forEach { n ->
                SoftButton(n, tracked(screen, "setup_add_$n".lowercase()) { drafts.add(SetupDraft(UUID.randomUUID().toString(), n, "")) }, icon = GymIcons.Plus)
            }
            SoftButton("Anderes", tracked(screen, "setup_add") { drafts.add(SetupDraft(UUID.randomUUID().toString(), "", "")) }, icon = GymIcons.Plus)
        }
        PrimaryButton(
            "Speichern",
            tracked(screen, "setup_save") {
                onSave(drafts.filter { it.name.isNotBlank() }.map { SetupParameter(it.id, it.name.trim(), it.value.trim()) })
            },
            height = 52.dp,
        )
    }
}
