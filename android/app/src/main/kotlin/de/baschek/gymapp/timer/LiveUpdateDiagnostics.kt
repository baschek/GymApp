package de.baschek.gymapp.timer

// TEMPORARY(live-update-diagnostics): remove after the Now Bar issue is solved

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * TEMPORARY(live-update-diagnostics): remove after the Now Bar issue is solved.
 *
 * Builds a plain-text report (one key=value per line) about everything that decides whether Android 16 / One UI 8
 * treats the workout notification as a Live Update. Every value is read on its own and failures are printed, never
 * thrown. No personal data and no notification title/text/workout content.
 */
object LiveUpdateDiagnostics {
    private const val ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS = "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"
    private const val PERMISSION_POST_PROMOTED_NOTIFICATIONS = "android.permission.POST_PROMOTED_NOTIFICATIONS"

    /** Notification.FLAG_PROMOTED_ONGOING (API 36); fallback only if the field cannot be read by reflection. */
    private const val FLAG_PROMOTED_ONGOING_FALLBACK = 0x00040000

    fun build(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Werte während eines laufenden Trainings ablesen.")
        sb.appendLine()
        val app = context.applicationContext
        device(app, sb)
        permissions(app, sb)
        channels(app, sb)
        active(app, sb)
        intents(app, sb)
        return sb.toString().trimEnd()
    }

    private fun line(sb: StringBuilder, key: String, block: () -> Any?) {
        val value = try {
            block().toString()
        } catch (t: Throwable) {
            "ERROR ${t.javaClass.simpleName}: ${t.message}"
        }
        sb.append(key).append('=').appendLine(value)
    }

