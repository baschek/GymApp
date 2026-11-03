package de.baschek.gymapp.telemetry

import de.baschek.gymapp.data.GymRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Tiny local UX telemetry: counts how often an element on a screen is used per
 * day. Counters are kept in Room and uploaded in batches during sync. No
 * content, no identifiers.
 *
 *     Telemetry.track("workout", "log_set")
 */
object Telemetry {
    @Volatile private var repository: GymRepository? = null
    @Volatile private var scope: CoroutineScope? = null

    fun install(repository: GymRepository, scope: CoroutineScope) {
        this.repository = repository
        this.scope = scope
    }

    fun track(screen: String, element: String) {
        val repo = repository ?: return
        val s = scope ?: return
        val day = LocalDate.now().toString()
        s.launch(Dispatchers.IO) { runCatching { repo.trackTelemetry(day, screen, element) } }
    }
}
