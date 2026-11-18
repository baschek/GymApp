package de.baschek.gymapp.recorder

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.baschek.gymapp.ui.components.GymSwitch
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.tracked
import de.baschek.gymapp.ui.tracked1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RecorderSettings() {
    val active by NavRecorder.recording.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val label = "Navigation aufzeichnen (Test, nur diese Version)"
    Column {
        ListRow(label, trailing = {
            GymSwitch(active, tracked1<Boolean>("einstellungen", "navrec_switch", NavRecorder::setEnabled), label)
        })
        SoftButton("Aufzeichnung teilen", tracked("einstellungen", "navrec_export") {
            sharing = true
            error = null
            scope.launch {
                try { NavRecorder.export(context) }
                catch (e: CancellationException) { throw e }
                catch (_: NoSuchElementException) { error = "Keine Aufzeichnung vorhanden." }
                catch (_: Exception) { error = "Aufzeichnung konnte nicht geteilt werden." }
                finally { sharing = false }
            }
        }, enabled = !sharing)
        error?.let { Text(it) }
    }
}
