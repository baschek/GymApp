package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.progression.finishSession
import de.baschek.gymapp.core.progression.provenWorkingWeight
import de.baschek.gymapp.core.training.MAX_WORKING_SETS
import de.baschek.gymapp.core.training.addSet
import de.baschek.gymapp.core.training.applyUndo
import de.baschek.gymapp.core.training.canAddSet
import de.baschek.gymapp.core.training.captureUndo
import de.baschek.gymapp.core.training.toggleSkipExercise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Adding a set to the running exercise by hand (synthetic data only). */
class AddSetTest {
    private var counter = 0
    private val newId = { "new-${++counter}" }

    private fun set(id: String, order: Int, status: String = SetStatus.PENDING, weight: Double? = null, reps: Int? = null, type: String = SetType.WORKING) =
        SetLog(id = id, order = order, setType = type, status = status, weightKg = weight, reps = reps, effort = if (status == SetStatus.COMPLETED) "limit" else null)

    private fun session(vararg sets: SetLog, plannedSets: Int = 3) = Session(
        gymId = "g", planName = "Plan", status = SessionStatus.ACTIVE, startedAt = "2026-10-01T10:00:00Z",
        exercises = listOf(
            SessionExercise(
                id = "e1", exerciseId = "bauch", name = "Bauchpresse", order = 1, plannedSets = plannedSets,
                target = Target("reps", 8.0, 12.0, 0.0, 2.0), sets = sets.toList(),
            ),
            SessionExercise(id = "e2", exerciseId = "rudern", name = "Rudern", order = 2, sets = listOf(set("r1", 1))),
        ),
    )

    @Test
    fun appendsOnePendingWorkingSetAtTheEnd() {
        val s = session(set("a", 1, SetStatus.COMPLETED, 40.0, 10), set("b", 2), set("c", 3))
        val out = addSet(s, "e1", newId)
        val sets = out.exercises[0].sets
        assertEquals(listOf("a", "b", "c", "new-1"), sets.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), sets.map { it.order })
        val added = sets.last()
        assertEquals(SetStatus.PENDING, added.status)
        assertEquals(SetType.WORKING, added.setType)
        assertNull(added.completedAt)
        // the plan, the other exercises and the session metadata are untouched
        assertEquals(3, out.exercises[0].plannedSets)
        assertSame(s.exercises[1], out.exercises[1])
        assertEquals(s.copy(exercises = emptyList()), out.copy(exercises = emptyList()))
    }

    @Test
    fun worksWhenEveryOtherSetIsDoneAndReopensTheExercise() {
        val s = session(set("a", 1, SetStatus.COMPLETED, 40.0, 10), set("b", 2, SetStatus.COMPLETED, 40.0, 10), set("c", 3, SetStatus.COMPLETED, 40.0, 9))
        val out = addSet(s, "e1", newId)
        assertEquals(1, out.exercises[0].sets.count { it.status == SetStatus.PENDING })
        assertEquals(3, out.exercises[0].sets.count { it.status == SetStatus.COMPLETED })
    }

    @Test
    fun warmupsDoNotCountForTheLimitButWorkingSetsDo() {
        val warm = set("w", 1, type = SetType.WARMUP)
        val many = (1..MAX_WORKING_SETS - 1).map { set("s$it", it + 1) }
        assertTrue(canAddSet(session(warm, *many.toTypedArray()), "e1"))
        val full = session(warm, *many.toTypedArray(), set("last", 30))
        assertFalse(canAddSet(full, "e1"))
        assertSame(full, addSet(full, "e1", newId))
    }

    @Test
    fun refusesUnknownAndSkippedExercises() {
        val s = session(set("a", 1), set("b", 2))
        assertFalse(canAddSet(s, "nope"))
        val skipped = toggleSkipExercise(s, "e1")
        assertFalse(canAddSet(skipped, "e1"))
        assertSame(skipped, addSet(skipped, "e1", newId))
        assertTrue(canAddSet(toggleSkipExercise(skipped, "e1"), "e1"))
    }

    @Test
    fun carriesTheWeightTheAssistantPlannedForOpenSets() {
        val s = session(set("a", 1, SetStatus.COMPLETED, 40.0, 10), set("b", 2, weight = 45.0), set("c", 3, weight = 45.0))
        assertEquals(45.0, addSet(s, "e1", newId).exercises[0].sets.last().weightKg)
        val none = session(set("a", 1, SetStatus.COMPLETED, 40.0, 10))
        assertNull(addSet(none, "e1", newId).exercises[0].sets.last().weightKg)
    }

    @Test
    fun undoTakesTheAddedSetBack() {
        val s = session(set("a", 1, SetStatus.COMPLETED, 40.0, 10), set("b", 2))
        val entry = captureUndo(s, "e1", "Satz hinzugefügt")!!
        val added = addSet(s, "e1", newId)
        assertEquals(3, added.exercises[0].sets.size)
        assertEquals(s.exercises[0].sets, applyUndo(added, entry).exercises[0].sets)
    }

    @Test
    fun aLoggedExtraSetIsAnOrdinaryWorkingSetForProgression() {
        // plan: 2 sets, the user adds a third and logs 42.5 kg twice at target: 2 qualifying sets prove 42.5 kg
        val s = session(set("a", 1, SetStatus.COMPLETED, 40.0, 10), set("b", 2, SetStatus.COMPLETED, 42.5, 10), plannedSets = 2)
        val extra = addSet(s, "e1", newId)
        val extraId = extra.exercises[0].sets.last().id
        val logged = extra.copy(
            exercises = extra.exercises.map { ex ->
                if (ex.id != "e1") ex else ex.copy(sets = ex.sets.map { if (it.id == extraId) it.copy(status = SetStatus.COMPLETED, weightKg = 42.5, reps = 9, effort = "limit") else it })
            },
        )
        assertEquals(42.5, provenWorkingWeight(logged.exercises[0], 40.0))
        // without the extra set only one set is above 40 kg: nothing is proven
        assertNull(provenWorkingWeight(s.exercises[0], 40.0))
    }

    @Test
    fun anUnloggedExtraSetIsSkippedAtTheEndAndNeverChangesAWeight() {
        val s = session(set("a", 1, SetStatus.COMPLETED, 40.0, 10), set("b", 2, SetStatus.COMPLETED, 40.0, 10), plannedSets = 2)
        val result = finishSession("s1", addSet(s, "e1", newId), SetStatus.SKIPPED_OTHER, "2026-10-01T11:00:00Z", 3600, newId)
        val sets = result.session.exercises[0].sets
        assertEquals(3, sets.size)
        assertEquals(SetStatus.SKIPPED_OTHER, sets.last().status)
        assertNull(sets.last().weightKg)
        assertEquals(SessionStatus.COMPLETED, result.session.status)
    }
}
