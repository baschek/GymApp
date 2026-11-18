package de.baschek.gymapp.core.wear.events

/** Only terminal rejections may be acknowledged, after recording the reason durably. */
object EventRejections {
    fun shouldAcknowledge(rejected: EventResult.Rejected): Boolean = when (rejected.reason) {
        "invalid_event", "invalid_resolution", "invalid_session", "invalid_merge", "event_id_reused", "deleted_session",
        "missing_finish_time", "missing_duration" -> true
        // Capacity, missing causal history, active workouts and profile selection can change.
        else -> false
    }

    /** A receipt is scoped to one session. Invalid IDs cannot be valid predecessors. */
    fun receiptKey(sessionId: String, eventId: String): String? =
        if (WorkoutEvents.uuid(sessionId) && WorkoutEvents.uuid(eventId)) "$sessionId:$eventId" else null
}
