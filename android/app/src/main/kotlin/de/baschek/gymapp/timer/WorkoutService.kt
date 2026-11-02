package de.baschek.gymapp.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.MainActivity
import de.baschek.gymapp.R
import de.baschek.gymapp.core.model.OTHER_GYM_ID
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.progression.prefillSet
import de.baschek.gymapp.workout.WorkoutActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Foreground service that keeps one ongoing notification for the whole active session:
 * current exercise and set, a chronometer (rest countdown, set/setup count-up, else session
 * time) and the actions "Satz geschafft" / "Pause überspringen". It restarts itself after
 * process death (sticky) and stops as soon as no session is active.
 */
class WorkoutService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, getString(R.string.timer_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.timer_channel_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as GymApplication).container
        // Must call startForeground quickly after startForegroundService().
        if (!started) {
            ServiceCompat.startForeground(this, ID, placeholder(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            started = true
        }
        when (intent?.action) {
            ACTION_LOG_SET -> scope.launch { container.workout.logNextPrefilled() }
            ACTION_SKIP_REST -> container.workout.skipRest()
        }
        if (job == null) {
            job = scope.launch {
                combine(
                    container.repository.observeActiveSession(),
                    container.repository.observeGyms(),
                    container.timer.state,
                ) { session, gyms, timer -> Triple(session?.takeIf { it.value.status == "active" }, gyms, timer) }
                    .collectLatest { (session, gyms, timer) ->
                        if (session == null) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            return@collectLatest
                        }
                        val gymName = gyms.firstOrNull { it.id == session.value.gymId }?.value?.name
                            ?: if (session.value.gymId == OTHER_GYM_ID) getString(R.string.gym_other_name) else null
                        val manager = getSystemService(NotificationManager::class.java)
                        manager.notify(ID, build(session.id, session.value, gymName, timer, System.currentTimeMillis()))
                        // Re-post once the planned rest ends so the chronometer switches to counting up.
                        timer?.endsAtMillis?.let { end ->
                            val wait = end - System.currentTimeMillis()
                            if (wait > 0) {
                                delay(wait + 200)
                                manager.notify(ID, build(session.id, session.value, gymName, timer, System.currentTimeMillis()))
                            }
                        }
                    }
            }
        }
        return START_STICKY
    }

    private fun placeholder(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.notif_session_running))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent(null))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun openIntent(sessionId: String?): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (sessionId != null) putExtra(MainActivity.EXTRA_SESSION_ID, sessionId) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, WorkoutService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun build(sessionId: String, session: Session, gymName: String?, timer: TimerState?, now: Long): Notification {
        val exercise = WorkoutActions.nextExercise(session.exercises)
        val setNumber = exercise?.sets?.indexOfFirst { it.status == SetStatus.PENDING }?.plus(1)
        val title = if (exercise != null && setNumber != null) {
            getString(R.string.notif_title, exercise.name, setNumber, exercise.sets.size)
        } else getString(R.string.notif_session_running)

        val prefill = exercise?.let { prefillSet(it, emptyList()) }
        val planned = prefill?.let {
            val value = it.value.let { v -> if (v == Math.floor(v)) v.toLong().toString() else v.toString() }
            it.weightKg?.let { w ->
                getString(R.string.notif_prefill, if (w == Math.floor(w)) w.toLong().toString() else w.toString(), value)
            } ?: getString(R.string.notif_prefill_nokg, value)
        }
        val phase = when (timer?.phase) {
            TimerPhase.SET -> getString(R.string.workout_timer_set)
            TimerPhase.REST -> getString(R.string.workout_timer_rest)
            TimerPhase.MACHINE_SETUP -> getString(R.string.workout_timer_setup)
            null -> null
        }
        val text = listOfNotNull(phase, planned, gymName).joinToString(" · ")

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openIntent(sessionId))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val end = timer?.endsAtMillis
        when {
            timer != null && end != null && end > now -> builder.setWhen(end).setChronometerCountDown(true)
            timer != null -> builder.setWhen(timer.startedAtMillis).setChronometerCountDown(false)
            else -> builder.setWhen(runCatching { Instant.parse(session.startedAt).toEpochMilli() }.getOrDefault(now))
                .setChronometerCountDown(false)
        }
        if (exercise != null) builder.addAction(0, getString(R.string.notif_action_log), actionIntent(ACTION_LOG_SET, 1))
        if (timer?.phase == TimerPhase.REST) builder.addAction(0, getString(R.string.notif_action_skip), actionIntent(ACTION_SKIP_REST, 2))

        // Android 16+: ask for a promoted ongoing ("Live Update") presentation. Older versions ignore the extra.
        builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOG_SET = "de.baschek.gymapp.timer.LOG_SET"
        const val ACTION_SKIP_REST = "de.baschek.gymapp.timer.SKIP_REST"
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val CHANNEL = "timer"
        private const val ID = 1001

        /** Starts the service; ignored if the system forbids a background start. */
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed from the background; it is started the next time the app is visible.
            }
        }
    }
}
