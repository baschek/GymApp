package de.baschek.gymapp.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.MainActivity
import de.baschek.gymapp.R
import de.baschek.gymapp.core.training.RestAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires when the planned rest ends: vibrates once (if "Vibration am Pausenende" is on) and posts one
 * short "Pause vorbei" heads-up notification on its own silent channel. This is the only place that
 * vibrates at the end of a rest; the UI must not vibrate additionally.
 */
class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REST_END) return
        val expectedEnd = intent.getLongExtra(RestAlarmScheduler.EXTRA_END_MILLIS, 0L)
        val app = context.applicationContext as? GymApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val container = app.container
                // After process death the timer is restored asynchronously; make sure it is.
                if (container.timer.state.value == null) runCatching { container.timer.restore() }
                val timer = container.timer.state.value
                val due = RestAlarm.isStillDue(expectedEnd, timer?.phase == TimerPhase.REST, timer?.endsAtMillis)
                if (due) {
                    if (container.prefs.restVibration.first()) vibrate(context)
                    notifyRestOver(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REST_END = "de.baschek.gymapp.timer.REST_END"
        private const val CHANNEL = "rest_end"
        private const val NOTIFICATION_ID = 1002

        fun cancelNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        fun vibrate(context: Context) {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300), -1)
            // Alarm usage so the vibration is also allowed while the app is in the background.
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
            }
        }

        private fun notifyRestOver(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            // Silent on purpose: vibration is done (and switched) by the app setting, not by the channel.
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.rest_end_channel_name), NotificationManager.IMPORTANCE_HIGH)
            channel.description = context.getString(R.string.rest_end_channel_description)
            channel.setSound(null, null)
            channel.enableVibration(false)
            manager.createNotificationChannel(channel)
            val open = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle(context.getString(R.string.rest_end_title))
                .setContentText(context.getString(R.string.rest_end_text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .setContentIntent(open)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
