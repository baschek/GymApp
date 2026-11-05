package de.baschek.gymapp.timer

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import de.baschek.gymapp.core.live.LiveUpdateStatus
import de.baschek.gymapp.core.live.LiveUpdateStatusLogic

object LiveUpdateSupport {
    /** Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS (AOSP 16); used as a string in case the compileSdk stubs lack it. */
    private const val ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"

    /** Samsung developer switch "Live notifications for all apps" (One UI 8.0). */
    private const val SAMSUNG_NOWBAR_TEST = "enable_notification_nowbar_test"

    fun status(context: Context): LiveUpdateStatus {
        if (Build.VERSION.SDK_INT < 36) return LiveUpdateStatus.UNSUPPORTED
        val manager = context.getSystemService(NotificationManager::class.java) ?: return LiveUpdateStatus.UNSUPPORTED
        return LiveUpdateStatusLogic.decide(Build.VERSION.SDK_INT, sdkIntFull(), manager.canPostPromotedNotifications(), samsungTestSwitch(context))
    }

    /** Build.VERSION.SDK_INT_FULL (API 36), read reflectively; null if unavailable. */
    private fun sdkIntFull(): Int? =
        try {
            Build.VERSION::class.java.getField("SDK_INT_FULL").getInt(null)
        } catch (_: Exception) {
            null
        }

    private fun samsungTestSwitch(context: Context): Int? {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return null
        return try {
            Settings.Secure.getInt(context.contentResolver, SAMSUNG_NOWBAR_TEST, 0)
        } catch (_: Exception) {
            null
        }
    }

    /** Opens the per-app promotion switch (Android 16+), else the app notification settings. */
    fun settingsIntent(context: Context): Intent {
        val intent = if (Build.VERSION.SDK_INT >= 36) {
            Intent(ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
        return intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens [settingsIntent], or the app notification settings if no activity handles it. */
    fun open(context: Context) {
        try {
            context.startActivity(settingsIntent(context))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
