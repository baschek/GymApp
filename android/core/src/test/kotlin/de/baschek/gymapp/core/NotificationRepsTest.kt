package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.training.NotificationReps
import de.baschek.gymapp.core.training.PrefillLabels
import de.baschek.gymapp.core.training.RepsOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRepsTest {
    private val labels = PrefillLabels("kg", "Wdh", "s", "m", "am Limit", "1–2 übrig", "3–4 übrig", "5+ übrig")

    @Test
    fun stepsPerMetric() {
        assertEquals(1.0, NotificationReps.step(Metric.REPS))
        assertEquals(5.0, NotificationReps.step(Metric.SECONDS))
        assertEquals(10.0, NotificationReps.step(Metric.METERS))
    }

    @Test
    fun adjustedMovesAndStopsAtBounds() {
        assertEquals(12.0, NotificationReps.adjusted(11.0, 1, Metric.REPS))
        assertEquals(10.0, NotificationReps.adjusted(11.0, -1, Metric.REPS))
        assertNull(NotificationReps.adjusted(1.0, -1, Metric.REPS))
        assertNull(NotificationReps.adjusted(999.0, 1, Metric.REPS))
        assertEquals(40.0, NotificationReps.adjusted(45.0, -1, Metric.SECONDS))
        assertNull(NotificationReps.adjusted(5.0, -1, Metric.SECONDS))
        assertEquals(5.0, NotificationReps.adjusted(8.0, -1, Metric.SECONDS))
    }

    @Test
    fun overrideOnlyAppliesToItsSet() {
        val o = RepsOverride("s1", "e1", "set2", 9.0)
        assertEquals(9.0, NotificationReps.valueFor(o, "s1", "e1", "set2"))
        assertNull(NotificationReps.valueFor(o, "s1", "e1", "set3"))
        assertNull(NotificationReps.valueFor(o, "s1", "e2", "set2"))
        assertNull(NotificationReps.valueFor(o, "s2", "e1", "set2"))
        assertNull(NotificationReps.valueFor(null, "s1", "e1", "set2"))
        assertTrue(NotificationReps.applies(o, "s1", "e1", "set2"))
        assertFalse(NotificationReps.applies(o, "s1", "e1", "x"))
    }

    @Test
    fun pressStartsFromBaseAndAccumulates() {
        val first = NotificationReps.press(null, "s", "e", "a", 12.0, -1, Metric.REPS)
        assertEquals(RepsOverride("s", "e", "a", 11.0), first)
        val second = NotificationReps.press(first, "s", "e", "a", first!!.value, -1, Metric.REPS)
        assertEquals(10.0, second!!.value)
    }

    @Test
    fun pressAtBoundKeepsExistingOrNothing() {
        assertNull(NotificationReps.press(null, "s", "e", "a", 1.0, -1, Metric.REPS))
        val o = RepsOverride("s", "e", "a", 1.0)
        assertEquals(o, NotificationReps.press(o, "s", "e", "a", 1.0, -1, Metric.REPS))
        // an override of another set is dropped, not kept
        assertNull(NotificationReps.press(RepsOverride("s", "e", "old", 1.0), "s", "e", "a", 1.0, -1, Metric.REPS))
    }

    @Test
    fun pressForNewSetReplacesOldOverride() {
        val old = RepsOverride("s", "e", "a", 7.0)
        val next = NotificationReps.press(old, "s", "e", "b", 10.0, 1, Metric.REPS)
        assertEquals(RepsOverride("s", "e", "b", 11.0), next)
    }

    @Test
    fun encodeDecodeRoundTripAndGarbage() {
        val o = RepsOverride("s", "e", "a", 11.0)
        assertEquals(o, NotificationReps.decode(NotificationReps.encode(o)))
        assertNull(NotificationReps.decode(null))
        assertNull(NotificationReps.decode("kaputt"))
        assertNull(NotificationReps.decode(NotificationReps.encode(o.copy(value = 0.0))))
    }

    @Test
    fun thirdActionIsSkipOnlyWhileRestRuns() {
        assertTrue(NotificationReps.showsSkipRest(true, 90_000L, 10_000L))
        assertTrue(NotificationReps.showsSkipRest(true, null, 10_000L))
        assertFalse(NotificationReps.showsSkipRest(true, 10_000L, 10_000L))
        assertFalse(NotificationReps.showsSkipRest(true, 5_000L, 10_000L))
        assertFalse(NotificationReps.showsSkipRest(false, 90_000L, 10_000L))
    }

    @Test
    fun summaryWithWeightAndEffort() {
        assertEquals("170 kg × 11 · 1–2 übrig", NotificationReps.summary(170.0, 11.0, Metric.REPS, "one_two", labels))
        assertEquals("17,5 kg × 8 · am Limit", NotificationReps.summary(17.5, 8.0, Metric.REPS, "limit", labels))
        assertEquals("60 kg × 10 · 5+ übrig", NotificationReps.summary(60.0, 10.0, Metric.REPS, "five_plus", labels))
        assertEquals("60 kg × 10 · 3–4 übrig", NotificationReps.summary(60.0, 10.0, Metric.REPS, "three_four", labels))
    }

    @Test
    fun summaryWithoutWeightAndSeconds() {
        assertEquals("12 Wdh · 1–2 übrig", NotificationReps.summary(null, 12.0, Metric.REPS, "one_two", labels))
        assertEquals("45 s", NotificationReps.summary(null, 45.0, Metric.SECONDS, null, labels))
        assertEquals("20 kg × 45 s", NotificationReps.summary(20.0, 45.0, Metric.SECONDS, "unbekannt", labels))
        assertEquals("100 m", NotificationReps.summary(null, 100.0, Metric.METERS, null, labels))
    }
}
