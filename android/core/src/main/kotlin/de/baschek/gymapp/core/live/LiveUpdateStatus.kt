package de.baschek.gymapp.core.live

/** Whether Android 16+ may show the workout notification as a "Live Update" (Samsung: Now Bar). */
enum class LiveUpdateStatus {
    /** The system allows promoted notifications for the app. */
    ALLOWED,

    /** Official support exists (Android 16 QPR2 / API 36.1 or newer), but the user has not allowed it. */
    NOT_ALLOWED,

    /** Android 16 before QPR2: the platform reports "not allowed" for every app; no official switch exists. */
    NOT_OFFICIAL,

    /** Like [NOT_OFFICIAL], but the Samsung developer switch for third-party live notifications is on. */
    SAMSUNG_TEST_SWITCH,

    /** Older than Android 16. */
    UNSUPPORTED,
}

object LiveUpdateStatusLogic {
    /** SDK_INT_FULL of API 36.1 (major * 100000 + minor). */
    const val SDK_FULL_36_1 = 3_600_001

    /**
     * @param sdkInt Build.VERSION.SDK_INT
     * @param sdkIntFull Build.VERSION.SDK_INT_FULL, or null if unreadable
     * @param canPost NotificationManager.canPostPromotedNotifications()
     * @param samsungTestSwitch value of Settings.Secure "enable_notification_nowbar_test" (null if not Samsung or unreadable)
     */
    fun decide(sdkInt: Int, sdkIntFull: Int?, canPost: Boolean, samsungTestSwitch: Int?): LiveUpdateStatus {
        if (sdkInt < 36) return LiveUpdateStatus.UNSUPPORTED
        if (canPost) return LiveUpdateStatus.ALLOWED
        val full = sdkIntFull ?: (sdkInt * 100_000)
        if (full >= SDK_FULL_36_1 || sdkInt > 36) return LiveUpdateStatus.NOT_ALLOWED
        return if (samsungTestSwitch == 1) LiveUpdateStatus.SAMSUNG_TEST_SWITCH else LiveUpdateStatus.NOT_OFFICIAL
    }
}
