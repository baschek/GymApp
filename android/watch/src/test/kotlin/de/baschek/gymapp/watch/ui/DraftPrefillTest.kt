package de.baschek.gymapp.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftPrefillTest {
    @Test fun phoneWeightUpdatesDespiteLocallyEditedReps() {
        val draft = Draft(20.0, 10.0, emptySet(), false)
        draft.amount = 12.0
        draft.amountEdited = true
        draft.applyPrefill(27.5, 10.0, setOf(0))
        assertEquals(27.5, draft.weight!!, 0.0)
        assertEquals(12.0, draft.amount, 0.0)
        assertEquals(setOf(0), draft.addOns)
    }

    @Test fun phoneWeightDoesNotOverwriteLocallyEditedWeight() {
        val draft = Draft(20.0, 10.0, emptySet(), false)
        draft.weight = 25.0
        draft.weightEdited = true
        draft.applyPrefill(27.5, 11.0, setOf(0))
        assertEquals(25.0, draft.weight!!, 0.0)
        assertEquals(11.0, draft.amount, 0.0)
        assertEquals(emptySet<Int>(), draft.addOns)
    }
}
