package de.baschek.gymapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.data.Accent

private val Background = Color(0xFF0C0C0C)
private val Card = Color(0xFF171717)
private val Card2 = Color(0xFF242424)
private val OnSurface = Color(0xFFF5F5F5)
private val OnSurfaceVariant = Color(0xFFA3A3A3)

private val GymShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Dark-only theme in the "Papier" family. Dynamic colour is deliberately not used so the accent stays
 * consistent; only `primary`/`onPrimary` change with the chosen [accent].
 */
@Composable
fun GymAppTheme(accent: Accent = Accent.PAPIER, content: @Composable () -> Unit) {
    val a = remember(accent) { accent.colors() }
    val scheme = remember(accent) {
        darkColorScheme(
            primary = a.primary,
            onPrimary = a.onPrimary,
            primaryContainer = Color(0xFF2E2E2E),
            onPrimaryContainer = OnSurface,
            secondary = OnSurfaceVariant,
            onSecondary = Background,
            secondaryContainer = Card2,
            onSecondaryContainer = OnSurface,
            tertiary = Color(0xFFC9C9C9),
            onTertiary = Background,
            tertiaryContainer = Color(0xFF333333),
            onTertiaryContainer = OnSurface,
            background = Background,
            onBackground = OnSurface,
            surface = Background,
            onSurface = OnSurface,
            surfaceVariant = Card2,
            onSurfaceVariant = OnSurfaceVariant,
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = Background,
            surfaceContainerLow = Card,
            surfaceContainer = Card,
            surfaceContainerHigh = Card2,
            surfaceContainerHighest = Card2,
            error = Color(0xFFF07373),
            onError = Background,
            errorContainer = Color(0xFF3A1D1D),
            onErrorContainer = OnSurface,
            outline = Color(0xFF6B6B6B),
            outlineVariant = Color(0xFF2C2C2C),
            scrim = Color.Black,
        )
    }
    CompositionLocalProvider(LocalGymColors provides GymColors()) {
        MaterialTheme(colorScheme = scheme, typography = GymTypography, shapes = GymShapes, content = content)
    }
}
