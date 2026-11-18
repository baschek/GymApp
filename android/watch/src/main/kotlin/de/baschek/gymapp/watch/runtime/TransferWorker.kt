package de.baschek.gymapp.watch.runtime

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.baschek.gymapp.watch.WatchApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Retry while a durable personal outbox or health queue awaits its matching ACK. */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WatchApplication
        return try {
            var failed = false
            suspend fun attempt(transfer: suspend () -> Unit) {
                try { transfer() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    failed = true
                    Log.w("TransferWorker", "Wearable transfer will be retried (${e.javaClass.simpleName})")
                }
            }
            app.restoreRepository()
            attempt { WatchTransport.sendPending(app) }
            attempt { WatchTransport.sendHello(app) }
            attempt { WatchTransport.reconcile(app) }
            attempt { HeartTransfer.retry(app) }
            val pending = (app.repository.value?.state?.value?.pendingEvents ?: 0) + HeartQueue(app).pending().size
            if ((failed || pending > 0) && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TransferWorker", "Could not read wearable transfer queue (${e.javaClass.simpleName})")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }
    companion object {
        // Outboxes are never dropped when automatic retry is exhausted; opening the app or
        // choosing retry schedules another bounded attempt sequence.
        private const val MAX_ATTEMPTS = 96
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<TransferWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("watch-transfer", ExistingWorkPolicy.KEEP, request)
        }
    }
}
