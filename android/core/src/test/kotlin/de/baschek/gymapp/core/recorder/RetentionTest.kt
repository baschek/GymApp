package de.baschek.gymapp.core.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionTest {
    private val day = RecorderConfig.DAY_MS
    private val now = 100 * day

    private fun session(name: String, ageDays: Double, bytes: Long = 1) =
        StoredSession(name, now - (ageDays * day).toLong(), bytes)

    private val limits = RetentionLimits(maxSessions = 3, maxAgeMs = 14 * day, maxBytes = 1_000)

    @Test fun keepsEverythingWithinAllLimits() {
        val sessions = listOf(session("a", 3.0), session("b", 2.0), session("c", 1.0))
        assertTrue(Retention.expired(sessions, now, limits, "c").isEmpty())
    }

    @Test fun dropsOldestBeyondTheCount() {
        val sessions = (1..6).map { session("s$it", 7.0 - it) }
        assertEquals(setOf("s1", "s2", "s3"), Retention.expired(sessions.shuffled(), now, limits, "s6").toSet())
    }

    @Test fun dropsEverythingOlderThanTheAgeLimit() {
        val sessions = listOf(session("old", 14.5), session("edge", 13.9), session("new", 0.1))
        assertEquals(listOf("old"), Retention.expired(sessions, now, limits, "new"))
    }

    @Test fun dropsOldestUntilTheSizeFits() {
        val sessions = listOf(session("a", 3.0, 600), session("b", 2.0, 600), session("c", 1.0, 600))
        assertEquals(listOf("a", "b"), Retention.expired(sessions, now, limits, "c"))
    }

    @Test fun neverReturnsTheCurrentSessionEvenWhenItAloneIsTooBigOrTooOld() {
        val sessions = listOf(session("a", 1.0, 10), session("current", 30.0, 5_000))
        val doomed = Retention.expired(sessions, now, limits, "current")
        assertEquals(listOf("a"), doomed)
        assertFalse("current" in doomed)
    }

    @Test fun ageRemovalFreesCountAndSizeBeforeNewerSessionsAreTouched() {
        val sessions = listOf(session("stale", 20.0, 900), session("a", 2.0, 400), session("b", 1.0, 400), session("c", 0.5, 100))
        assertEquals(listOf("stale"), Retention.expired(sessions, now, limits, "c"))
    }

    @Test fun phoneAndWatchDefaults() {
        assertEquals(2_000L, RecorderConfig.PHONE.shotIntervalMs)
        assertEquals(5_000L, RecorderConfig.WATCH.shotIntervalMs)
        assertTrue(RecorderConfig.WATCH.maxShotsPerSession < RecorderConfig.PHONE.maxShotsPerSession)
        assertTrue(RecorderConfig.WATCH.retention.maxBytes < RecorderConfig.PHONE.retention.maxBytes)
        assertEquals(14 * day, RecorderConfig.PHONE.retention.maxAgeMs)
    }
}
