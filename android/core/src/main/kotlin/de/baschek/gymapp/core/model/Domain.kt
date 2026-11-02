package de.baschek.gymapp.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Typed views of the `data` object of each entity. Open value sets are kept as
 * strings (see the constants below) so that unknown values from newer clients
 * are preserved instead of failing to decode.
 */

object MeasurementType {
    const val LOAD_REPS = "load_reps"
    const val BODYWEIGHT_REPS = "bodyweight_reps"
    const val ADDED_WEIGHT_REPS = "added_weight_reps"
    const val ASSISTED_REPS = "assisted_reps"
    const val REPS_ONLY = "reps_only"
    const val LOAD_DURATION = "load_duration"
    const val LOAD_DISTANCE_DURATION = "load_distance_duration"
    const val DURATION_ONLY = "duration_only"
}

object LoadDirection {
    const val HIGHER = "higher"
    const val LOWER_ASSISTANCE = "lower_assistance"
    const val NONE = "none"
}

object LoadBasis {
    const val STACK = "stack"
    const val TOTAL = "total"
    const val PER_HAND = "per_hand"
    const val ADDED = "added"
    const val ASSISTANCE = "assistance"
    const val NONE = "none"
}

object SetStatus {
    const val PENDING = "pending"
    const val COMPLETED = "completed"
    const val SKIPPED_TIME = "skipped_time"
    const val SKIPPED_EQUIPMENT = "skipped_equipment"
    const val FAILED = "failed"
    const val PAIN = "pain"
    const val SKIPPED_OTHER = "skipped_other"
}

object SetType {
    const val WORKING = "working"
    const val WARMUP = "warmup"
}

object SessionStatus {
    const val ACTIVE = "active"
    const val COMPLETED = "completed"
}

object WorkoutMode {
    const val NORMAL = "normal"
    const val FAST = "fast"
    const val ULTRA = "ultra"
}

object Metric {
    const val REPS = "reps"
    const val SECONDS = "seconds"
    const val METERS = "meters"
}

/** Lower bound of "reps in reserve" per effort band (current and legacy values). */
val EffortLowerBound: Map<String, Int> = mapOf(
    "limit" to 0,
    "one" to 1,
    "two_three" to 2,
    "four_plus" to 4,
    "one_two" to 1,
    "three_four" to 3,
    "five_plus" to 5,
)

val SelectableEffortBands = listOf("limit", "one_two", "three_four", "five_plus")

@Serializable
data class PackItem(
    val id: String = "",
    val text: String = "",
    val order: Int = 0,
    @SerialName("done_default") val doneDefault: Boolean? = null,
)

@Serializable
data class Gym(
    val name: String = "",
    @SerialName("short_name") val shortName: String? = null,
    val archived: Boolean? = null,
    @SerialName("has_sauna") val hasSauna: Boolean? = null,
    val notes: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
    val packlist: List<PackItem>? = null,
)

@Serializable
data class Exercise(
    val name: String = "",
    val aliases: List<String>? = null,
    val equipment: String? = null,
    val category: String? = null,
    @SerialName("primary_muscles") val primaryMuscles: List<String>? = null,
    @SerialName("secondary_muscles") val secondaryMuscles: List<String>? = null,
    val instructions: List<String>? = null,
    @SerialName("measurement_type") val measurementType: String = MeasurementType.LOAD_REPS,
    @SerialName("load_direction") val loadDirection: String = LoadDirection.HIGHER,
    @SerialName("load_basis") val loadBasis: String = LoadBasis.STACK,
    @SerialName("movement_family") val movementFamily: String? = null,
    @SerialName("photo_ref") val photoRef: String? = null,
    val notes: String? = null,
)

@Serializable
data class ModeSets(val normal: Int = 0, val fast: Int = 0, val ultra: Int = 0) {
    fun forMode(mode: String): Int = when (mode) {
        WorkoutMode.FAST -> fast
        WorkoutMode.ULTRA -> ultra
        else -> normal
    }
}

@Serializable
data class Target(
    val metric: String = Metric.REPS,
    val min: Double = 0.0,
    val max: Double = 0.0,
    @SerialName("rir_min") val rirMin: Double = 0.0,
    @SerialName("rir_max") val rirMax: Double = 0.0,
)

