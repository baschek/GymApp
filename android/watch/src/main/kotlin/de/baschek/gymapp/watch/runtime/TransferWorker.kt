package de.baschek.gymapp.watch.runtime

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.watch.WatchApplication
import java.util.concurrent.TimeUnit

/** Retry while a durable personal outbox or health queue awaits its matching ACK. */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WatchApplication
        return try {
            val repository = app.restoreRepository()
            if (repository?.state?.value?.profile == WorkoutProfile.PERSONAL) WatchTransport.sendHello(app)
            WatchTransport.reconcile(app)
            val events = repository?.state?.value?.takeIf { it.profile == WorkoutProfile.PERSONAL }?.outbox.orEmpty()
            WatchTransport.sendPending(app, events)
            HeartTransfer.retry(app)
            val pending = (repository?.state?.value?.takeIf { it.profile == WorkoutProfile.PERSONAL }?.outbox?.size ?: 0) + HeartQueue(app).pending().size
            if (pending > 0 && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }
    companion object {
        // Outboxes are never dropped when automatic retry is exhausted; opening the app or
        // choosing retry schedules another bounded attempt sequence.
        private const val MAX_ATTEMPTS = 96
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<TransferWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniqueWork("watch-transfer", ExistingWorkPolicy.KEEP, request)
        }
    }
}
