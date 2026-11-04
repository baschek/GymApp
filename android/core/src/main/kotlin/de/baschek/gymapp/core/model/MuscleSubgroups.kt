package de.baschek.gymapp.core.model

/**
 * Optional finer muscle areas below the twelve [MuscleGroup]s (contract v1 amendment G). They only refine the
 * muscle map: targets, radar, statistics and the counting rule always work on the groups, a subgroup's sets are
 * part of its parent group. An exercise names them in `exercise.data.muscle_subgroups`.
 */
object MuscleSub {
    const val BRUST_OBEN = "brust_oben"
    const val BRUST_UNTEN = "brust_unten"
    const val SCHULTER_VORNE = "schulter_vorne"
    const val SCHULTER_SEITLICH = "schulter_seitlich"
    const val SCHULTER_HINTEN = "schulter_hinten"
    const val LATISSIMUS = "latissimus"
    const val RHOMBOIDEN = "rhomboiden"
    const val TRAPEZ_OBEN = "trapez_oben"
    const val TRAPEZ_UNTEN = "trapez_unten"
    const val BAUCH_GERADE = "bauch_gerade"
    const val BAUCH_SCHRAEG = "bauch_schraeg"
    const val ABDUKTOREN = "abduktoren"
    const val ADDUKTOREN = "adduktoren"

    /** Subgroup to parent group, in display order. */
    val PARENT: Map<String, String> = linkedMapOf(
        BRUST_OBEN to MuscleGroup.BRUST,
        BRUST_UNTEN to MuscleGroup.BRUST,
        SCHULTER_VORNE to MuscleGroup.SCHULTERN,
        SCHULTER_SEITLICH to MuscleGroup.SCHULTERN,
        SCHULTER_HINTEN to MuscleGroup.SCHULTERN,
        LATISSIMUS to MuscleGroup.RUECKEN,
        RHOMBOIDEN to MuscleGroup.RUECKEN,
        TRAPEZ_OBEN to MuscleGroup.RUECKEN,
        TRAPEZ_UNTEN to MuscleGroup.RUECKEN,
        BAUCH_GERADE to MuscleGroup.BAUCH,
        BAUCH_SCHRAEG to MuscleGroup.BAUCH,
        ABDUKTOREN to MuscleGroup.GESAESS,
        ADDUKTOREN to MuscleGroup.QUADRIZEPS,
    )

    val ALL: List<String> = PARENT.keys.toList()

    fun parent(sub: String): String? = PARENT[sub]

    /** The subgroups of a group (empty for groups without finer areas). */
    fun childrenOf(group: String): List<String> = PARENT.filterValues { it == group }.keys.toList()

    /** German display name. */
    fun label(sub: String): String = when (sub) {
        BRUST_OBEN -> "Brust oben"
        BRUST_UNTEN -> "Brust unten"
        SCHULTER_VORNE -> "Schulter vorne"
        SCHULTER_SEITLICH -> "Schulter seitlich"
        SCHULTER_HINTEN -> "Schulter hinten"
        LATISSIMUS -> "Latissimus"
        RHOMBOIDEN -> "Rhomboideen / mittlerer Rücken"
        TRAPEZ_OBEN -> "Trapez oben"
        TRAPEZ_UNTEN -> "Trapez mitte/unten"
        BAUCH_GERADE -> "Gerader Bauch"
        BAUCH_SCHRAEG -> "Schräger Bauch"
        ABDUKTOREN -> "Abduktoren"
        ADDUKTOREN -> "Adduktoren"
        else -> sub
    }
}
