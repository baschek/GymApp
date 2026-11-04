package de.baschek.gymapp.timer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Whether Android 16+ may show the workout notification as a "Live Update" (Samsung: Now Bar). */
enum class LiveUpdateStatus { ALLOWED, NOT_ALLOWED, UNSUPPORTED }

object LiveUpdateSupport {
    fun status(context: Context): LiveUpdateStatus {
        if (Build.VERSION.SDK_INT < 36) return LiveUpdateStatus.UNSUPPORTED
        val manager = context.getSystemService(NotificationManager::class.java) ?: return LiveUpdateStatus.UNSUPPORTED
        return if (manager.canPostPromotedNotifications()) LiveUpdateStatus.ALLOWED else LiveUpdateStatus.NOT_ALLOWED
    }

    /** Opens the per-app switch for promoted notifications (Android 16+), else the app notification settings. */
    fun settingsIntent(context: Context): Intent {
        val intent = if (Build.VERSION.SDK_INT >= 36) {
            Intent(Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
        return intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens [settingsIntent], or the app notification settings if no activity handles it (e.g. Samsung variants). */
    fun open(context: Context) {
        try {
            context.startActivity(settingsIntent(context))
        } catch (e: android.content.ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
