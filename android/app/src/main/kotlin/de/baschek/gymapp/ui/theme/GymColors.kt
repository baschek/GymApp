package de.baschek.gymapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import de.baschek.gymapp.data.Accent

/** The accent: `primary` and the colour on top of it. Switching it needs no other change. */
data class AccentColors(val primary: Color, val onPrimary: Color)

fun Accent.colors(): AccentColors = when (this) {
    Accent.PAPIER -> AccentColors(Color(0xFFF5F5F5), Color(0xFF0C0C0C))
    Accent.SALBEI -> AccentColors(Color(0xFFA8CF8E), Color(0xFF14210D))
    Accent.DUNST -> AccentColors(Color(0xFF9DB2CC), Color(0xFF0D1624))
    Accent.SAND -> AccentColors(Color(0xFFD9C9A6), Color(0xFF241D0E))
}

/**
 * Roles Material 3 does not have. Status colours mean only: done, skipped (also offline, conflict),
 * pain (also errors). Gyms get their colour from the synced gym document; [gymFallback] is the neutral grey.
 */
@Immutable
data class GymColors(
    val done: Color = Color(0xFF62C28A),
    val doneContainer: Color = Color(0xFF1B2B22),
    val skipped: Color = Color(0xFFE0B15A),
    val skippedContainer: Color = Color(0xFF2E2616),
    val pain: Color = Color(0xFFF07373),
    val painContainer: Color = Color(0xFF3A1D1D),
    val gymFallback: Color = Color(0xFF8E99A8),
)

val LocalGymColors = staticCompositionLocalOf { GymColors() }

val MaterialTheme.gymColors: GymColors
    @Composable get() = LocalGymColors.current
