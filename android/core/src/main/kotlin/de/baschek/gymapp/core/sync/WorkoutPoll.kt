package de.baschek.gymapp.core.sync

/**
 * How often the app syncs while a workout is active, so that adjustments from the assistant arrive and the server can see
 * where the user is (contract v1 amendment D). Base interval 45 s; every consecutive failure (offline, backoff, server
 * error) doubles it, at most 5 minutes. A success resets it. This keeps the radio mostly idle without network.
 */
object WorkoutPoll {
    const val BASE_MILLIS = 45_000L
    const val MAX_MILLIS = 300_000L

    fun delayMillis(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return BASE_MILLIS
        var delay = BASE_MILLIS
        repeat(consecutiveFailures.coerceAtMost(10)) { delay = (delay * 2).coerceAtMost(MAX_MILLIS) }
        return delay
    }
}
