package de.baschek.gymapp.core.training

/** Pure planning rules for the alarm that fires when a planned rest ends (also with a locked phone). */
object RestAlarm {
    /**
     * Epoch millis at which the rest-end alarm must fire, or null if no alarm is needed:
     * only a running rest with a planned end in the future. Skipping, logging a set, ending the
     * workout and an already reached end all yield null (= cancel); "+15 s" yields a later time.
     */
    fun triggerAt(isRest: Boolean, endsAtMillis: Long?, nowMillis: Long): Long? =
        if (isRest && endsAtMillis != null && endsAtMillis > nowMillis) endsAtMillis else null

    /**
     * A fired alarm is only valid if the rest it was set for is still running with the same planned end
     * (a stale alarm after skip, "+15 s" or workout end must stay silent).
     */
    fun isStillDue(expectedEndMillis: Long, isRest: Boolean, currentEndMillis: Long?): Boolean =
        isRest && currentEndMillis == expectedEndMillis
}
