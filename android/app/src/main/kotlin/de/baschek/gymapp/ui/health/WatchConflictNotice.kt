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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.core.wear.events.ConflictChoice
import de.baschek.gymapp.telemetry.Telemetry
import kotlinx.coroutines.launch

/** Non-blocking notice; the user chooses when to open the conflict resolution. */
@Composable
fun WatchConflictNotice() {
    val repository = (LocalContext.current.applicationContext as GymApplication).container.repository
    val conflicts by repository.observeWatchConflicts().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val first = conflicts.firstOrNull() ?: return
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
        confirmButton = { TextButton(onClick = {
            Telemetry.track("wear", "conflict_newest")
            scope.launch { repository.resolveWatchConflicts(first.sessionId, ConflictChoice.NEWEST) }
            open = false
        }) { Text("Neueste") } },
        dismissButton = { TextButton(onClick = {
            Telemetry.track("wear", "conflict_mine")
            scope.launch { repository.resolveWatchConflicts(first.sessionId, ConflictChoice.MINE) }
            open = false
        }) { Text("Meine") } },
    )
}
