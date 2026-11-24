package de.baschek.gymapp.ui.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.health.TessHeartConsent
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymSwitch
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.Overline

/** Own switch for sending the exact pulse curve of new personal trainings to the sync server (default off). */
@Composable
fun TessHeartSettings(profile: String) {
    val context = LocalContext.current.applicationContext
    val container = (context as GymApplication).container
    val consent = remember { TessHeartConsent(context) }
    var enabled by remember { mutableStateOf(consent.enabled()) }
    val paired by container.secureStore.paired.collectAsStateWithLifecycle()
    Column {
        Overline("Pulskurve", Modifier.padding(start = 4.dp, bottom = 8.dp))
        Group {
            ListRow(
                "Pulskurve an TESS senden",
                subtitle = when {
                    profile == Profile.TEST -> "Testdaten bleiben lokal."
                    enabled -> "Jedes neue, abgeschlossene Training sendet seine genaue Pulskurve (alle Messpunkte, Lücken bleiben Lücken) an den Sync-Server."
                    else -> "Aus. Nur Trainings, die nach dem Einschalten beginnen, werden gesendet; frühere nie."
                },
                trailing = {
                    if (profile == Profile.PERSONAL) GymSwitch(enabled, { on ->
                        Telemetry.track("health", if (on) "tess_heart_on" else "tess_heart_off")
                        consent.setEnabled(on)
                        enabled = consent.enabled()
                        // Nothing is sent retroactively: the switch only affects trainings started from now on.
                    }, "Pulskurve an TESS senden")
                },
            )
        }
        if (profile == Profile.PERSONAL && enabled && !paired) {
            Text(
                "Dafür muss die App mit dem Sync-Server gekoppelt sein.", Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Ausschalten stoppt weitere Übertragungen; bereits gesendete Kurven bleiben auf dem Server.", Modifier.padding(start = 4.dp, top = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
