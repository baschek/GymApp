package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.EffortSource
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.progression.nextStepDown
import de.baschek.gymapp.core.progression.nextStepUp
import de.baschek.gymapp.core.training.ActionOutcome
import de.baschek.gymapp.core.training.RejectReason
import de.baschek.gymapp.core.training.SetCorrection
import de.baschek.gymapp.core.training.correctSet
import de.baschek.gymapp.core.training.nextAfter
import de.baschek.gymapp.core.training.nextExerciseAfter
import de.baschek.gymapp.core.training.reopenSet
import de.baschek.gymapp.core.training.upNextExerciseIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextExerciseTest {
    private fun done(id: String) = SetLog(id = id, status = SetStatus.COMPLETED, weightKg = 50.0, reps = 10, effort = "one_two")
    private fun open(id: String) = SetLog(id = id, status = SetStatus.PENDING)
    private fun skipped(id: String) = SetLog(id = id, status = SetStatus.SKIPPED_OTHER)
    private fun ex(id: String, vararg sets: SetLog) = SessionExercise(id = id, exerciseId = "x-$id", name = "Übung $id", sets = sets.toList())

    @Test
    fun afterAJumpTheFirstOpenExerciseComesNext() {
        // 1 and 2 done, the user jumped from 2 to 4 and finished it: 3 is next, then 5, then 6.
        val list = listOf(ex("1", done("a")), ex("2", done("b")), ex("3", open("c")), ex("4", done("d")), ex("5", open("e")), ex("6", open("f")))
        assertEquals("3", nextExerciseAfter(list, "4")?.id)
        val after3 = list.map { if (it.id == "3") ex("3", done("c")) else it }
        assertEquals("5", nextExerciseAfter(after3, "3")?.id)
        val after5 = after3.map { if (it.id == "5") ex("5", done("e")) else it }
        assertEquals("6", nextExerciseAfter(after5, "5")?.id)
    }

    @Test
    fun theCurrentExerciseIsNeverItsOwnSuccessorAndSkippedOnesAreLeftOut() {
        val list = listOf(ex("1", open("a")), ex("2", skipped("b")), ex("3", open("c")))
        assertEquals(2, nextAfter(list, "1"))
        assertEquals(0, nextAfter(list, "3"))
        assertEquals(0, nextAfter(list, null))
        assertEquals(-1, nextAfter(listOf(ex("1", open("a"))), "1"))
    }

    @Test
    fun phoneUpNextUsesTheSameRule() {
        val list = listOf(ex("1", done("a")), ex("2", open("b")), ex("3", done("c")), ex("4", open("d")))
        assertEquals(1, upNextExerciseIndex(list, "3")) // finished focus: first open one
        assertEquals(3, upNextExerciseIndex(list, "4")) // focus with open sets stays
        assertEquals(nextAfter(list, "3"), upNextExerciseIndex(list, "3"))
    }

    @Test
    fun outcomesSeparateNoOpsFromRejections() {
        assertTrue(ActionOutcome.Changed.changed)
        assertNull(ActionOutcome.Unchanged.message)
        assertFalse(ActionOutcome.Unchanged.changed)
        assertEquals("Pause schon beendet", ActionOutcome.Rejected(RejectReason.REST_ALREADY_OVER).message)
        assertEquals("Höchstens 20 Arbeitssätze", RejectReason.SET_LIMIT.message)
    }

    @Test
    fun weightStepsUseTheMachineAndAnAddOnBeforeAVeryLargeJump() {
        val levels = listOf(150.0, 160.0, 170.0, 180.0)
        assertEquals(180.0, nextStepUp(170.0, levels, listOf(2.5)))
        assertEquals(160.0, nextStepDown(170.0, levels))
        assertEquals(21.25, nextStepUp(20.0, listOf(15.0, 20.0, 25.0), listOf(1.25)))
        assertEquals(25.0, nextStepUp(20.0, listOf(15.0, 20.0, 25.0)))
        assertEquals(182.5, nextStepUp(180.0, levels, listOf(2.5)))
        assertNull(nextStepUp(180.0, levels))
        assertEquals(180.0, nextStepUp(172.5, levels, listOf(2.5)))
        assertEquals(22.5, nextStepUp(20.0, emptyList()))
        assertEquals(17.5, nextStepDown(20.0, emptyList()))
        assertNull(nextStepDown(150.0, levels))
    }

    @Test
    fun correctingTheEffortMarksItAsChosenAndReopeningClearsTheSource() {
        val set = SetLog(id = "s", status = SetStatus.COMPLETED, weightKg = 50.0, reps = 10, effort = "one_two", effortSource = EffortSource.AUTO)
        val session = Session(exercises = listOf(ex("1", set)))
        val same = correctSet(session, "1", "s", SetCorrection(55.0, 10.0, "one_two"))
        assertEquals(EffortSource.AUTO, same.exercises[0].sets[0].effortSource)
        val changed = correctSet(session, "1", "s", SetCorrection(50.0, 10.0, "three_four"))
        assertEquals(EffortSource.USER, changed.exercises[0].sets[0].effortSource)
        val confirmed = correctSet(session, "1", "s", SetCorrection(50.0, 10.0, "one_two", effortSource = EffortSource.USER))
        assertEquals(EffortSource.USER, confirmed.exercises[0].sets[0].effortSource)
        assertNull(reopenSet(changed, "1", "s").exercises[0].sets[0].effortSource)
    }
}
