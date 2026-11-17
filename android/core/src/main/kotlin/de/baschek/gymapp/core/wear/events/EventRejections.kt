package de.baschek.gymapp.core.wear.events

/** A rejection is never proof of durable application; keep the originating event for recovery. */
object EventRejections {
    @Suppress("UNUSED_PARAMETER")
    fun shouldAcknowledge(rejected: EventResult.Rejected): Boolean = false
}
