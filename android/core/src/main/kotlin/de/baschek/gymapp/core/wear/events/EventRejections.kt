package de.baschek.gymapp.core.wear.events

/**
 * Splits [EventResult.Rejected] reasons into those that may succeed later (keep the event
 * unacknowledged so the sender retries) and permanent ones (acknowledge so the sender drops it).
 */
object EventRejections {
    private val transient = setOf("local_only", "profile_local_only", "resolution_missing_conflict")

    fun isTransient(reason: String): Boolean = reason in transient

    /** True when the receiver must acknowledge although it did not apply the event. */
    fun shouldAcknowledge(rejected: EventResult.Rejected): Boolean = !isTransient(rejected.reason)
}
