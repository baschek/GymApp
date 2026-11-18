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
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.tracked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The navigation recorder always runs; these are only its two buttons. */
@Composable
fun RecorderSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column {
        Text("Die Navigation (Bildschirme und Tipps ohne Inhalte, maskierte Bilder) wird im Hintergrund aufgezeichnet.")
        SoftButton("Aufzeichnung teilen", tracked("einstellungen", "navrec_export") {
            busy = true
            confirmDelete = false
            message = null
            scope.launch {
                try { NavRecorder.export(context) }
                catch (e: CancellationException) { throw e }
                catch (_: NoSuchElementException) { message = "Keine Aufzeichnung vorhanden." }
                catch (_: Exception) { message = "Aufzeichnung konnte nicht geteilt werden." }
                finally { busy = false }
            }
        }, enabled = !busy)
        SoftButton(if (confirmDelete) "Wirklich löschen" else "Aufzeichnungen löschen", tracked("einstellungen", "navrec_delete") {
            if (!confirmDelete) { confirmDelete = true; message = null; return@tracked }
            busy = true
            confirmDelete = false
            scope.launch {
                try {
                    val removed = NavRecorder.deleteAll()
                    message = "$removed Aufzeichnungen gelöscht, die laufende bleibt."
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "Aufzeichnungen konnten nicht gelöscht werden." }
                finally { busy = false }
            }
        }, enabled = !busy)
        message?.let { Text(it) }
    }
}
