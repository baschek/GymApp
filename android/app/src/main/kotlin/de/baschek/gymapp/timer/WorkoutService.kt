package de.baschek.gymapp.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
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
import de.baschek.gymapp.core.sync.WorkoutPoll
import de.baschek.gymapp.core.training.RestAlarm
import de.baschek.gymapp.core.training.upNextExerciseIndex
import de.baschek.gymapp.sync.SyncOutcome
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

private data class ServiceView(
    val session: de.baschek.gymapp.data.Stored<Session>?,
    val gyms: List<de.baschek.gymapp.data.Stored<de.baschek.gymapp.core.model.Gym>>,
    val timer: TimerState?,
    val focus: String?,
)

/**
 * Foreground service that keeps one ongoing notification for the whole active session:
 * current exercise and set, a chronometer (rest countdown, set/setup count-up, else session
 * time) and the actions "Satz geschafft" / "Pause überspringen". It restarts itself after
 * process death (sticky) and stops as soon as no session is active.
 */
class WorkoutService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var syncJob: Job? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        // Promotion ("Live Update") requires an importance above MIN; DEFAULT is used, kept silent
        // (no sound, no vibration) here and per notification. The old LOW channel cannot be raised
        // after creation, so it is replaced by a new channel id.
        manager.deleteNotificationChannel(LEGACY_CHANNEL)
        val channel = NotificationChannel(CHANNEL, getString(R.string.timer_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = getString(R.string.timer_channel_description)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
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
                    container.workout.focus,
                ) { session, gyms, timer, focus -> ServiceView(session?.takeIf { it.value.status == "active" }, gyms, timer, focus) }
                    .collectLatest { (session, gyms, timer, focus) ->
                        if (session == null) {
                            RestAlarmScheduler.cancel(this@WorkoutService)
                            RestAlarmReceiver.cancelNotification(this@WorkoutService)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            return@collectLatest
                        }
                        val gymName = gyms.firstOrNull { it.id == session.value.gymId }?.value?.name
                            ?: if (session.value.gymId == OTHER_GYM_ID) getString(R.string.gym_other_name) else null
                        val manager = getSystemService(NotificationManager::class.java)
                        val now = System.currentTimeMillis()
                        val notification = build(session.id, session.value, gymName, timer, focus, now)
                        manager.notify(ID, notification)
                        logPromotion(manager, notification)
                        // Doze-safe rest-end alarm (vibration + "Pause vorbei"); null cancels it
                        // (skip, set logged, rest reached, workout ended). "+15 s" re-plans it.
                        val alarmAt = RestAlarm.triggerAt(timer?.phase == TimerPhase.REST, timer?.endsAtMillis, now)
                        if (alarmAt != null) {
                            RestAlarmScheduler.schedule(this@WorkoutService, alarmAt)
                        } else {
                            RestAlarmScheduler.cancel(this@WorkoutService)
                        }
                        if (timer?.phase != TimerPhase.REST) RestAlarmReceiver.cancelNotification(this@WorkoutService)
                        // Re-post once the planned rest ends so the chronometer switches to counting up.
                        timer?.endsAtMillis?.let { end ->
                            val wait = end - System.currentTimeMillis()
                            if (wait > 0) {
                                delay(wait + 200)
                                manager.notify(ID, build(session.id, session.value, gymName, timer, focus, System.currentTimeMillis()))
                            }
                        }
                    }
            }
        }
        if (syncJob?.isActive != true) syncJob = scope.launch { syncWhileActive(container) }
        return START_STICKY
    }

    /**
     * While a workout is active: sync about every 45 s (cheap status check first; backoff up to 5 min after errors, the
     * reconnect backoff of the sync manager still applies). This pushes the running session, so the server can see where
     * the user is, and pulls assistant adjustments, which are applied after each run (contract v1 amendment D).
     */
    private suspend fun syncWhileActive(container: de.baschek.gymapp.AppContainer) {
        var failures = 0
        while (true) {
            if (container.repository.getActiveSession() == null) return
            val outcome = try {
                container.workout.publishFocus()
                container.syncManager.runSync()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncOutcome.RETRY
            }
            when (outcome) {
                SyncOutcome.NOT_PAIRED -> return
                SyncOutcome.SUCCESS, SyncOutcome.ALREADY_RUNNING -> failures = 0
                else -> failures++
            }
            delay(WorkoutPoll.delayMillis(failures))
        }
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

    private fun build(sessionId: String, session: Session, gymName: String?, timer: TimerState?, focus: String?, now: Long): Notification {
        // The exercise the user jumped to (if it still has open sets), else the first one with open sets: the same one
        // "Satz geschafft" logs (WorkoutActions.logNextPrefilled).
        val exercise = session.exercises.getOrNull(upNextExerciseIndex(session.exercises, focus))
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
            .setCategory(CATEGORY_WORKOUT)
            .setColorized(false)
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

        // Android 16+ "Live Update" (promoted ongoing notification). Requirements: ongoing foreground
        // service, contentTitle, not colorized, no custom views. Older versions ignore these calls and
        // show the plain ongoing notification with the chronometer above.
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
            val resting = timer?.phase == TimerPhase.REST && end != null && end > now
            // A ticking rest countdown is the chronometer (shown live in the status bar chip without
            // re-posting every second); otherwise the chip shows the current set as short critical text.
            val restOver = timer?.phase == TimerPhase.REST && !resting && end != null
            when {
                restOver -> builder.setShortCriticalText(getString(R.string.notif_chip_rest_over))
                !resting && exercise != null && setNumber != null ->
                    builder.setShortCriticalText(getString(R.string.notif_chip_set, setNumber, exercise.sets.size))
            }
            progressStyle(session)?.let { builder.setStyle(it) }
        }
        return builder.build()
    }

    /** Segments = exercises (length = sets), progress = sets already handled; null without sets. */
    private fun progressStyle(session: Session): NotificationCompat.ProgressStyle? {
        val exercises = session.exercises.filter { it.sets.isNotEmpty() }
        if (exercises.isEmpty()) return null
        val accent = Color.rgb(0xA8, 0xC7, 0xFA)
        val segments = exercises.map { NotificationCompat.ProgressStyle.Segment(it.sets.size).setColor(accent) }
        val done = exercises.sumOf { ex -> ex.sets.count { it.status != SetStatus.PENDING } }
        return NotificationCompat.ProgressStyle().setProgressSegments(segments).setProgress(done)
    }

    private var lastPromotionLog: String? = null

    /** Logs (only on change, no content) whether the system considers the notification promotable. */
    private fun logPromotion(manager: NotificationManager, notification: Notification) {
        if (Build.VERSION.SDK_INT < 36) return
        val line = "promotable=${notification.hasPromotableCharacteristics()} allowed=${manager.canPostPromotedNotifications()} " +
            "importance=${manager.getNotificationChannel(CHANNEL)?.importance}"
        if (line != lastPromotionLog) {
            lastPromotionLog = line
            Log.d(TAG, line)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOG_SET = "de.baschek.gymapp.timer.LOG_SET"
        const val ACTION_SKIP_REST = "de.baschek.gymapp.timer.SKIP_REST"
        /** Notification.CATEGORY_WORKOUT (API 36); the string is ignored by older versions. */
        private const val CATEGORY_WORKOUT = "workout"
        private const val TAG = "WorkoutService"
        private const val LEGACY_CHANNEL = "timer"
        private const val CHANNEL = "timer_live"
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
