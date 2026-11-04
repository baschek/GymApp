package de.baschek.gymapp.ui.components

import de.baschek.gymapp.core.model.BodyRegions

enum class BodySide { FRONT, BACK }

/**
 * A muscle shape of the body drawing: its muscle [group], the finer [sub] area where the drawing has one
 * (null = the shape stands for the whole group), the region [slug] and SVG path data ([BodyArtPaths]).
 */
data class BodyShape(val group: String, val sub: String?, val slug: String, val d: String)

/**
 * The body drawing: a front and a back view with individually colourable muscle regions. The artwork is the male
 * body illustration of MuscleMap (@musclemap/assets 1.1.0, MIT, Copyright (c) 2026 Jsplice / MuscleMap
 * contributors; modified, see THIRD_PARTY_NOTICES.md), the path data lives in [BodyArtPaths]. Its regions are
 * finer than the app's twelve muscle groups: [BodyRegions] assigns each region to a group and, where it is finer
 * than the group, to a subgroup (for example "chest_upper" is subgroup "brust_oben" of "brust"). Both views are
 * drawn with both body halves, so nothing needs to be mirrored.
 */
object BodyArt {
    /** Size of one view in path units. */
    const val WIDTH = 637f
    const val HEIGHT = 1396f

    private fun regions(side: BodySide): Map<String, String> = if (side == BodySide.FRONT) BodyArtPaths.front else BodyArtPaths.back

    /** Muscle regions in drawing order. */
    fun shapes(side: BodySide): List<BodyShape> = if (side == BodySide.FRONT) front else back

    /** Silhouette of the whole body, drawn in the neutral body colour below the muscles. */
    fun outline(side: BodySide): String = if (side == BodySide.FRONT) BodyArtPaths.frontOutline else BodyArtPaths.backOutline

    private fun build(side: BodySide): List<BodyShape> = regions(side).mapNotNull { (slug, d) ->
        BodyRegions.regionOf(slug, side == BodySide.FRONT)?.let { BodyShape(it.group, it.sub, slug, d) }
    }

    private val front = build(BodySide.FRONT)
    private val back = build(BodySide.BACK)
}
