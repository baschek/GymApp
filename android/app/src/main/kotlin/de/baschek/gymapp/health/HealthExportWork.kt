package de.baschek.gymapp.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.data.Profile
import java.util.concurrent.TimeUnit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.baschek.gymapp.core.health.RecordingProfile
import java.security.MessageDigest

/** Consent is per personal workout and can be revoked. No health payload enters WorkManager data. */
class HealthExportConsent(private val context: Context) {
    private val prefs = context.getSharedPreferences("health_export_consent", Context.MODE_PRIVATE)
    fun allowed(sessionId: String) = prefs.getBoolean(key(sessionId), false)
    fun set(sessionId: String, allowed: Boolean) { prefs.edit().putBoolean(key(sessionId), allowed).apply() }
    private fun key(sessionId: String) = MessageDigest.getInstance("SHA-256")
        .digest(sessionId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

class HealthExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("session") ?: return Result.failure()
        if (!HealthExportConsent(applicationContext).allowed(id)) return Result.success()
        if ((applicationContext as? GymApplication)?.container?.repository?.currentProfile() != Profile.PERSONAL) return Result.success()
        val recording = PrivateHeartStore(applicationContext).read(RecordingProfile.PERSONAL, id) ?: return Result.success()
        val result = HealthConnectExporter(applicationContext).export(id, RecordingProfile.PERSONAL, recording.version, true)
        publishHealthStatus(applicationContext, id, recording.version, result.watchStatus())
        return when (result) {
            HealthExportResult.Exported, HealthExportResult.LocalOnly, HealthExportResult.ConsentRequired,
            HealthExportResult.UnsupportedHistory, is HealthExportResult.PermissionRequired -> Result.success()
            HealthExportResult.RecordingChanged, HealthExportResult.Failed, is HealthExportResult.NotAvailable ->
                if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
    companion object {
        fun enqueue(context: Context, id: String) {
            if (!HealthExportConsent(context).allowed(id)) return
            val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val request = OneTimeWorkRequestBuilder<HealthExportWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).setInputData(workDataOf("session" to id)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("health-export-$name", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
