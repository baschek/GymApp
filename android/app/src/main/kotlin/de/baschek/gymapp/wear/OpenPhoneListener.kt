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
        if (event.path != "/gymapp/open-phone/v1") return
        val session = runBlocking {
            val repo = (application as GymApplication).container.repository
            if (repo.currentProfile() == Profile.PERSONAL) repo.getActiveSession() else null
        } ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("wear_handoff", "Uhr und Handy", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, session.id)
        val pending = PendingIntent.getActivity(this, 991, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "wear_handoff")
            .setSmallIcon(R.drawable.ic_stat_timer).setContentTitle("Training auf dem Handy öffnen")
            .setContentText("Tippen, um das laufende Training fortzusetzen")
            .setContentIntent(pending).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(this).notify(991, notification) } catch (_: SecurityException) { }
    }
}
