package de.baschek.gymapp.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.baschek.gymapp.GymApplication
import java.util.concurrent.TimeUnit

class SyncScheduler(private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val workManager get() = WorkManager.getInstance(context)

    /** Periodic background sync (needs a network connection). */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One-off sync, e.g. at app start or after finishing a session. */
    fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val PERIODIC = "gym-sync-periodic"
        const val ONCE = "gym-sync-once"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as GymApplication).container
        return when (container.syncManager.runSync()) {
            SyncOutcome.RETRY -> if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
