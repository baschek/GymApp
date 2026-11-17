package de.baschek.gymapp.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.data.Profile
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

/** One explicit consent applies to personal workouts completed after activation. */
class HealthExportConsent(context: Context) {
    private val prefs = context.getSharedPreferences("health_export_consent", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("automatic_enabled", false)
    fun enabledSince(): Long = prefs.getLong("automatic_since", Long.MAX_VALUE)
    fun setEnabled(enabled: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        val edit = prefs.edit().putBoolean("automatic_enabled", enabled)
        if (enabled && !this.enabled()) edit.putLong("automatic_since", nowMillis - nowMillis % 1000)
        edit.apply()
    }
    suspend fun revoke() {
        // Reject new attempts immediately; wait for any already-running write to finish.
        prefs.edit().putBoolean("automatic_enabled", false).apply()
        healthStoreMutex.withLock { }
    }
    fun allows(session: Session): Boolean {
        val completed = session.completedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return enabled() && session.status == SessionStatus.COMPLETED && completed != null && completed >= enabledSince()
    }
}

/** Stores only a successful version marker, never health measurements. */
internal class HealthExportReceipts(context: Context) {
    private val prefs = context.getSharedPreferences("health_export_receipts", Context.MODE_PRIVATE)
    fun isCurrent(id: String, recording: HeartRecording): Boolean = prefs.getString(key(id), null) == signature(recording)
    fun record(id: String, recording: HeartRecording) { prefs.edit().putString(key(id), signature(recording)).apply() }
    private fun signature(recording: HeartRecording) = "${recording.version}:${recording.startMillis}:${recording.endMillis}"
    private fun key(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal suspend fun recordingForCompleted(context: Context, id: String, session: Session): HeartRecording? {
    if (session.status != SessionStatus.COMPLETED) return null
    val store = PrivateHeartStore(context)
    store.read(RecordingProfile.PERSONAL, id)?.let { return it }
    val start = runCatching { Instant.parse(session.startedAt).toEpochMilli() }.getOrNull() ?: return null
    val end = session.completedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
    if (start < 0 || end <= start || end - start > 86_400_000L) return null
    return store.ensureEmpty(RecordingProfile.PERSONAL, id, start, end)
}

class HealthExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("session") ?: return Result.failure()
        val app = applicationContext as? GymApplication ?: return Result.success()
        val repository = app.container.repository
        if (repository.currentProfile() != Profile.PERSONAL) return Result.success()
        val session = repository.getSession(id) ?: return Result.success()
        if (!HealthExportConsent(applicationContext).allows(session)) return Result.success()
        val recording = recordingForCompleted(applicationContext, id, session) ?: return Result.success()
        val exporter = HealthConnectExporter(applicationContext)
        // Health Connect may deny reads in a worker even when foreground reads are allowed.
        // Leave the work pending for foreground reconciliation rather than claiming success.
        if (!runCatching { exporter.backgroundReadGranted() }.getOrDefault(false)) {
            publishHealthStatus(applicationContext, id, recording.version, "PENDING")
            return Result.success()
        }
        if (!HealthExportConsent(applicationContext).allows(session)) return Result.success()
        val result = exporter.export(id, RecordingProfile.PERSONAL, recording.version, true, automatic = true)
        if (result == HealthExportResult.Exported) HealthExportReceipts(applicationContext).record(id, recording)
        publishHealthStatus(applicationContext, id, recording.version, result.watchStatus())
        return when (result) {
            HealthExportResult.Exported, HealthExportResult.LocalOnly, HealthExportResult.ConsentRequired,
            HealthExportResult.UnsupportedHistory, is HealthExportResult.PermissionRequired -> Result.success()
            HealthExportResult.RecordingChanged, HealthExportResult.Failed, is HealthExportResult.NotAvailable ->
                if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
    companion object {
        private const val TAG = "automatic-health-export"
        fun enqueue(context: Context, id: String) {
            if (!HealthExportConsent(context).enabled()) return
            val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val request = OneTimeWorkRequestBuilder<HealthExportWorker>().addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(workDataOf("session" to id)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("health-export-$name", ExistingWorkPolicy.REPLACE, request)
        }
        fun cancelAll(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }
    }
}
