package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.LoadDirection
import de.baschek.gymapp.core.model.Milestone
import de.baschek.gymapp.core.model.Session

/**
 * One exercise whose proven working weight went up in a workout, shown as a short animation before the summary
 * ("Beinpresse 60 → 65 kg"). For assisted exercises [lowerIsBetter] is true: less assistance is the progress, so the number
 * goes down.
 */
data class WeightIncrease(
    val exerciseId: String,
    val name: String,
    val fromKg: Double,
    val toKg: Double,
    val lowerIsBetter: Boolean,
) {
    /** "60 → 65 kg" */
    fun change(): String = "${deNum(fromKg)} → ${deNum(toKg)} kg"

    /** "Beinpresse 60 → 65 kg" */
    fun label(): String = "$name ${change()}"

    /** The small line above the name. */
    fun caption(): String = if (lowerIsBetter) "Weniger Hilfe" else "Neues Arbeitsgewicht"
}

/**
 * The weight increases this workout produced, in the order of the exercises in [session]. They come from the milestones the
 * finish wrote for [sessionId]; a first working weight (no previous one) is no increase, and neither is a milestone that does not
 * move in the progress direction. Exercises whose name is missing fall back to the session's own name.
 */
fun weightIncreases(milestones: List<Milestone>, sessionId: String, session: Session): List<WeightIncrease> {
    val names = session.exercises.associate { it.exerciseId to it.name }
    val order = session.exercises.map { it.exerciseId }
    return milestones.asSequence()
        .filter { it.sessionId == sessionId }
        .mapNotNull { m ->
            val from = m.previousWeightKg ?: return@mapNotNull null
            val lower = m.direction == LoadDirection.LOWER_ASSISTANCE
            val improved = if (lower) m.newWeightKg < from else m.newWeightKg > from
            if (!improved) return@mapNotNull null
            WeightIncrease(m.exerciseId, m.exerciseName?.takeIf { it.isNotBlank() } ?: names[m.exerciseId] ?: "Übung", from, m.newWeightKg, lower)
        }
        .distinctBy { it.exerciseId }
        .sortedBy { order.indexOf(it.exerciseId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .toList()
}
