package de.baschek.gymapp.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The fixed set of muscle groups of `exercise.data.muscle_groups` (contract v1 amendment C). */
object MuscleGroup {
    const val BRUST = "brust"
    const val RUECKEN = "ruecken"
    const val UNTERER_RUECKEN = "unterer_ruecken"
    const val SCHULTERN = "schultern"
    const val BIZEPS = "bizeps"
    const val TRIZEPS = "trizeps"
    const val UNTERARME = "unterarme"
    const val BAUCH = "bauch"
    const val QUADRIZEPS = "quadrizeps"
    const val BEINBEUGER = "beinbeuger"
    const val GESAESS = "gesaess"
    const val WADEN = "waden"

    val ALL = listOf(
        BRUST, RUECKEN, UNTERER_RUECKEN, SCHULTERN, BIZEPS, TRIZEPS, UNTERARME, BAUCH, QUADRIZEPS, BEINBEUGER, GESAESS, WADEN,
    )

    /** German display name. */
    fun label(group: String): String = when (group) {
        BRUST -> "Brust"
        RUECKEN -> "Rücken"
        UNTERER_RUECKEN -> "Unterer Rücken"
        SCHULTERN -> "Schultern"
        BIZEPS -> "Bizeps"
        TRIZEPS -> "Trizeps"
        UNTERARME -> "Unterarme"
        BAUCH -> "Bauch"
        QUADRIZEPS -> "Quadrizeps"
        BEINBEUGER -> "Beinbeuger"
        GESAESS -> "Gesäß"
        WADEN -> "Waden"
        else -> group
    }
}

/** The six areas of the balance radar and of the goals. */
enum class RadarArea(val label: String, val groups: List<String>) {
    BRUST("Brust", listOf(MuscleGroup.BRUST)),
    SCHULTERN("Schultern", listOf(MuscleGroup.SCHULTERN)),
    ARME("Arme", listOf(MuscleGroup.BIZEPS, MuscleGroup.TRIZEPS, MuscleGroup.UNTERARME)),
    BEINE("Beine", listOf(MuscleGroup.QUADRIZEPS, MuscleGroup.BEINBEUGER, MuscleGroup.GESAESS, MuscleGroup.WADEN)),
    BAUCH("Bauch", listOf(MuscleGroup.BAUCH, MuscleGroup.UNTERER_RUECKEN)),
    RUECKEN("Rücken", listOf(MuscleGroup.RUECKEN));

    companion object {
        fun of(group: String): RadarArea? = entries.firstOrNull { group in it.groups }
    }
}

fun TargetSets.forArea(area: RadarArea): Int = when (area) {
    RadarArea.BRUST -> brust
    RadarArea.SCHULTERN -> schultern
    RadarArea.ARME -> arme
    RadarArea.BEINE -> beine
    RadarArea.BAUCH -> bauch
    RadarArea.RUECKEN -> ruecken
}

fun TargetSets.with(area: RadarArea, value: Int): TargetSets = when (area) {
    RadarArea.BRUST -> copy(brust = value)
    RadarArea.SCHULTERN -> copy(schultern = value)
    RadarArea.ARME -> copy(arme = value)
    RadarArea.BEINE -> copy(beine = value)
    RadarArea.BAUCH -> copy(bauch = value)
    RadarArea.RUECKEN -> copy(ruecken = value)
}

/**
 * Fixed table from the legacy catalog muscle names to [MuscleGroup]s, loaded from
 * `muscle_mapping.json` on the classpath. The server owns an identical table.
 */
object MuscleMapping {
    val table: Map<String, String> by lazy { load() }

    private fun load(): Map<String, String> = runCatching {
        val text = MuscleMapping::class.java.getResourceAsStream("/muscle_mapping.json")!!.use { it.readBytes().decodeToString() }
        parse(text)
    }.getOrDefault(emptyMap())

    /** Subgroup to parent group from `muscle_mapping.json` (amendment G); tests keep it identical to [MuscleSub.PARENT]. */
    val subgroupTable: Map<String, String> by lazy {
        runCatching {
            val text = MuscleMapping::class.java.getResourceAsStream("/muscle_mapping.json")!!.use { it.readBytes().decodeToString() }
            GymJson.parseToJsonElement(text).jsonObject["subgroups"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    internal fun parse(text: String): Map<String, String> {
        val root: JsonObject = GymJson.parseToJsonElement(text).jsonObject
        return root["mapping"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    /**
     * The muscle groups of an exercise: its own `muscle_groups` when set, otherwise derived from the
     * legacy primary (first) and secondary muscles through the table; unknown names are skipped.
     */
    fun groupsOf(exercise: Exercise, mapping: Map<String, String> = table): List<String> {
        exercise.muscleGroups?.filter { it in MuscleGroup.ALL }?.takeIf { it.isNotEmpty() }?.let { return it.distinct() }
        val legacy = exercise.primaryMuscles.orEmpty() + exercise.secondaryMuscles.orEmpty()
        return legacy.mapNotNull { mapping[it.trim().lowercase()] }.distinct()
    }

    /** The known subgroups an exercise names (amendment G); unknown keys are skipped, order kept. */
    fun subgroupsOf(exercise: Exercise): List<String> =
        exercise.muscleSubgroups.orEmpty().filter { it in MuscleSub.PARENT }.distinct()
}
