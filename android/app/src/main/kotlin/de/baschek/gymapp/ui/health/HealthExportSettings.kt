package de.baschek.gymapp.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.health.HealthAvailability
import de.baschek.gymapp.health.HealthConnectExporter
import de.baschek.gymapp.health.HealthExportConsent
import de.baschek.gymapp.health.HealthExportDeleteWorker
import de.baschek.gymapp.health.HealthExportWorker
import de.baschek.gymapp.health.publishHealthSettings
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.SoftButton
import kotlinx.coroutines.launch

@Composable
fun HealthExportSettings(profile: String) {
    val context = LocalContext.current.applicationContext
    val container = (context as GymApplication).container
    val consent = remember { HealthExportConsent(context) }
    val exporter = remember { HealthConnectExporter(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(consent.enabled()) }
    var status by remember { mutableStateOf("") }
    suspend fun refresh() {
        enabled = consent.enabled()
        status = when {
            profile == Profile.TEST -> "Testdaten bleiben lokal."
            exporter.availability() == HealthAvailability.ProviderUpdateRequired -> "Health Connect muss aktualisiert werden."
            exporter.availability() != HealthAvailability.Available -> "Health Connect ist auf diesem Gerät nicht verfügbar."
            !enabled -> "Automatischer Export ist ausgeschaltet."
            exporter.missingPermissions()?.isNotEmpty() != false -> "Berechtigung fehlt. Bitte erneut erlauben."
            exporter.backgroundReadGranted() -> "Automatischer Export aktiv – auch im Hintergrund."
            else -> "Automatischer Export aktiv. Ausstehende Daten werden beim Öffnen der App abgeglichen."
        }
    }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch {
            if (profile == Profile.PERSONAL && runCatching { exporter.missingPermissions()?.isEmpty() == true }.getOrDefault(false)) {
                consent.setEnabled(true)
                enabled = true
                container.health.onForeground()
            }
            runCatching { refresh() }
            publishHealthSettings(context)
        }
    }
    LifecycleResumeEffect(profile) {
        scope.launch { runCatching { refresh() }; publishHealthSettings(context) }
        onPauseOrDispose { }
    }
    Column {
        Overline("Gesundheit", Modifier.padding(start = 4.dp, bottom = 8.dp))
        GymCardSurface {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            Text("Nach einmaliger Freigabe werden künftig abgeschlossene persönliche Trainings und vorhandene Pulswerte automatisch gespeichert. Testdaten bleiben lokal.", style = MaterialTheme.typography.bodySmall)
            Text("Für den Abgleich liest GymApp nur eigene Pulsexporte. Hintergrundzugriff ist optional; ohne ihn erfolgt der Abgleich beim Öffnen der App.", style = MaterialTheme.typography.bodySmall)
            Text("Samsung Health kann Daten über Health Connect übernehmen, wenn dort Zugriff und Synchronisierung unterstützt und erlaubt sind.", style = MaterialTheme.typography.bodySmall)
            if (status.isNotBlank()) Text(status, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (profile == Profile.PERSONAL) {
                SoftButton(if (enabled) "Berechtigungen prüfen" else "Automatischen Export einschalten", onClick = {
                    Telemetry.track("health", "enable")
                    scope.launch {
                        val available = runCatching { exporter.availability() }.getOrNull()
                        if (available != HealthAvailability.Available) { refresh(); return@launch }
                        val missing = runCatching { exporter.missingPermissions() }.getOrNull() ?: return@launch
                        val background = runCatching { exporter.supportsBackgroundRead() }.getOrDefault(false)
                        if (missing.isEmpty() && (!background || runCatching { exporter.backgroundReadGranted() }.getOrDefault(false))) {
                            consent.setEnabled(true); enabled = true; container.health.onForeground(); refresh(); publishHealthSettings(context)
                        } else {
                            val permissions = exporter.requiredPermissions + if (background) setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) else emptySet()
                            launcher.launch(permissions)
                        }
                    }
                }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
                if (enabled) SoftButton("Automatischen Export ausschalten", onClick = {
                    Telemetry.track("health", "disable")
                    scope.launch {
                        HealthExportWorker.cancelAll(context); HealthExportDeleteWorker.cancelAll(context); consent.revoke(); enabled = false
                        refresh(); publishHealthSettings(context)
                    }
                }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
            }
        }
    }
}
