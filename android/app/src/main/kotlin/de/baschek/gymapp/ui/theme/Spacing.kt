package de.baschek.gymapp.ui.theme

import androidx.compose.ui.unit.dp

/** 4 dp grid from the design tokens. */
object Spacing {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp

    /** Screen side gutter. */
    val Gutter = s4

    /** Smallest target outside training. */
    val Touch = 48.dp

    /** Smallest target during training (sweaty fingers). */
    val TouchMin = 56.dp

    /** The one primary action per screen. */
    val TouchPrimary = 72.dp

    val RadiusCard = 22.dp
    val RadiusMd = 14.dp
    val RadiusBanner = 18.dp
    val RadiusSheet = 28.dp
}
