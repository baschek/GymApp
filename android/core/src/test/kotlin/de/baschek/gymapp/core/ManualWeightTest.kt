package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.progression.GymExerciseKey
import de.baschek.gymapp.core.progression.MANUAL_WEIGHT_SOURCE
import de.baschek.gymapp.core.progression.WorkingWeight
import de.baschek.gymapp.core.progression.finishSession
import de.baschek.gymapp.core.progression.reassignSessionGym
import de.baschek.gymapp.core.progression.recalculateWorkingWeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A weight set by hand is the baseline of every recalculation (contract v1 amendment D), as on the server. */
class ManualWeightTest {
    private val key = GymExerciseKey("gym-a", "press")
    private val manual = mapOf(key to WorkingWeight(65.0, MANUAL_WEIGHT_SOURCE, "2026-11-04T08:00:00.000Z"))

    private fun session(day: String, gym: String, vararg weights: Double, status: String = SessionStatus.COMPLETED) = Session(
        gymId = gym, planName = "Synthetic plan", status = status, startedAt = "${day}T10:00:00.000Z",
        completedAt = if (status == SessionStatus.COMPLETED) "${day}T11:00:00.000Z" else null,
        exercises = listOf(
            SessionExercise(
                id = "e-$day", exerciseId = "press", name = "Test press", target = Target("reps", 8.0, 12.0, 1.0, 2.0),
                sets = weights.mapIndexed { i, w -> SetLog(id = "$day-$i", order = i + 1, status = SetStatus.COMPLETED, weightKg = w, reps = 10, effort = "two_three") },
            ),
        ),
    )

    @Test fun editingAFinishedSessionDoesNotResetTheManualWeight() {
        val old = "old" to session("2026-11-03", "gym-a", 55.0, 55.0)
        assertEquals(55.0, recalculateWorkingWeights(listOf(old), setOf(key))[key]!!.weightKg)
        val kept = recalculateWorkingWeights(listOf(old), setOf(key), manual)[key]!!
        assertEquals(65.0, kept.weightKg)
        assertEquals(MANUAL_WEIGHT_SOURCE, kept.sourceSessionId)
    }

    @Test fun finishingASessionBelowTheManualWeightKeepsItAndAboveDevelopsIt() {
        val old = "old" to session("2026-11-03", "gym-a", 55.0, 55.0)
        val below = finishSession("new", session("2026-11-05", "gym-a", 60.0, 60.0, status = SessionStatus.ACTIVE), "skipped_other", "2026-11-05T11:00:00.000Z", 3600, { "m" }, listOf(old), manual)
        assertNull(below.workingWeights[key]) // nothing changed: stays 65 manual
        assertEquals(0, below.milestones.size)
        val above = finishSession("new", session("2026-11-05", "gym-a", 70.0, 70.0, status = SessionStatus.ACTIVE), "skipped_other", "2026-11-05T11:00:00.000Z", 3600, { "m" }, listOf(old), manual)
        assertEquals(70.0, above.workingWeights[key]!!.weightKg)
        assertEquals(65.0, above.milestones.single().milestone.previousWeightKg)
    }

    @Test fun movingASessionToAnotherGymKeepsTheManualWeightOfTheOldGym() {
        val sessions = listOf("old" to session("2026-11-03", "gym-a", 55.0, 55.0), "wrong" to session("2026-11-02", "gym-a", 50.0, 50.0))
        val result = reassignSessionGym(sessions, "wrong", "gym-b", manual)
        assertEquals(65.0, result[key]!!.weightKg)
        assertEquals(50.0, result[GymExerciseKey("gym-b", "press")]!!.weightKg)
    }

    @Test fun pendingSetsLoseTheirPlannedWeightWhenTheSessionEnds() {
        val s = session("2026-11-05", "gym-a", 60.0, status = SessionStatus.ACTIVE)
        val planned = s.copy(exercises = s.exercises.map { it.copy(sets = it.sets + SetLog(id = "p", order = 2, status = SetStatus.PENDING, weightKg = 62.5)) })
        val done = finishSession("x", planned, "skipped_other", "2026-11-05T11:00:00.000Z", 10, { "m" }).session
        assertNull(done.exercises.single().sets.last().weightKg)
    }
}
