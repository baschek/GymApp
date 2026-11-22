package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.*
import de.baschek.gymapp.core.progression.prefillSet
import de.baschek.gymapp.core.training.setPlannedSetWeight
import kotlin.test.Test
import kotlin.test.assertEquals

class PlannedSetWeightTest {
    private val session = Session(status = SessionStatus.ACTIVE, exercises = listOf(SessionExercise(
        id = "exercise", suggestedWeightKg = 30.0, sets = listOf(
            SetLog(id = "warmup", setType = SetType.WARMUP), SetLog(id = "working")
        )
    )))

    @Test fun draftChangesOnlyOpenSetAndIsTheWatchPrefill() {
        val changed = setPlannedSetWeight(session, "exercise", "warmup", 27.5)
        assertEquals(27.5, changed.exercises.single().sets.first().weightKg)
        assertEquals(SetStatus.PENDING, changed.exercises.single().sets.first().status)
        assertEquals(null, changed.exercises.single().sets.last().weightKg)
        assertEquals(27.5, prefillSet(changed.exercises.single(), listOf(20.0)).weightKg)
    }

    @Test fun staleAndInvalidEditsDoNotChangeSession() {
        assertEquals(session, setPlannedSetWeight(session, "exercise", "working", 50.0))
        assertEquals(session, setPlannedSetWeight(session, "exercise", "warmup", Double.NaN))
        assertEquals(session, setPlannedSetWeight(session, "exercise", "warmup", -1.0))
        val finished = session.copy(status = SessionStatus.COMPLETED)
        assertEquals(finished, setPlannedSetWeight(finished, "exercise", "warmup", 50.0))
    }
}
