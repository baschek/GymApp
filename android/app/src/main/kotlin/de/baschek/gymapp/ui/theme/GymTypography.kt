@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package de.baschek.gymapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.baschek.gymapp.R

/**
 * Hanken Grotesk (bundled variable font, SIL OFL 1.1, see THIRD_PARTY_NOTICES.md).
 * Roboto (the system default) takes over if the font cannot be loaded.
 */
val HankenGrotesk: FontFamily = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(
            R.font.hanken_grotesk,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

private const val TNUM = "tnum"

/** Number styles for the training glance: tabular figures so digits do not jitter. */
object GymTextStyles {
    val displayTimer = TextStyle(
        fontFamily = HankenGrotesk, fontSize = 72.sp, lineHeight = 76.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.03).em, fontFeatureSettings = TNUM,
    )
    val displayWeight = TextStyle(
        fontFamily = HankenGrotesk, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.02).em, fontFeatureSettings = TNUM,
    )
    val displayReps = TextStyle(
        fontFamily = HankenGrotesk, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.02).em, fontFeatureSettings = TNUM,
    )
    val number = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM)
    val overline = TextStyle(
        fontFamily = HankenGrotesk, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.06.em,
    )
}

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) =
    TextStyle(fontFamily = HankenGrotesk, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

val GymTypography = Typography(
    headlineLarge = style(32, 38, FontWeight.SemiBold, -0.3f),
    headlineMedium = style(28, 36, FontWeight.Bold),
    headlineSmall = style(24, 30, FontWeight.SemiBold, -0.2f),
    titleLarge = style(22, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.Medium),
    titleSmall = style(15, 20, FontWeight.Medium),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(16, 24, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium, 0.5f),
    labelSmall = style(11, 14, FontWeight.Medium),
)
