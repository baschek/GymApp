package de.baschek.gymapp.core.model

/**
 * Mapping of the regions of the body drawing (slugs of the illustration, see `ui/components/BodyArt`) to the
 * [MuscleGroup]s of the app. The drawing is finer than the twelve groups, so several regions share one group:
 * obliques and abs are "bauch"; neck is "schultern" and trapezius/upper back are "ruecken" (as in
 * `muscle_mapping.json`); adductors count to "quadrizeps" and the shin muscle (tibialis) to "waden".
 * Regions without a group (head, hair, hands, knees, ankles, feet) are drawn neutral and are not tappable.
 */
object BodyRegions {
    val FRONT: Map<String, String> = mapOf(
        "chest" to MuscleGroup.BRUST,
        "abs" to MuscleGroup.BAUCH,
        "obliques" to MuscleGroup.BAUCH,
        "biceps" to MuscleGroup.BIZEPS,
        "triceps" to MuscleGroup.TRIZEPS,
        "deltoids" to MuscleGroup.SCHULTERN,
        "neck" to MuscleGroup.SCHULTERN,
        "trapezius" to MuscleGroup.RUECKEN,
        "forearm" to MuscleGroup.UNTERARME,
        "quadriceps" to MuscleGroup.QUADRIZEPS,
        "adductors" to MuscleGroup.QUADRIZEPS,
        "tibialis" to MuscleGroup.WADEN,
        "calves" to MuscleGroup.WADEN,
    )

    val BACK: Map<String, String> = mapOf(
        "neck" to MuscleGroup.SCHULTERN,
        "trapezius" to MuscleGroup.RUECKEN,
        "upper-back" to MuscleGroup.RUECKEN,
        "lower-back" to MuscleGroup.UNTERER_RUECKEN,
        "deltoids" to MuscleGroup.SCHULTERN,
        "triceps" to MuscleGroup.TRIZEPS,
        "forearm" to MuscleGroup.UNTERARME,
        "gluteal" to MuscleGroup.GESAESS,
        "adductors" to MuscleGroup.QUADRIZEPS,
        "hamstring" to MuscleGroup.BEINBEUGER,
        "calves" to MuscleGroup.WADEN,
    )

    /** Regions drawn in the neutral body colour only. */
    val NEUTRAL: Set<String> = setOf("head", "hair", "hands", "knees", "ankles", "feet")

    /** The muscle group of a region slug, or null for neutral/unknown regions. */
    fun groupOf(slug: String, front: Boolean): String? = (if (front) FRONT else BACK)[slug]
}
