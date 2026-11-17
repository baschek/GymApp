package de.baschek.gymapp.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Explains Health Connect permissions when opened from the system permission screen. */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.padding(24.dp)) {
                    Text("Gesundheitsdaten", style = MaterialTheme.typography.headlineSmall)
                    Text("Mit deiner Freigabe speichert GymApp dieses Training und die auf der Uhr gemessenen Pulswerte in Health Connect. Die Leseberechtigung wird nur benötigt, um eigene frühere Exporte bei Änderungen abzugleichen. Andere Gesundheitsdaten werden nicht importiert.", Modifier.padding(vertical = 16.dp))
                    Text("Pulsaufzeichnungen bleiben lokal auf den Geräten und werden nicht mit dem GymApp-Syncserver synchronisiert. Testdaten werden nicht exportiert.")
                    TextButton(onClick = { finish() }) { Text("Schließen") }
                }
            }
        }
    }
}
