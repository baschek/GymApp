package de.baschek.gymapp.ui.health

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.core.wear.events.ConflictChoice
import de.baschek.gymapp.core.wear.events.ReplicaState
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.LocalSnack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Non-blocking notice; the user chooses when to open the conflict resolution. */
@Composable
fun WatchConflictNotice(first: ReplicaState, resolveConflicts: suspend (String, ConflictChoice) -> Boolean) {
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    var open by remember(first.sessionId) { mutableStateOf(false) }
    var resolving by remember(first.sessionId) { mutableStateOf(false) }
    fun resolve(choice: ConflictChoice) {
        if (resolving) return
        resolving = true
        scope.launch {
            val success = try { resolveConflicts(first.sessionId, choice) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            resolving = false
            open = false
            if (!success) snack("Änderungen konnten nicht aufgelöst werden. Bitte erneut prüfen.")
        }
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Uhr und Handy: ${first.conflicts.size} Änderung(en) prüfen", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { Telemetry.track("wear", "conflict_open"); open = true }) { Text("Prüfen") }
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Änderungen prüfen") },
        text = { Text("Dieses Training wurde auf Uhr und Handy unterschiedlich geändert. Die Auswahl gilt für alle strittigen Änderungen dieser Sitzung.") },
        confirmButton = { TextButton(enabled = !resolving, onClick = {
            Telemetry.track("wear", "conflict_newest")
            resolve(ConflictChoice.NEWEST)
        }) { Text("Neueste") } },
        dismissButton = { TextButton(enabled = !resolving, onClick = {
            Telemetry.track("wear", "conflict_mine")
            resolve(ConflictChoice.MINE)
        }) { Text("Meine") } },
    )
}
