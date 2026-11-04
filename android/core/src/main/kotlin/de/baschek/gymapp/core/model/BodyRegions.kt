package de.baschek.gymapp.core.model

/** A drawn muscle region: the [MuscleGroup] it belongs to and, where the drawing is finer, its [MuscleSub]. */
data class BodyRegion(val group: String, val sub: String? = null)

/**
 * Mapping of the regions of the body drawing (slugs of the illustration, see `ui/components/BodyArt`) to the
 * [MuscleGroup]s and [MuscleSub]s of the app. The drawing is MuscleMap's male body (front and back); the chest
 * is cut into an upper and a lower part. Regions without subgroup colour with their whole group. Adductors
 * count to "quadrizeps" and abductors to "gesaess" (as in `muscle_mapping.json`), the shin muscle is part of
 * the calf region ("waden"). The silhouette (head, hands, feet) is drawn neutral and is not tappable.
 */
object BodyRegions {
    val FRONT: Map<String, BodyRegion> = linkedMapOf(
        "trapezius_upper" to BodyRegion(MuscleGroup.RUECKEN, MuscleSub.TRAPEZ_OBEN),
        "chest_upper" to BodyRegion(MuscleGroup.BRUST, MuscleSub.BRUST_OBEN),
        "chest_lower" to BodyRegion(MuscleGroup.BRUST, MuscleSub.BRUST_UNTEN),
        "shoulder_front" to BodyRegion(MuscleGroup.SCHULTERN, MuscleSub.SCHULTER_VORNE),
        "shoulder_side" to BodyRegion(MuscleGroup.SCHULTERN, MuscleSub.SCHULTER_SEITLICH),
        "biceps" to BodyRegion(MuscleGroup.BIZEPS),
        "triceps" to BodyRegion(MuscleGroup.TRIZEPS),
        "forearm" to BodyRegion(MuscleGroup.UNTERARME),
        "obliques" to BodyRegion(MuscleGroup.BAUCH, MuscleSub.BAUCH_SCHRAEG),
        "core" to BodyRegion(MuscleGroup.BAUCH, MuscleSub.BAUCH_GERADE),
        "abductors" to BodyRegion(MuscleGroup.GESAESS, MuscleSub.ABDUKTOREN),
        "quadriceps" to BodyRegion(MuscleGroup.QUADRIZEPS),
        "adductors" to BodyRegion(MuscleGroup.QUADRIZEPS, MuscleSub.ADDUKTOREN),
        "calves" to BodyRegion(MuscleGroup.WADEN),
    )

    val BACK: Map<String, BodyRegion> = linkedMapOf(
        "trapezius_upper" to BodyRegion(MuscleGroup.RUECKEN, MuscleSub.TRAPEZ_OBEN),
        "trapezius_lower" to BodyRegion(MuscleGroup.RUECKEN, MuscleSub.TRAPEZ_UNTEN),
        "rhomboids" to BodyRegion(MuscleGroup.RUECKEN, MuscleSub.RHOMBOIDEN),
        "lats" to BodyRegion(MuscleGroup.RUECKEN, MuscleSub.LATISSIMUS),
        "lower_back" to BodyRegion(MuscleGroup.UNTERER_RUECKEN),
        "shoulder_rear" to BodyRegion(MuscleGroup.SCHULTERN, MuscleSub.SCHULTER_HINTEN),
        "shoulder_side" to BodyRegion(MuscleGroup.SCHULTERN, MuscleSub.SCHULTER_SEITLICH),
        "triceps" to BodyRegion(MuscleGroup.TRIZEPS),
        "forearm" to BodyRegion(MuscleGroup.UNTERARME),
        "obliques" to BodyRegion(MuscleGroup.BAUCH, MuscleSub.BAUCH_SCHRAEG),
        "gluteus" to BodyRegion(MuscleGroup.GESAESS),
        "abductors" to BodyRegion(MuscleGroup.GESAESS, MuscleSub.ABDUKTOREN),
        "adductors" to BodyRegion(MuscleGroup.QUADRIZEPS, MuscleSub.ADDUKTOREN),
        "hamstrings" to BodyRegion(MuscleGroup.BEINBEUGER),
        "calves" to BodyRegion(MuscleGroup.WADEN),
    )

    /** The region of a slug, or null for unknown ones. */
    fun regionOf(slug: String, front: Boolean): BodyRegion? = (if (front) FRONT else BACK)[slug]

    /** The muscle group of a region slug, or null for unknown regions. */
    fun groupOf(slug: String, front: Boolean): String? = regionOf(slug, front)?.group
}
