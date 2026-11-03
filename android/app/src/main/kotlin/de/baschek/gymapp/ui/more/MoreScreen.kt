package de.baschek.gymapp.ui.more

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import de.baschek.gymapp.BuildConfig
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.Routes
import de.baschek.gymapp.ui.components.ChevronIcon
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.TopTitle
import de.baschek.gymapp.ui.tracked

private const val SCREEN = "mehr"

@Composable
fun MoreScreen(nav: Navigator) {
    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }
    ScreenList {
        item { TopTitle("Mehr") }
        item {
            Group {
                ListRow(
                    "Übungen und Maschinen", subtitle = "Katalog, Maschinenprofile je Studio", minHeight = 64.dp,
                    onClick = tracked(SCREEN, "open_exercises") { nav.push(Routes.EXERCISES) }, trailing = { ChevronIcon() },
                )
                Hairline()
                ListRow(
                    "Ziele", subtitle = "Trainingstage und Sätze pro Muskelbereich", minHeight = 64.dp,
                    onClick = tracked(SCREEN, "open_goals") { nav.push(Routes.GOALS) }, trailing = { ChevronIcon() },
                )
                Hairline()
                ListRow(
                    "Einstellungen", subtitle = "Sync, Profil, Akzentfarbe, Update", minHeight = 64.dp,
                    onClick = tracked(SCREEN, "open_settings") { nav.push(Routes.SETTINGS) }, trailing = { ChevronIcon() },
                )
            }
        }
        item {
            Text(
                "GymApp · Version ${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
