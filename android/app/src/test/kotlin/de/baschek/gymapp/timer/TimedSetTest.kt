package de.baschek.gymapp.timer

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class TimedSetTest {
    @Test fun timerIsBoundToExactlyOneSetAndMeasuresElapsedSeconds() {
        val timer = TimerState(TimerPhase.SET, 1_000, "Synthetic hold", sessionId = "session", exerciseId = "exercise", setId = "set")
        assertTrue(timer.matchesSet("session", "exercise", "set"))
        assertFalse(timer.matchesSet("session", "exercise", "other"))
        assertFalse(timer.matchesSet("other", "exercise", "set"))
        assertEquals(42, timer.elapsedSeconds(43_500))
        assertEquals(0, timer.elapsedSeconds(500))
        assertFalse(timer.copy(phase = TimerPhase.REST).matchesSet("session", "exercise", "set"))
        assertFalse(timer.copy(setId = null).matchesSet("session", "exercise", "set"))
    }
}
