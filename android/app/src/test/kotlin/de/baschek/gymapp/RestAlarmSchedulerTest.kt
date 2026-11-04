package de.baschek.gymapp

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.timer.RestAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class RestAlarmSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Test
    fun scheduleSetsOneAlarmAndReplacesIt() {
        RestAlarmScheduler.schedule(context, 90_000L)
        assertEquals(90_000L, alarms.peekNextScheduledAlarm()?.triggerAtTime)
        // "+15 s": same alarm moved, never two alarms.
        RestAlarmScheduler.schedule(context, 105_000L)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(105_000L, alarms.peekNextScheduledAlarm()?.triggerAtTime)
    }

    @Test
    fun cancelRemovesTheAlarm() {
        RestAlarmScheduler.schedule(context, 90_000L)
        assertNotNull(alarms.peekNextScheduledAlarm())
        RestAlarmScheduler.cancel(context)
        assertNull(alarms.peekNextScheduledAlarm())
    }
}
