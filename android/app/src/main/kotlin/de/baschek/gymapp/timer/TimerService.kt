package de.baschek.gymapp.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.MainActivity
import de.baschek.gymapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service that shows the running set/rest/machine-setup timer as an ongoing notification. */
class TimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, getString(R.string.timer_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.timer_channel_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as GymApplication).container.timer
        if (intent?.action == ACTION_STOP) {
            controller.stop()
        }
        val initial = controller.state.value
        if (initial == null) {
            // startForegroundService() requires a notification even if we stop right away.
            ServiceCompat.startForeground(this, ID, build(TimerState(TimerPhase.REST, System.currentTimeMillis(), "")), foregroundType())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, ID, build(initial), foregroundType())
        job?.cancel()
        job = scope.launch {
            controller.state.collect { s ->
                if (s == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(ID, build(s))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun foregroundType() = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    private fun build(state: TimerState): Notification {
        val title = when (state.phase) {
            TimerPhase.SET -> R.string.timer_notification_set
            TimerPhase.REST -> R.string.timer_notification_rest
            TimerPhase.MACHINE_SETUP -> R.string.timer_notification_setup
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val rest = if (state.phase == TimerPhase.REST && state.suggestedRestSeconds > 0) {
            " · " + getString(R.string.workout_suggested_rest, "%d:%02d".format(state.suggestedRestSeconds / 60, state.suggestedRestSeconds % 60))
        } else ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(title))
            .setContentText(state.label + rest)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(state.startedAtMillis)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.timer_notification_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "de.baschek.gymapp.timer.STOP"
        private const val CHANNEL = "timer"
        private const val ID = 1001
    }
}
