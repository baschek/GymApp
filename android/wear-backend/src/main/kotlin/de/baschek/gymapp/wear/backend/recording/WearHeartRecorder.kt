package de.baschek.gymapp.wear.backend.recording

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.*
import com.google.common.util.concurrent.ListenableFuture
import de.baschek.gymapp.core.health.RecordingProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_SAMPLES = 100_000
private const val MAX_DURATION_MS = 24 * 60 * 60 * 1000L
private val directExecutor = Executor { it.run() }

/**
 * Construct this class on a background dispatcher because it reads durable state immediately.
 * A hostable Health Services recorder. The host must obtain the runtime heart-rate permission,
 * run and maintain an Android health foreground service with its notification and required
 * permissions, and call [start] only in response to a user action. No service or UI is supplied.
 * Keep one instance alive in that service; after process death, explicitly call [reconnect].
 * Call [detach] when the host releases it, to unregister and shut down its callback executor.
 * Health Services may end a session after five minutes without a listener. Reconnection never
 * starts a sensor. The other-app check cannot eliminate a race with another app starting at the
 * same instant: Health Services itself owns arbitration.
 *
 * End timestamps represent callback receipt on a monotonic clock anchored at start, not an exact
 * sensor stop time. Gaps include times when availability was unknown during reconnection.
 * The file retains one bounded recording; copy/export a completed recording before starting a new
 * one. A failure to read or write it is surfaced, never silently replaced. No network is used.
 */
