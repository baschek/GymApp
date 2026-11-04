package de.baschek.gymapp.ui.components

import de.baschek.gymapp.core.model.BodyRegions

enum class BodySide { FRONT, BACK }

/** A muscle shape of the body drawing: the muscle group it belongs to and SVG path data (see [BodyArt.originX]). */
data class BodyShape(val group: String, val d: String)

/**
 * The body drawing: a front and a back view with individually colourable muscle regions. The artwork is the male
 * body illustration of react-native-body-highlighter 3.2.0 (MIT, Copyright (c) 2022 ELABBASSI Hicham; see
 * THIRD_PARTY_NOTICES.md), the path data lives in [BodyArtPaths]. Its regions are finer than the app's twelve
 * muscle groups, [BodyRegions] merges them (for example abs and obliques are "bauch"). Both views are drawn
 * with both body halves, so nothing needs to be mirrored.
 */
object BodyArt {
    /** Size of one view in path units. */
    const val WIDTH = 724f
    const val HEIGHT = 1448f

    /** The back view's path data lies in x 724..1448; subtract this from x to get view coordinates. */
    fun originX(side: BodySide): Float = if (side == BodySide.FRONT) 0f else WIDTH

    private fun regions(side: BodySide): Map<String, String> = if (side == BodySide.FRONT) BodyArtPaths.front else BodyArtPaths.back

    /** Muscle regions that belong to a group, in drawing order. */
    fun shapes(side: BodySide): List<BodyShape> = if (side == BodySide.FRONT) front else back

    /** Path data of the regions without muscle group (head, hands, feet, ...), drawn in the neutral body colour. */
    fun neutral(side: BodySide): List<String> = if (side == BodySide.FRONT) frontNeutral else backNeutral

    /** Outline of the whole body. */
    fun outline(side: BodySide): String = if (side == BodySide.FRONT) BodyArtPaths.frontOutline else BodyArtPaths.backOutline

    private fun build(side: BodySide): List<BodyShape> = regions(side).mapNotNull { (slug, d) ->
        BodyRegions.groupOf(slug, side == BodySide.FRONT)?.let { BodyShape(it, d) }
    }

    private fun buildNeutral(side: BodySide): List<String> = regions(side).filterKeys { it in BodyRegions.NEUTRAL }.values.toList()

    private val front = build(BodySide.FRONT)
    private val back = build(BodySide.BACK)
    private val frontNeutral = buildNeutral(BodySide.FRONT)
    private val backNeutral = buildNeutral(BodySide.BACK)
}
