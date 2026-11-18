package de.baschek.gymapp.core

import de.baschek.gymapp.core.health.PulseState
import de.baschek.gymapp.core.health.pulseErrorReason
import de.baschek.gymapp.core.health.pulseStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PulseStatusTest {
    @Test
    fun recordingWithAndWithoutReading() {
        val measuring = pulseStatus(true, true, recording = true, bpm = 124, error = null, autoConsent = true)
        assertEquals(PulseState.MEASURING, measuring.state)
        assertEquals("♥ 124", measuring.label)
        val waiting = pulseStatus(true, true, recording = true, bpm = null, error = null, autoConsent = true)
        assertEquals(PulseState.NO_READING, waiting.state)
        assertFalse(waiting.detail!!.contains("enger"))
    }

    @Test
    fun notRecordingSaysSoAndOffersAStart() {
        val off = pulseStatus(true, true, recording = false, bpm = null, error = null, autoConsent = false)
        assertEquals(PulseState.OFF, off.state)
        assertEquals("Puls aus", off.label)
        assertTrue(off.canStart)
        assertFalse(off.askConsent)
        assertTrue(pulseStatus(true, true, false, null, null, null).askConsent)
        assertFalse(pulseStatus(false, true, false, null, null, null).canStart)
    }

    @Test
    fun permissionAndErrorsNameTheReason() {
        assertEquals(PulseState.PERMISSION_MISSING, pulseStatus(true, false, false, null, null, true).state)
        val error = pulseStatus(true, true, false, null, "Another exercise is active or its status is unknown", true)
        assertEquals(PulseState.ERROR, error.state)
        assertEquals("Eine andere App zeichnet gerade ein Training auf", error.detail)
        assertEquals("Alte Aufzeichnung wurde aufgeräumt", pulseErrorReason("Orphaned recording closed at start"))
        assertEquals("Unbekannter Fehler", pulseErrorReason(null))
    }
}
