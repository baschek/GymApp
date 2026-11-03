package de.baschek.gymapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import de.baschek.gymapp.R
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.OTHER_GYM_ID
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.ui.components.gymStripeColor
import de.baschek.gymapp.ui.theme.gymColors

fun gymById(gymId: String?, gyms: List<Stored<Gym>>): Gym? = gyms.firstOrNull { it.id == gymId }?.value

@Composable
fun gymLabel(gymId: String?, gyms: List<Stored<Gym>>): String = when {
    gymId == null -> stringResource(R.string.gym_unknown)
    else -> gymById(gymId, gyms)?.name
        ?: if (gymId == OTHER_GYM_ID) stringResource(R.string.gym_other_name) else stringResource(R.string.gym_unknown)
}

/** Stripe colour from the gym document (neutral grey when it has none or the gym is unknown). */
@Composable
fun gymColorOf(gymId: String?, gyms: List<Stored<Gym>>): Color =
    gymStripeColor(gymById(gymId, gyms), MaterialTheme.gymColors.gymFallback)

/**
 * Gym ids that can be chosen: the synced, non-archived gyms. A built-in "other" entry is only added when
 * the server did not provide a gym document with that id.
 */
fun gymChoices(gyms: List<Stored<Gym>>): List<String> {
    val ids = gyms.map { it.id }
    return if (OTHER_GYM_ID in ids) ids else ids + OTHER_GYM_ID
}
