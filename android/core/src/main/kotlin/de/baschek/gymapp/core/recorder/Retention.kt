package de.baschek.gymapp.core.recorder

/** Rolling limits of the stored recordings of one device. */
class RetentionLimits(val maxSessions: Int, val maxAgeMs: Long, val maxBytes: Long)

/** Per-device recorder tuning: screenshot spacing, per-session image budget and retention. */
class RecorderConfig(val shotIntervalMs: Long, val maxShotsPerSession: Int, val retention: RetentionLimits) {
    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        val PHONE = RecorderConfig(2_000, 300, RetentionLimits(20, 14 * DAY_MS, 100L * 1024 * 1024))
        val WATCH = RecorderConfig(5_000, 60, RetentionLimits(20, 14 * DAY_MS, 20L * 1024 * 1024))
    }
}

class StoredSession(val name: String, val lastActivity: Long, val bytes: Long)

object Retention {
    /**
     * Names to delete: first everything older than the age limit, then the oldest ones until count and size fit.
     * The current session is never returned, even when it alone exceeds a limit.
     */
    fun expired(sessions: List<StoredSession>, now: Long, limits: RetentionLimits, current: String?): List<String> {
        val oldestFirst = sessions.sortedWith(compareBy({ it.lastActivity }, { it.name }))
        val removable = oldestFirst.filter { it.name != current }
        val doomed = linkedSetOf<String>()
        for (s in removable) if (now - s.lastActivity > limits.maxAgeMs) doomed += s.name
        val kept = oldestFirst.filter { it.name !in doomed }
        var count = kept.size
        var bytes = kept.sumOf { it.bytes }
        for (s in removable) {
            if (count <= limits.maxSessions && bytes <= limits.maxBytes) break
            if (s.name in doomed) continue
            doomed += s.name
            count--
            bytes -= s.bytes
        }
        return doomed.toList()
    }
}
