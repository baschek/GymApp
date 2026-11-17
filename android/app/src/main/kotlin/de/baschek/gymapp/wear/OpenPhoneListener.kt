package de.baschek.gymapp.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.MainActivity
import de.baschek.gymapp.R
import de.baschek.gymapp.data.Profile
import kotlinx.coroutines.runBlocking

/** Background activity starts are restricted: request is shown as a user-tappable notification. */
class OpenPhoneListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val health = event.path == "/gymapp/open-health/v1"
        if (!health && event.path != "/gymapp/open-phone/v1") return
        val repository = (application as GymApplication).container.repository
        if (runBlocking { repository.currentProfile() } != Profile.PERSONAL) return
        val session = runBlocking {
            val repo = (application as GymApplication).container.repository
            if (repo.currentProfile() == Profile.PERSONAL) repo.getActiveSession() else null
        }
        if (!health && session == null) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("wear_handoff", "Uhr und Handy", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(this, MainActivity::class.java).apply {
            if (health) putExtra(MainActivity.EXTRA_HEALTH_SETTINGS, true)
            else putExtra(MainActivity.EXTRA_SESSION_ID, session!!.id)
        }
        val notificationId = if (health) 992 else 991
        val pending = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "wear_handoff")
            .setSmallIcon(R.drawable.ic_stat_timer).setContentTitle(if (health) "Gesundheit auf dem Handy einrichten" else "Training auf dem Handy öffnen")
            .setContentText(if (health) "Tippen, um die Health-Connect-Einstellungen zu öffnen" else "Tippen, um das laufende Training fortzusetzen")
            .setContentIntent(pending).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(this).notify(notificationId, notification) } catch (_: SecurityException) { }
    }
}
