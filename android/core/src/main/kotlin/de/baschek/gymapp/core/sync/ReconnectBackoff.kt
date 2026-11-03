package de.baschek.gymapp.core.sync

/** Exponential backoff for retrying after the server stopped recognising this device. */
object ReconnectBackoff {
    private const val BASE_MILLIS = 15L * 60 * 1000
    private const val MAX_MILLIS = 24L * 60 * 60 * 1000

    /** Delay before attempt number [attempts] (1 = first retry after the first failure). */
    fun delayMillis(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 20)
        return minOf(MAX_MILLIS, BASE_MILLIS shl shift)
    }
}
