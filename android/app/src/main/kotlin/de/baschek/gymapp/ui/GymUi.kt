package de.baschek.gymapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.R
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.OTHER_GYM_ID
import de.baschek.gymapp.data.Stored

/** Stable colour per gym id (the contract has no colour field). */
fun gymColor(gymId: String?): Color {
    val hue = ((gymId?.hashCode() ?: 0).toLong() and 0x7fffffffL) % 360
    return Color.hsv(hue.toFloat(), 0.55f, 0.92f)
}

@Composable
fun gymLabel(gymId: String?, gyms: List<Stored<Gym>>): String = when {
    gymId == null -> stringResource(R.string.gym_unknown)
    gymId == OTHER_GYM_ID -> stringResource(R.string.gym_other_name)
    else -> gyms.firstOrNull { it.id == gymId }?.value?.name ?: stringResource(R.string.gym_unknown)
}

/** Full-width band showing the gym; used on top of the workout screen. */
@Composable
fun GymBanner(gymId: String?, gyms: List<Stored<Gym>>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(gymColor(gymId)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(gymLabel(gymId, gyms), style = MaterialTheme.typography.titleMedium, color = Color.Black)
    }
}

@Composable
fun GymDot(gymId: String?, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).clip(CircleShape).background(gymColor(gymId)))
}
