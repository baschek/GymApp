package de.baschek.gymapp.watch.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.baschek.gymapp.watch.MainActivity
import java.lang.ref.WeakReference

/** Explicit non-exported callback: Android, not the app, decides and confirms installation. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    WatchUpdater.result(WatchUpdateState(WatchUpdatePhase.ERROR, message = "Installationsbestätigung nicht verfügbar"))
                    return
                }
                if (confirmation.resolveActivity(context.packageManager) == null) {
                    WatchUpdater.result(WatchUpdateState(WatchUpdatePhase.UNSUPPORTED_MANUAL_INSTALL, message = "Installationsbestätigung nicht verfügbar. Uhr-App manuell aktualisieren."))
                    return
                }
                pendingConfirmation = confirmation
                WatchUpdater.result(WatchUpdateState(WatchUpdatePhase.INSTALLING, message = "Installation bestätigen"))
                val activity = foreground?.get()
                if (activity != null && !activity.isFinishing && activity.hasWindowFocus()) {
                    Handler(Looper.getMainLooper()).post { showConfirmation(activity) }
                } else {
                    UpdateNotifications.confirm(context, confirmation)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                pendingConfirmation = null
                // A replaced process will initialize with its new BuildConfig; do not assert success
                // solely from a callback that can race package replacement.
                WatchUpdater.result(WatchUpdateState(WatchUpdatePhase.CHECKING, message = "Installationsmeldung erhalten; Version wird geprüft"))
            }
            else -> {
                pendingConfirmation = null
                WatchUpdater.result(WatchUpdateState(WatchUpdatePhase.ERROR, message = "Installation abgebrochen oder fehlgeschlagen"))
            }
        }
    }
    companion object {
        @Volatile private var foreground: WeakReference<Activity>? = null
        @Volatile private var pendingConfirmation: Intent? = null
        fun setForegroundActivity(activity: Activity) { foreground = WeakReference(activity) }
        fun showConfirmation(activity: Activity): Boolean {
            val intent = pendingConfirmation ?: return false
            return try { activity.startActivity(intent); pendingConfirmation = null; true }
            catch (_: Exception) {
                WatchUpdater.result(WatchUpdateState(WatchUpdatePhase.UNSUPPORTED_MANUAL_INSTALL, message = "Installationsbestätigung nicht verfügbar. Uhr-App manuell aktualisieren."))
                false
            }
        }
    }
}

internal object UpdateNotifications {
    private const val CHANNEL = "watch_app_updates"
    private fun builder(context: Context): NotificationCompat.Builder {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Uhr-App-Updates", NotificationManager.IMPORTANCE_DEFAULT))
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("GymApp Uhr-Update").setAutoCancel(true)
    }
    fun dismiss(context: Context) { NotificationManagerCompat.from(context).cancel(491) }
    fun ready(context: Context, version: String) {
        val open = PendingIntent.getActivity(context, 491, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(context, 491, builder(context).setContentText("Version $version ist bereit").setContentIntent(open))
    }
    fun confirm(context: Context, confirmation: Intent) {
        val action = PendingIntent.getActivity(context, 492, Intent(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(context, 492, builder(context).setContentText("Installation bestätigen").setContentIntent(action))
    }
    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        try { NotificationManagerCompat.from(context).notify(id, builder.build()) } catch (_: SecurityException) { }
    }
}