class WearHeartRecorder(
    context: Context,
    private val client: ExerciseClient = HealthServices.getClient(context.applicationContext).exerciseClient,
    private val store: RecordingStore = RecordingStore(context),
) {
    private val context = context.applicationContext
    private val operation = Mutex()
    private val lock = Any()
    private val mutableRecording = MutableStateFlow(store.read())
    val recording: StateFlow<LocalRecording?> = mutableRecording.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    @Volatile private var registered = false
    private var callbackPool: ThreadPoolExecutor? = null
    private val callbackOverflow = AtomicBoolean(false)

    private fun save(value: LocalRecording) {
        store.write(value)
        mutableRecording.value = value
    }

    private fun clock(recording: LocalRecording): Long =
        (recording.startedAtMillis + SystemClock.elapsedRealtime() - recording.bootElapsedAtStartMillis)
            .coerceIn(recording.startedAtMillis, recording.startedAtMillis + MAX_DURATION_MS)

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) {
            synchronized(lock) { registered = false; mutableError.value = "Listener registration failed: ${throwable.message}" }
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (dataType != DataType.HEART_RATE_BPM) return
            synchronized(lock) {
                val old = mutableRecording.value ?: return
                if (old.status !in setOf(RecordingStatus.STARTING, RecordingStatus.ACTIVE, RecordingStatus.STOPPING)) return
                try {
                    val now = clock(old)
                    if (availability == DataTypeAvailability.AVAILABLE) {
                        save(closeGap(old, now).copy(lastUpdateAtMillis = now))
                    } else if (old.openGapAtMillis == null) {
                        save(old.copy(openGapAtMillis = now, lastUpdateAtMillis = now))
                    }
                } catch (t: Exception) { mutableError.value = "Availability could not be saved: ${t.message}" }
            }
        }
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            synchronized(lock) {
                val old = mutableRecording.value ?: return
                if (old.status !in setOf(RecordingStatus.STARTING, RecordingStatus.ACTIVE, RecordingStatus.STOPPING)) return
                try {
                    val bootMillis = old.startedAtMillis - old.bootElapsedAtStartMillis
                    val received = update.latestMetrics.getData(DataType.HEART_RATE_BPM).mapNotNull { point ->
                        val time = bootMillis + point.timeDurationFromBoot.toMillis()
                        val bpm = point.value
                        if (!bpm.isFinite() || bpm < 1 || bpm > 300 || time < old.startedAtMillis || time > old.startedAtMillis + MAX_DURATION_MS) null
                        else {
                            val status = (point.accuracy as? HeartRateAccuracy)?.sensorStatus
                            val reliable = status == HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM || status == HeartRateAccuracy.SensorStatus.ACCURACY_HIGH
                            LocalSample(time, bpm, reliable)
                        }
                    }
                    val allSamples = mergeSamples(old.samples, received)
                    val samples = allSamples.take(MAX_SAMPLES)
                    val state = update.exerciseStateInfo.state
                    val ended = state.isEnded
                    val reason = update.exerciseStateInfo.endReason
                    val newStatus = when {
                        ended && isUserEnded(reason) -> RecordingStatus.ENDED
                        ended -> RecordingStatus.INTERRUPTED
                        old.status == RecordingStatus.STOPPING -> RecordingStatus.STOPPING
                        else -> RecordingStatus.ACTIVE
                    }
                    val now = clock(old)
                    val updated = old.copy(samples = samples, status = newStatus, lastUpdateAtMillis = now,
                        sampleLimitReached = old.sampleLimitReached || allSamples.size > MAX_SAMPLES,
                        durationLimitReached = old.durationLimitReached || SystemClock.elapsedRealtime() - old.bootElapsedAtStartMillis > MAX_DURATION_MS,
                        endedAtMillis = if (ended) maxOf(now, samples.lastOrNull()?.timeMillis ?: now) else null,
                        error = if (ended && !isUserEnded(reason)) "Health Services ended exercise (reason $reason)" else old.error)
                    save(if (ended) closeGap(updated, now) else updated)
                } catch (t: Exception) { mutableError.value = "Recording could not be saved: ${t.message}" }
            }
        }
    }

    private fun register() {
        if (registered) return
        val existing = callbackPool
        check(existing == null || !existing.isShutdown) { "Callback executor is still shutting down" }
        val pool = existing ?: ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1024), { task -> Thread(task, "wear-health-callback").apply { isDaemon = true } })
            .also { callbackPool = it }
        val serialExecutor = Executor { task ->
            try {
                pool.execute {
                    if (callbackOverflow.getAndSet(false)) synchronized(lock) {
                        try {
                            mutableRecording.value?.let { save(it.copy(sampleLimitReached = true)) }
                        } catch (e: Exception) { mutableError.value = "Dropped callback could not be recorded: ${e.message}" }
                    }
                    task.run()
                }
            } catch (_: RejectedExecutionException) {
                callbackOverflow.set(true)
                mutableError.value = "Health callback queue overflow; recording is incomplete"
            }
        }
        registered = true
        try { client.setUpdateCallback(serialExecutor, callback) }
        catch (e: Exception) { registered = false; throw e }
    }

    /** Unregisters without ending the exercise or deleting local data. The host must reconnect
     * promptly: Health Services can end an exercise after five minutes without a listener. */
    suspend fun detach() = withContext(Dispatchers.IO) { operation.withLock { detachLocked() } }

    private suspend fun detachLocked() {
        if (registered) {
            client.clearUpdateCallbackAsync(callback).await()
            registered = false
        }
        val pool = callbackPool ?: return
        pool.shutdown()
        val done = withContext(Dispatchers.IO) { pool.awaitTermination(10, TimeUnit.SECONDS) }
        check(done) { "Callback executor did not stop" }
        callbackPool = null
    }

    /** Checks support and ownership again immediately before starting; throws on denial or conflict. */
    suspend fun start(sessionId: String, profile: RecordingProfile) = withContext(Dispatchers.IO) { operation.withLock {
        require(sessionId.isNotBlank() && sessionId.length <= 256)
        check(hasPermission()) { "Heart-rate permission is missing" }
        check(mutableRecording.value == null) { "Export and clear the previous recording first" }
        val capabilities = client.getCapabilitiesAsync().await()
        check(ExerciseType.STRENGTH_TRAINING in capabilities.supportedExerciseTypes &&
            DataType.HEART_RATE_BPM in capabilities.getExerciseTypeCapabilities(ExerciseType.STRENGTH_TRAINING).supportedDataTypes) {
            "Strength training with heart rate is not supported"
        }
        check(ownership(client.getCurrentExerciseInfoAsync().await().exerciseTrackedStatus) == ExerciseOwnership.NONE) {
            "Another exercise is active or its status is unknown"
        }
        synchronized(lock) { save(LocalRecording(sessionId, System.currentTimeMillis(), RecordingStatus.STARTING, SystemClock.elapsedRealtime(), profile)) }
        register()
        try {
            client.startExerciseAsync(ExerciseConfig(ExerciseType.STRENGTH_TRAINING, setOf(DataType.HEART_RATE_BPM), false, false)).await()
            synchronized(lock) {
                mutableRecording.value?.takeIf { it.status == RecordingStatus.STARTING }?.let { save(it.copy(status = RecordingStatus.ACTIVE)) }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            synchronized(lock) { mutableRecording.value?.let { save(it.copy(error = "Start failed; reconnect to reconcile: ${e.message}")) } }
            throw e
        }
    } }

    /**
     * Call before [start] (watch design spec J): reconciles a non-terminal recording left behind by an earlier workout
     * or process (the cause of "Export and clear the previous recording first"). Returns true when that recording
     * belongs to [sessionId]/[profile] and our exercise is still running: it is reconnected and [start] must not be
     * called. Otherwise a stale recording is closed as INTERRUPTED (our exercise is ended first) so the host can archive
     * it with [clearCompleted] and start anew. Without a recording, or with a terminal one, nothing changes (false).
     */
    suspend fun reconcileBeforeStart(sessionId: String, profile: RecordingProfile): Boolean = withContext(Dispatchers.IO) { operation.withLock {
        val current = mutableRecording.value ?: return@withLock false
        if (current.status !in setOf(RecordingStatus.STARTING, RecordingStatus.ACTIVE, RecordingStatus.STOPPING)) return@withLock false
        val info = client.getCurrentExerciseInfoAsync().await()
        val ours = ownership(info.exerciseTrackedStatus) == ExerciseOwnership.OURS && info.exerciseType == ExerciseType.STRENGTH_TRAINING
        if (ours && current.sessionId == sessionId && current.profile == profile && current.status != RecordingStatus.STOPPING) {
            synchronized(lock) {
                val now = clock(current)
                val since = current.lastUpdateAtMillis ?: current.startedAtMillis
                val withGap = if (current.openGapAtMillis == null && now > since) current.copy(openGapAtMillis = since) else current
                save(closeGap(withGap, now))
            }
            register()
            return@withLock true
        }
        if (ours) {
            try { client.endExerciseAsync().await() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableError.value = "End request failed: ${e.message}" }
        }
        synchronized(lock) {
            save(current.copy(status = RecordingStatus.INTERRUPTED,
                endedAtMillis = maxOf(current.startedAtMillis + 1, System.currentTimeMillis()),
                error = "Orphaned recording closed at start"))
        }
        false
    } }

    /** Re-register only for our already-running exercise; does not create a new exercise. */
    suspend fun reconnect() = withContext(Dispatchers.IO) { operation.withLock {
        val current = mutableRecording.value ?: return@withLock
        if (current.status !in setOf(RecordingStatus.STARTING, RecordingStatus.ACTIVE, RecordingStatus.STOPPING)) return@withLock
        val info = client.getCurrentExerciseInfoAsync().await()
        if (ownership(info.exerciseTrackedStatus) == ExerciseOwnership.OURS && info.exerciseType == ExerciseType.STRENGTH_TRAINING) {
            synchronized(lock) {
                val now = clock(current)
                val since = current.lastUpdateAtMillis ?: current.startedAtMillis
                val withGap = if (current.openGapAtMillis == null && now > since) current.copy(openGapAtMillis = since) else current
                save(closeGap(withGap, now))
            }
            register()
        } else if (ownership(info.exerciseTrackedStatus) in setOf(ExerciseOwnership.NONE, ExerciseOwnership.OTHER)) {
            synchronized(lock) { save(current.copy(status = RecordingStatus.INTERRUPTED, endedAtMillis = maxOf(current.startedAtMillis, System.currentTimeMillis()), error = "Exercise is no longer owned by this app")) }
        } else {
            error("Exercise ownership could not be determined")
        }
    } }

    /** Requests an end; the final flushed update changes the state from STOPPING to ENDED. */
    suspend fun stop() = withContext(Dispatchers.IO) { operation.withLock {
        synchronized(lock) {
            val current = checkNotNull(mutableRecording.value)
            check(current.status in setOf(RecordingStatus.ACTIVE, RecordingStatus.STARTING, RecordingStatus.STOPPING))
            save(current.copy(status = RecordingStatus.STOPPING))
        }
        try { client.endExerciseAsync().await() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableError.value = "End request failed: ${e.message}"; throw e }
    } }

    /** Clears only a terminal recording, after the host has safely consumed it. */
    suspend fun clearCompleted() = withContext(Dispatchers.IO) { operation.withLock {
        synchronized(lock) {
            val current = mutableRecording.value ?: return@withLock
            check(current.status in setOf(RecordingStatus.ENDED, RecordingStatus.INTERRUPTED, RecordingStatus.ERROR))
        }
        detachLocked()
        synchronized(lock) {
            store.clear()
            mutableRecording.value = null
        }
    } }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else "android.permission.BODY_SENSORS"
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try { continuation.resume(get()) }
        catch (e: Exception) { continuation.resumeWithException(e.cause ?: e) }
    }, directExecutor)
    // Cancellation does not cancel Health Services' remote operation; reconnect reconciles it.
}