    private fun device(context: Context, sb: StringBuilder) {
        line(sb, "manufacturer") { Build.MANUFACTURER }
        line(sb, "model") { Build.MODEL }
        line(sb, "sdk_int") { Build.VERSION.SDK_INT }
        line(sb, "release") { Build.VERSION.RELEASE }
        line(sb, "sdk_int_full") {
            try {
                Build.VERSION::class.java.getField("SDK_INT_FULL").getInt(null)
            } catch (e: NoSuchFieldException) {
                "n/a"
            }
        }
        line(sb, "sem_platform_int") {
            try {
                Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
            } catch (e: NoSuchFieldException) {
                "n/a"
            }
        }
        line(sb, "app_versionName") { packageInfo(context, 0).versionName }
        line(sb, "app_versionCode") { packageInfo(context, 0).longVersionCode }
        line(sb, "app_targetSdk") { context.applicationInfo.targetSdkVersion }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context, flags: Int) =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            context.packageManager.getPackageInfo(context.packageName, flags)
        }

    private fun permissions(context: Context, sb: StringBuilder) {
        val manager = context.getSystemService(NotificationManager::class.java)
        line(sb, "areNotificationsEnabled") { manager?.areNotificationsEnabled() ?: "no NotificationManager" }
        line(sb, "canPostPromotedNotifications") {
            if (Build.VERSION.SDK_INT >= 36) canPostPromoted(manager) else "n/a (SDK < 36)"
        }
        line(sb, "perm_POST_NOTIFICATIONS") { granted(context, Manifest.permission.POST_NOTIFICATIONS) }
        line(sb, "perm_POST_PROMOTED_NOTIFICATIONS") { granted(context, PERMISSION_POST_PROMOTED_NOTIFICATIONS) }
        line(sb, "manifest_requests_POST_PROMOTED_NOTIFICATIONS") {
            packageInfo(context, PackageManager.GET_PERMISSIONS).requestedPermissions
                ?.contains(PERMISSION_POST_PROMOTED_NOTIFICATIONS) ?: false
        }
        line(sb, "areNotificationsPaused") {
            try {
                NotificationManager::class.java.getMethod("areNotificationsPaused").invoke(manager)
            } catch (e: NoSuchMethodException) {
                "n/a"
            }
        }
        line(sb, "interruptionFilter") { manager?.currentInterruptionFilter }
    }

    @RequiresApi(36)
    private fun canPostPromoted(manager: NotificationManager?): Any =
        manager?.canPostPromotedNotifications() ?: "no NotificationManager"

    private fun granted(context: Context, permission: String): String =
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"

    private fun channels(context: Context, sb: StringBuilder) {
        val manager = context.getSystemService(NotificationManager::class.java)
        line(sb, "channels_count") { manager?.notificationChannels?.size ?: "no NotificationManager" }
        try {
            manager?.notificationChannels?.forEach { c ->
                line(sb, "channel[${c.id}]") {
                    "importance=${c.importance} sound=${c.sound != null} vibration=${c.shouldVibrate()} " +
                        "lockscreenVisibility=${c.lockscreenVisibility} canBypassDnd=${c.canBypassDnd()}"
                }
            }
        } catch (t: Throwable) {
            line(sb, "channels_error") { throw t }
        }
    }

    private fun promotedOngoingFlag(): Int = try {
        Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null)
    } catch (t: Throwable) {
        FLAG_PROMOTED_ONGOING_FALLBACK
    }

    private fun active(context: Context, sb: StringBuilder) {
        val manager = context.getSystemService(NotificationManager::class.java)
        line(sb, "FLAG_PROMOTED_ONGOING_value") { "0x" + Integer.toHexString(promotedOngoingFlag()) }
        val list = try {
            manager?.activeNotifications?.toList().orEmpty()
        } catch (t: Throwable) {
            line(sb, "activeNotifications_error") { throw t }
            return
        }
        line(sb, "active_count") { list.size }
        list.forEachIndexed { i, sbn ->
            val n = sbn.notification
            line(sb, "active[$i]") {
                val extras = n.extras
                val promotable = if (Build.VERSION.SDK_INT >= 36) hasPromotable(n) else "n/a"
                val keys = try {
                    extras.keySet().filter { k ->
                        k.contains("promot", ignoreCase = true) || k.contains("progress", ignoreCase = true)
                    }.sorted().joinToString(",") { k ->
                        val v = extras.get(k)
                        when (v) {
                            null -> "$k:null"
                            is Number, is Boolean -> "$k:$v"
                            else -> "$k:${v.javaClass.simpleName}"
                        }
                    }
                } catch (t: Throwable) {
                    "ERROR ${t.javaClass.simpleName}"
                }
                "id=${sbn.id} channelId=${n.channelId} flags=0x${Integer.toHexString(n.flags)} " +
                    "promotedOngoingFlag=${(n.flags and promotedOngoingFlag()) != 0} category=${n.category} " +
                    "isOngoing=${sbn.isOngoing} hasPromotableCharacteristics=$promotable " +
                    "template=${extras?.getString(Notification.EXTRA_TEMPLATE)} extrasKeys=[$keys]"
            }
        }
    }

    @RequiresApi(36)
    private fun hasPromotable(n: Notification): Any = try {
        n.hasPromotableCharacteristics()
    } catch (t: Throwable) {
        "ERROR ${t.javaClass.simpleName}"
    }

    private fun intents(context: Context, sb: StringBuilder) {
        resolve(context, sb, "intent_MANAGE_APP_PROMOTED_NOTIFICATIONS", Intent(ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS))
        resolve(context, sb, "intent_APP_NOTIFICATION_SETTINGS", Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
        sb.appendLine("hinweis=resolvable=false kann auch an der Paket-Sichtbarkeit (Android 11+) liegen")
    }

    private fun resolve(context: Context, sb: StringBuilder, key: String, intent: Intent) {
        line(sb, key) {
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            val info = context.packageManager.resolveActivity(intent, 0)
            "resolvable=${info != null} activity=${info?.activityInfo?.let { it.packageName + "/" + it.name }}"
        }
    }
}
