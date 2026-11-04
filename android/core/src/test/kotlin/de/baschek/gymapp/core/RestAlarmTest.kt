package de.baschek.gymapp.core

import de.baschek.gymapp.core.training.RestAlarm
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class RestAlarmTest {
    @Test
    fun runningRestSchedulesAtPlannedEnd() {
        assertEquals(90_000L, RestAlarm.triggerAt(true, 90_000L, 10_000L))
    }

    @Test
    fun noAlarmWithoutRestOrEndOrWhenAlreadyReached() {
        assertNull(RestAlarm.triggerAt(false, 90_000L, 10_000L))
        assertNull(RestAlarm.triggerAt(true, null, 10_000L))
        assertNull(RestAlarm.triggerAt(true, 10_000L, 10_000L))
        assertNull(RestAlarm.triggerAt(true, 5_000L, 10_000L))
    }

    @Test
    fun extendingMovesTheAlarmAndStaleAlarmIsIgnored() {
        assertEquals(105_000L, RestAlarm.triggerAt(true, 105_000L, 20_000L))
        assertTrue(RestAlarm.isStillDue(90_000L, true, 90_000L))
        assertFalse(RestAlarm.isStillDue(90_000L, true, 105_000L)) // +15 s after scheduling
        assertFalse(RestAlarm.isStillDue(90_000L, false, null)) // skipped / set logged / workout ended
    }
}
