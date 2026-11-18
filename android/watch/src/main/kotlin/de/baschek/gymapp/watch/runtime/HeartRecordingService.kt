package de.baschek.gymapp.watch.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.watch.MainActivity
import de.baschek.gymapp.wear.backend.recording.RecordingStatus
import de.baschek.gymapp.wear.backend.recording.WearHeartRecorder
import de.baschek.gymapp.wear.backend.recording.toHeartRecording
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal object RuntimeMonitor {
    val state = MutableStateFlow(WatchRuntimeState())
}

/** Owns the Health Services listener while the user-authorized exercise is being recorded. */
class HeartRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var foregroundReady = false
    private var recorder: WearHeartRecorder? = null
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Trainingsaufzeichnung", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("GymApp Training")
            .setContentText("Pulsaufzeichnung")
            .setOngoing(true).setContentIntent(pending)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, builder.build(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
            foregroundReady = true
        } catch (e: SecurityException) {
            RuntimeMonitor.state.update { it.copy(recordingLabel = "Pulserlaubnis fehlt", recordingActive = false, heartError = "Heart-rate permission is missing: ${e.message}") }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) return START_NOT_STICKY
        // An Android restart never calls start(): it only reconnects the existing exercise.
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                val engine = recorder ?: WearHeartRecorder(applicationContext).also { recorder = it }
                when (intent?.action) {
                    ACTION_START -> {
                        val session = requireNotNull(intent.getStringExtra("session"))
                        val profile = RecordingProfile.valueOf(requireNotNull(intent.getStringExtra("profile")))
                        // An old recording that never ended (process death, older workout) would block every new start:
                        // reconnect it when it is this workout's, otherwise close and archive it first (spec J).
                        val running = engine.reconcileBeforeStart(session, profile)
                        archiveTerminal(engine)
                        if (!running) {
                            RuntimeMonitor.state.update { it.copy(heartError = null) }
                            engine.start(session, profile)
                        }
                    }
                    ACTION_STOP -> { engine.reconnect(); engine.stop() }
                    else -> engine.reconnect()
                }
                engine.recording.collect { value ->
                    val label = when (value?.status) {
                        RecordingStatus.STARTING, RecordingStatus.ACTIVE -> "Aufzeichnung läuft"
                        RecordingStatus.STOPPING -> "Aufzeichnung wird beendet"
                        RecordingStatus.ENDED -> "Aufzeichnung beendet"
                        RecordingStatus.INTERRUPTED, RecordingStatus.ERROR -> "Aufzeichnung unterbrochen"
                        null -> "Puls nicht aufgezeichnet"
                    }
                    val reliable = value?.samples?.filter { it.reliable }.orEmpty()
                    val active = value?.status == RecordingStatus.STARTING || value?.status == RecordingStatus.ACTIVE
                    RuntimeMonitor.state.update { it.copy(recordingLabel = label, recordingActive = active,
                        heartError = if (value?.status in setOf(RecordingStatus.INTERRUPTED, RecordingStatus.ERROR)) value?.error ?: "Recording interrupted" else if (active) null else it.heartError,
                        bpm = value?.samples?.lastOrNull()?.bpm?.toInt(),
                        pulseSamples = value?.let(::pulseWindow) ?: it.pulseSamples,
                        heartSessionId = value?.sessionId ?: it.heartSessionId, heartProfile = value?.profile ?: it.heartProfile,
                        averageBpm = if (value == null) it.averageBpm else reliable.takeIf { it.isNotEmpty() }?.map { sample -> sample.bpm }?.average()?.toInt(),
                        maxBpm = if (value == null) it.maxBpm else reliable.maxOfOrNull { sample -> sample.bpm }?.toInt()) }
                    if (value?.status in setOf(RecordingStatus.ENDED, RecordingStatus.INTERRUPTED, RecordingStatus.ERROR)) {
                        archiveTerminal(engine)
                        engine.detach()
                        stopSelf(startId)
                    } else if (value == null) {
                        engine.detach()
                        stopSelf(startId)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "Heart recording failed: ${e.javaClass.simpleName}: ${e.message}")
                RuntimeMonitor.state.update { it.copy(recordingLabel = "Pulsaufzeichnung nicht verfügbar", recordingActive = false, heartError = e.message ?: e.javaClass.simpleName) }
                runCatching { recorder?.detach() }
                stopSelf(startId)
            }
        }
        return START_STICKY
    }
    private suspend fun archiveTerminal(engine: WearHeartRecorder) {
        val value = engine.recording.value ?: return
        if (value.status !in setOf(RecordingStatus.ENDED, RecordingStatus.INTERRUPTED, RecordingStatus.ERROR)) return
        val queue = HeartQueue(applicationContext)
        HeartSummaryStore(applicationContext).save(value)
        val pending = if (value.status == RecordingStatus.ENDED && value.profile == RecordingProfile.PERSONAL) {
            try { queue.stage(value.toHeartRecording()) }
            catch (_: IllegalArgumentException) { queue.archiveLocal(value); null }
        } else { queue.archiveLocal(value); null }
        // Staging is durable before the single-recording backend is cleared.
        engine.clearCompleted()
        if (pending != null) {
            TransferWorker.schedule(applicationContext)
            try {
                HeartTransfer.enqueue(applicationContext, pending)
                val status = HeartSummaryStore(applicationContext).read(RecordingProfile.PERSONAL, value.sessionId)
                RuntimeMonitor.state.update { it.copy(exportLabel = if (status?.exportVersion == 1L && status.exportState != null) exportLabel(status.exportState) else "Gespeichert, wartet auf Handy") }
            } catch (_: Exception) {
                RuntimeMonitor.state.update { it.copy(exportLabel = "Gesundheitsexport ausstehend") }
            }
        } else RuntimeMonitor.state.update { it.copy(exportLabel = "Nur lokal gespeichert") }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        recorder?.let { engine -> CoroutineScope(Dispatchers.IO).launch { runCatching { engine.detach() } } }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "de.baschek.gymapp.watch.START_HEART"
        const val ACTION_STOP = "de.baschek.gymapp.watch.STOP_HEART"
        private const val CHANNEL = "wear_heart"
        private const val TAG = "HeartRecording"
        private const val NOTIFICATION = 481
    }
}

/** Null points break lines at unreliable readings and explicitly recorded outages. */
private fun pulseWindow(recording: de.baschek.gymapp.wear.backend.recording.LocalRecording): List<WatchPulseSample> {
    val elapsed = (android.os.SystemClock.elapsedRealtime() - recording.bootElapsedAtStartMillis).coerceAtLeast(0)
    val now = recording.startedAtMillis + elapsed
    val from = now - 5 * 60_000L
    val points = mutableListOf<WatchPulseSample>()
    recording.samples.asSequence().filter { it.timeMillis >= from }.forEach { sample ->
        points += WatchPulseSample(sample.timeMillis, if (sample.reliable) sample.bpm.toInt() else null)
        if (recording.gaps.any { sample.timeMillis > it.startMillis && sample.timeMillis < it.endMillis } ||
            recording.openGapAtMillis?.let { sample.timeMillis > it } == true) points += WatchPulseSample(sample.timeMillis, null)
    }
    recording.gaps.filter { it.endMillis >= from }.forEach { gap ->
        points += WatchPulseSample(maxOf(from, gap.startMillis), null)
        points += WatchPulseSample(gap.endMillis, null)
    }
    recording.openGapAtMillis?.let { if (it <= now) points += WatchPulseSample(maxOf(from, it), null) }
    return points.sortedBy { it.timeMillis }.takeLast(600)
}
