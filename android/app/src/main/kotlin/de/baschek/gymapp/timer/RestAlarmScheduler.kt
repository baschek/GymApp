package de.baschek.gymapp.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Sets and cancels the Doze-safe alarm for the end of a rest. The alarm fires [RestAlarmReceiver]
 * even if the phone is locked or idle. There is exactly one alarm; scheduling again replaces it.
 */
object RestAlarmScheduler {
    private const val TAG = "RestAlarm"
    private const val REQUEST_CODE = 4711
    const val EXTRA_END_MILLIS = "end_millis"

    /** True if exact alarms are allowed (always below Android 12; USE_EXACT_ALARM covers 13+). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarms.canScheduleExactAlarms()
    }

    /** Schedules the alarm at [triggerAtMillis] (epoch millis, RTC). Falls back to an inexact alarm. */
    fun schedule(context: Context, triggerAtMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, triggerAtMillis, create = true) ?: return
        try {
            if (canScheduleExact(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                // Exact alarms revoked by the user: may be a few minutes late in Doze.
                Log.w(TAG, "exact alarms not allowed, using inexact alarm")
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, using inexact alarm")
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pendingIntent(context, 0L, create = false)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    private fun pendingIntent(context: Context, endMillis: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, RestAlarmReceiver::class.java)
            .setAction(RestAlarmReceiver.ACTION_REST_END)
            .putExtra(EXTRA_END_MILLIS, endMillis)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