/** Keeps the first reading at a duplicate timestamp and restores chronological order. */
internal fun mergeSamples(existing: List<LocalSample>, received: List<LocalSample>): List<LocalSample> =
    (existing + received).distinctBy { it.timeMillis }.sortedBy { it.timeMillis }

private fun closeGap(recording: LocalRecording, end: Long): LocalRecording {
    val start = recording.openGapAtMillis ?: return recording
    if (end <= start) return recording.copy(openGapAtMillis = null)
    check(recording.gaps.size < 10_000) { "Gap limit reached" }
    val last = recording.gaps.lastOrNull()
    val gaps = if (last != null && start <= last.endMillis) recording.gaps.dropLast(1) + LocalGap(last.startMillis, maxOf(last.endMillis, end))
        else recording.gaps + LocalGap(start, end)
    return recording.copy(gaps = gaps, openGapAtMillis = null)
}

// AndroidX 1.0/1.1 marks these IntDef containers restricted even though the official Health
// Services guide instructs applications to compare these exact statuses and end reasons:
// https://developer.android.com/health-and-fitness/guides/health-services/active-data
// Keep the workaround scoped to the constants; unknown statuses always fail closed.
private enum class ExerciseOwnership { NONE, OURS, OTHER, UNKNOWN }

@SuppressLint("RestrictedApi")
private fun ownership(status: Int): ExerciseOwnership = when (status) {
    ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS -> ExerciseOwnership.NONE
    ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> ExerciseOwnership.OURS
    ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> ExerciseOwnership.OTHER
    else -> ExerciseOwnership.UNKNOWN
}

@SuppressLint("RestrictedApi")
private fun isUserEnded(reason: Int): Boolean = reason == ExerciseEndReason.USER_END
