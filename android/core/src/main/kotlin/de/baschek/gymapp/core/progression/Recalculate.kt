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
    /** Working weights this session changed, per gym x exercise. */
    val workingWeights: Map<GymExerciseKey, WorkingWeight>,
    val milestones: List<MilestoneDraft>,
)

/**
 * Completes an active session: pending sets get [pendingReason], and the
 * per-gym working weight changes when two qualifying sets prove it (amendment A).
 * [history] are the other sessions (any status; only completed ones count).
 * Sessions without a gym never change a working weight. [baselines] are manually set weights (amendment D).
 */
fun finishSession(
    sessionId: String,
    session: Session,
    pendingReason: String,
    nowIso: String,
    durationSeconds: Int,
    newId: () -> String,
    history: List<Pair<String, Session>> = emptyList(),
    baselines: Map<GymExerciseKey, WorkingWeight> = emptyMap(),
): FinishResult {
    require(pendingReason != SetStatus.PENDING && pendingReason != SetStatus.COMPLETED && pendingReason != SetStatus.FAILED)
    val finalized = session.copy(
        status = SessionStatus.COMPLETED,
        completedAt = nowIso,
        durationSeconds = durationSeconds.coerceAtLeast(0),
        exercises = session.exercises.map { ex ->
            ex.copy(sets = ex.sets.map { if (it.status == SetStatus.PENDING) it.copy(status = pendingReason, weightKg = null, addonWeightsKg = null) else it })
        },
    )
    val gym = finalized.gymId ?: return FinishResult(finalized, emptyMap(), emptyList())
    val others = history.filter { it.first != sessionId }
    val keys = finalized.exercises.map { GymExerciseKey(gym, it.exerciseId) }.toSet()
    val before = recalculateWorkingWeights(others, keys, baselines)
    val after = recalculateWorkingWeights(others + (sessionId to finalized), keys, baselines)

    val changed = after.filter { (_, w) -> w != null && w.sourceSessionId == sessionId }
        .mapValues { it.value!! }
    val drafts = changed.map { (key, w) ->
        val performed = finalized.exercises.last { it.exerciseId == key.exerciseId }
        MilestoneDraft(
            newId(),
            Milestone(
                planId = finalized.planId,
                planExerciseId = performed.sourcePlanExerciseId,
                exerciseId = key.exerciseId,
                exerciseName = performed.name,
                sessionId = sessionId,
                occurredAt = nowIso,
                previousWeightKg = before[key]?.weightKg,
                newWeightKg = w.weightKg,
                direction = performed.loadDirection,
                seen = false,
            ),
        )
    }
    return FinishResult(finalized, changed, drafts)
}
