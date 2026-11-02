package de.baschek.gymapp.data

import kotlinx.serialization.json.JsonObject

/**
 * Data profiles. Only [PERSONAL] is ever synchronized; [TEST] is local-only:
 * it never gets outbox entries and is never touched by pulls or snapshots.
 */
object Profile {
    const val PERSONAL = "personal"
    const val TEST = "test"
}

/** A decoded document together with its raw JSON (needed to write unknown fields back). */
data class Stored<T>(val id: String, val rev: Long?, val value: T, val raw: JsonObject)

object SettingKeys {
    const val PROFILE = "profile"
    const val SYNC_CURSOR = "sync_cursor"
    const val LAST_SYNC_AT = "last_sync_at"
    const val SYNC_NOTICES = "sync_notices"
    const val AUTH_ERROR = "auth_error"
    const val TELEMETRY_BATCH = "telemetry_batch"
    const val CURRENT_GYM = "current_gym_id"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val TIMER_STATE = "timer_state"
}