@Serializable
data class PlanExercise(
    val id: String = "",
    @SerialName("exercise_id") val exerciseId: String = "",
    val order: Int = 0,
    @SerialName("mode_sets") val modeSets: ModeSets = ModeSets(),
    val target: Target = Target(),
    @SerialName("starting_weight_kg") val startingWeightKg: Double? = null,
    @SerialName("current_weight_kg") val currentWeightKg: Double? = null,
    @SerialName("rest_seconds") val restSeconds: Int = 90,
    @SerialName("superset_group") val supersetGroup: String? = null,
    val notes: String? = null,
)

@Serializable
data class Plan(
    val name: String = "",
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val archived: Boolean? = null,
    val exercises: List<PlanExercise> = emptyList(),
)

@Serializable
data class WeightRange(
    val id: String = "",
    @SerialName("min_kg") val minKg: Double = 0.0,
    @SerialName("max_kg") val maxKg: Double = 0.0,
    @SerialName("step_kg") val stepKg: Double = 0.0,
)

@Serializable
data class SetupParameter(val id: String = "", val name: String = "", val value: String = "")

@Serializable
data class MachineProfile(
    @SerialName("gym_id") val gymId: String = "",
    @SerialName("exercise_id") val exerciseId: String = "",
    @SerialName("available_weights_kg") val availableWeightsKg: List<Double>? = null,
    @SerialName("weight_ranges") val weightRanges: List<WeightRange>? = null,
    @SerialName("setup_parameters") val setupParameters: List<SetupParameter>? = null,
    @SerialName("machine_brand") val machineBrand: String? = null,
    @SerialName("machine_model") val machineModel: String? = null,
    val notes: String? = null,
)

@Serializable
data class SetLog(
    val id: String = "",
    val order: Int = 0,
    @SerialName("set_type") val setType: String = SetType.WORKING,
    val status: String = SetStatus.PENDING,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val meters: Double? = null,
    val effort: String? = null,
    @SerialName("set_duration_seconds") val setDurationSeconds: Int? = null,
    @SerialName("rest_before_seconds") val restBeforeSeconds: Int? = null,
    @SerialName("setup_before_seconds") val setupBeforeSeconds: Int? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class SessionExercise(
    val id: String = "",
    @SerialName("source_plan_exercise_id") val sourcePlanExerciseId: String? = null,
    @SerialName("exercise_id") val exerciseId: String = "",
    val name: String = "",
    val order: Int = 0,
    val notes: String? = null,
    @SerialName("measurement_type") val measurementType: String = MeasurementType.LOAD_REPS,
    @SerialName("load_direction") val loadDirection: String = LoadDirection.HIGHER,
    @SerialName("load_basis") val loadBasis: String = LoadBasis.STACK,
    val target: Target = Target(),
    @SerialName("rest_seconds") val restSeconds: Int = 90,
    @SerialName("superset_group") val supersetGroup: String? = null,
    @SerialName("planned_sets") val plannedSets: Int = 0,
    @SerialName("suggested_weight_kg") val suggestedWeightKg: Double? = null,
    @SerialName("suggestion_reason") val suggestionReason: String? = null,
    val sets: List<SetLog> = emptyList(),
)

@Serializable
data class Session(
    @SerialName("gym_id") val gymId: String? = null,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("plan_name") val planName: String = "",
    val mode: String = WorkoutMode.NORMAL,
    val status: String = SessionStatus.ACTIVE,
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val notes: String? = null,
    @SerialName("import_key") val importKey: String? = null,
    val exercises: List<SessionExercise> = emptyList(),
) {
    /** Sort key used for "newest first" (completed time, else start). */
    val effectiveTime: String get() = completedAt ?: startedAt
}

@Serializable
data class BodyWeight(
    val date: String = "",
    @SerialName("weight_kg") val weightKg: Double = 0.0,
    val notes: String? = null,
)

@Serializable
data class Milestone(
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("plan_exercise_id") val planExerciseId: String? = null,
    @SerialName("exercise_id") val exerciseId: String = "",
    @SerialName("exercise_name") val exerciseName: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("occurred_at") val occurredAt: String = "",
    @SerialName("previous_weight_kg") val previousWeightKg: Double? = null,
    @SerialName("new_weight_kg") val newWeightKg: Double = 0.0,
    val direction: String? = null,
    val seen: Boolean? = null,
)
