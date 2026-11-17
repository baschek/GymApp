package de.baschek.gymapp.watch.runtime

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import de.baschek.gymapp.watch.WatchApplication
import de.baschek.gymapp.wear.backend.workout.WatchWorkoutState
import kotlinx.coroutines.launch

/** Only the device that last operated the replicated workout schedules a rest alarm. */
object RestAlarm {
    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 972,
        Intent(context, RestAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    fun update(context: Context, state: WatchWorkoutState, deviceId: String) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val timer = state.timer
        val end = timer?.endsAtMillis
        if (timer?.phase != "REST" || end == null || end <= System.currentTimeMillis() || state.lastDeviceId != deviceId) {
            cancel(context); return
        }
        try {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending(context))
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending(context))
        } catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending(context)) }
    }
}

class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext as WatchApplication
        app.scope.launch {
            try {
                val state = app.restoreRepository()?.state?.value ?: return@launch
                val timer = state.timer ?: return@launch
                val end = timer.endsAtMillis
                if (timer.phase != "REST" || end == null || end > System.currentTimeMillis() || state.lastDeviceId != app.deviceId) return@launch
                val settings = context.getSharedPreferences("watch-alerts", Context.MODE_PRIVATE)
                val notification = context.getSystemService(NotificationManager::class.java)
                val audio = context.getSystemService(AudioManager::class.java)
                if (notification.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return@launch
                if (settings.getBoolean("vibrate", true)) {
                    val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
                    vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                if (settings.getBoolean("sound", true)) ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).let { tone ->
                    try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150); kotlinx.coroutines.delay(200) } finally { tone.release() }
                }
            } finally { pendingResult.finish() }
        }
    }
}
