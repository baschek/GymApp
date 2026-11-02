package de.baschek.gymapp.core.progression

import de.baschek.gymapp.core.model.Milestone
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetStatus

/**
 * Finds the performed exercise for a plan entry. This is the single standard
 * matching rule: the explicit plan-entry reference first, the exercise id as
 * fallback (first match in session order wins).
 */
fun findPerformed(session: Session, entry: PlanExercise): SessionExercise? =
    session.exercises.firstOrNull { it.sourcePlanExerciseId == entry.id || it.exerciseId == entry.exerciseId }

data class MilestoneDraft(val id: String, val milestone: Milestone)

data class RecalculationResult(val plan: Plan, val milestones: List<MilestoneDraft>)

/**
 * Replays all completed sessions of [plan] (oldest first) and derives the
 * current plan weights plus the milestones. [seenSessionIds] keeps milestones
 * the user already acknowledged marked as seen.
 */
fun recalculatePlanProgression(
    planId: String,
    plan: Plan,
    sessions: List<Pair<String, Session>>,
    seenSessionIds: Set<String>,
    newId: () -> String,
): RecalculationResult {
    val relevant = sessions
        .filter { (_, s) -> s.planId == planId && s.status == SessionStatus.COMPLETED }
        .sortedBy { (_, s) -> s.effectiveTime }
    val current = HashMap<String, Double?>()
    plan.exercises.forEach { current[it.id] = it.startingWeightKg }
    val out = mutableListOf<MilestoneDraft>()

    for ((sessionId, session) in relevant) {
        for (entry in plan.exercises) {
            val performed = findPerformed(session, entry) ?: continue
            val previous = current[entry.id]
            val next = provenWorkingWeight(performed, previous) ?: continue
            current[entry.id] = next
            out += MilestoneDraft(
                newId(),
                Milestone(
                    planId = planId,
                    planExerciseId = entry.id,
                    exerciseId = performed.exerciseId,
                    exerciseName = performed.name,
                    sessionId = sessionId,
                    occurredAt = session.effectiveTime,
                    previousWeightKg = previous,
                    newWeightKg = next,
                    direction = performed.loadDirection,
                    seen = sessionId in seenSessionIds,
                ),
            )
        }
    }
    val updated = plan.copy(
        exercises = plan.exercises.map { it.copy(currentWeightKg = current[it.id] ?: it.startingWeightKg) },
    )
    return RecalculationResult(updated, out)
}

data class FinishResult(
    val session: Session,
    val plan: Plan?,
    val milestones: List<MilestoneDraft>,
)

/**
 * Completes an active session: pending sets get [pendingReason], and a
 * permanent plan weight change is applied when two qualifying sets prove it.
 */
fun finishSession(
    sessionId: String,
    session: Session,
    plan: Plan?,
    pendingReason: String,
    nowIso: String,
    durationSeconds: Int,
    newId: () -> String,
): FinishResult {
    require(pendingReason != SetStatus.PENDING && pendingReason != SetStatus.COMPLETED && pendingReason != SetStatus.FAILED)
    val finalized = session.copy(
        status = SessionStatus.COMPLETED,
        completedAt = nowIso,
        durationSeconds = durationSeconds.coerceAtLeast(0),
        exercises = session.exercises.map { ex ->
            ex.copy(sets = ex.sets.map { if (it.status == SetStatus.PENDING) it.copy(status = pendingReason) else it })
        },
    )
    if (plan == null) return FinishResult(finalized, null, emptyList())
    val drafts = mutableListOf<MilestoneDraft>()
    val entries = plan.exercises.map { entry ->
        val performed = findPerformed(finalized, entry) ?: return@map entry
        val previous = entry.currentWeightKg ?: entry.startingWeightKg
        val next = provenWorkingWeight(performed, previous) ?: return@map entry
        drafts += MilestoneDraft(
            newId(),
            Milestone(
                planId = finalized.planId,
                planExerciseId = entry.id,
                exerciseId = performed.exerciseId,
                exerciseName = performed.name,
                sessionId = sessionId,
                occurredAt = nowIso,
                previousWeightKg = previous,
                newWeightKg = next,
                direction = performed.loadDirection,
                seen = false,
            ),
        )
        entry.copy(currentWeightKg = next)
    }
    return FinishResult(finalized, plan.copy(exercises = entries), drafts)
}
