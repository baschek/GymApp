package de.baschek.gymapp.core.stats

import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.WorkoutMode
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

data class PlanSuggestion(
    val planId: String,
    val name: String,
    val lastDoneAt: Instant?,
    /** Whole days since the last completed session of the plan; null = never done. */
    val daysAgo: Long?,
)

/**
 * Orders plans for "Heute": the plan that was done longest ago comes first, plans that were never
 * done come before all others (in their given order). Ties keep the given order.
 *
 * @param plans (id, name) in display order
 */
fun recommendPlans(plans: List<Pair<String, String>>, sessions: List<Session>, now: Instant): List<PlanSuggestion> {
    val lastByPlan = HashMap<String, Instant>()
    for (s in sessions) {
        if (s.status != SessionStatus.COMPLETED) continue
        val id = s.planId ?: continue
        val at = runCatching { Instant.parse(s.effectiveTime) }.getOrNull() ?: continue
        val old = lastByPlan[id]
        if (old == null || at > old) lastByPlan[id] = at
    }
    return plans.map { (id, name) ->
        val last = lastByPlan[id]
        PlanSuggestion(id, name, last, last?.let { Duration.between(it, now).toDays().coerceAtLeast(0) })
    }.withIndex()
        .sortedWith(compareBy<IndexedValue<PlanSuggestion>>({ it.value.lastDoneAt != null }, { it.value.lastDoneAt }).thenBy { it.index })
        .map { it.value }
}

private const val SECONDS_PER_SET = 45
private const val SETUP_SECONDS_PER_EXERCISE = 60

/** Rough duration of a plan in a mode: 45 s per set plus the rest, 60 s setup per exercise. */
fun estimateMinutes(exercises: List<PlanExercise>, mode: String): Int {
    var seconds = 0
    for (e in exercises) {
        val sets = e.modeSets.forMode(mode)
        if (sets <= 0) continue
        seconds += SETUP_SECONDS_PER_EXERCISE + sets * (SECONDS_PER_SET + e.restSeconds) - e.restSeconds
    }
    return (seconds / 60.0).roundToInt()
}

val WORKOUT_MODES = listOf(WorkoutMode.NORMAL, WorkoutMode.FAST, WorkoutMode.ULTRA)
