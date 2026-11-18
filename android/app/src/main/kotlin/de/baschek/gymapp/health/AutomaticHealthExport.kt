package de.baschek.gymapp.health

import android.content.Context
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates completion, late watch recordings and foreground fallback after one consent. */
class AutomaticHealthExport(
    private val context: Context,
    private val repository: GymRepository,
    private val schedule: (String) -> Unit = { HealthExportWorker.enqueue(context, it) },
    private val scheduleDelete: (String) -> Unit = { HealthExportDeleteWorker.enqueue(context, it) },
) {
    private val foregroundMutex = Mutex()
    private val seen = mutableMapOf<String, String>()
    private val deleteScheduled = mutableSetOf<String>()
    @Volatile private var foreground = false

    fun start(scope: CoroutineScope) = scope.launch {
        launch {
            repository.activeProfile.collect { profile ->
                if (profile == Profile.PERSONAL && foreground) launch {
                    publishHealthSettings(context)
                    reconcileIfForeground()
                }
            }
        }
        repository.observeCompletedSessions().collect { sessions ->
            if (!HealthExportConsent(context).enabled() || repository.currentProfile() != Profile.PERSONAL) return@collect
            for (stored in sessions) {
                if (!HealthExportConsent(context).allows(stored.value)) continue
                val signature = "${stored.value.startedAt}:${stored.value.completedAt}:${stored.value.durationSeconds}"
                if (seen.put(stored.id, signature) != signature) {
                    schedule(stored.id)
                    if (foreground) scope.launch { reconcileIfForeground() }
                }
            }
            deleteScheduled.removeAll(sessions.map { it.id }.toSet())
            for (id in deletedExports(sessions.map { it.id }.toSet())) {
                if (deleteScheduled.add(id)) scheduleDelete(id)
            }
        }
    }

    /** Invoke when the phone is visible. This is the fallback when background reads are unavailable. */
    fun onBackground() { foreground = false }

    suspend fun onForeground() {
        foreground = true
        publishHealthSettings(context)
        reconcileIfForeground()
    }

    fun onRecordingChanged(scope: CoroutineScope) {
        if (foreground) scope.launch { reconcileIfForeground() }
    }

    /** Exported sessions that no longer exist (tombstone, snapshot removal or local delete). Personal profile only. */
    private suspend fun deletedExports(completedIds: Set<String>): List<String> {
        val receipts = HealthExportReceipts(context)
        completedIds.forEach { receipts.adopt(it) }
        return receipts.exportedIds().filter { it !in completedIds && repository.getSession(it) == null }
    }

    private suspend fun reconcileIfForeground() = foregroundMutex.withLock {
        if (!foreground || !HealthExportConsent(context).enabled() || repository.currentProfile() != Profile.PERSONAL) return@withLock
        val sessions = repository.observeCompletedSessions().first()
        val exporter = HealthConnectExporter(context)
        for (stored in sessions) {
            if (!foreground) break
            if (repository.currentProfile() != Profile.PERSONAL || !HealthExportConsent(context).allows(stored.value)) continue
            val recording = recordingForCompleted(context, stored.id, stored.value) ?: continue
            if (HealthExportReceipts(context).isCurrent(stored.id, recording)) continue
            if (!HealthExportConsent(context).allows(stored.value)) continue
            val result = exporter.export(stored.id, RecordingProfile.PERSONAL, recording.version, true, automatic = true, title = stored.value.planName)
            if (result == HealthExportResult.Exported) HealthExportReceipts(context).record(stored.id, recording)
            publishHealthStatus(context, stored.id, recording.version, result.watchStatus())
        }
        // Fallback when the background delete could not run: delete in the foreground.
        for (id in deletedExports(sessions.map { it.id }.toSet())) {
            if (!foreground || repository.currentProfile() != Profile.PERSONAL || !HealthExportConsent(context).enabled()) break
            exporter.deleteExported(id)
        }
    }
}
