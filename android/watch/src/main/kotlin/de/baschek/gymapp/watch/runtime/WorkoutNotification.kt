package de.baschek.gymapp.watch.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.watch.MainActivity
import de.baschek.gymapp.wear.backend.workout.WatchWorkoutState

/** Ongoing workout entry is independent of the optional heart-rate foreground service. */
object WorkoutNotification {
    private const val ID = 482
    fun update(context: Context, state: WatchWorkoutState) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val session = state.session
        if (session?.status != SessionStatus.ACTIVE) { manager.cancel(ID); return }
        manager.createNotificationChannel(NotificationChannel("watch_workout", "Training", NotificationManager.IMPORTANCE_LOW))
        val exercise = session.exercises.firstOrNull { it.id == state.focusExerciseId }
        val index = exercise?.sets?.indexOfFirst { it.status == SetStatus.PENDING } ?: -1
        val description = if (index >= 0 && exercise != null) "Satz ${index + 1}/${exercise.sets.size}" else "Training läuft"
        val touch = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, "watch_workout")
            .setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("GymApp")
            .setContentText(description).setOngoing(true).setContentIntent(touch)
        val restEnd = state.timer?.takeIf { it.phase == "REST" }?.endsAtMillis
        val status = if (restEnd != null && restEnd > System.currentTimeMillis()) {
            notification.setWhen(restEnd).setUsesChronometer(true).setChronometerCountDown(true)
            val endElapsed = android.os.SystemClock.elapsedRealtime() + (restEnd - System.currentTimeMillis()).coerceAtLeast(0)
            Status.forPart(Status.TimerPart(endElapsed))
        } else Status.forPart(Status.TextPart(description))
        OngoingActivity.Builder(context, ID, notification).setStaticIcon(android.R.drawable.ic_menu_compass)
            .setTouchIntent(touch).setStatus(status).build().apply(context)
        try { NotificationManagerCompat.from(context).notify(ID, notification.build()) }
        catch (_: SecurityException) { /* The workout continues without notification permission. */ }
    }
}
