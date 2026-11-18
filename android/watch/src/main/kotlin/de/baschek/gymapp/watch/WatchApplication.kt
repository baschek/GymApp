package de.baschek.gymapp.watch

import de.baschek.gymapp.watch.recorder.NavRecorder
import android.app.Application
import android.util.AtomicFile
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.wear.backend.workout.WatchWorkoutRepository
import de.baschek.gymapp.watch.runtime.RestAlarm
import de.baschek.gymapp.watch.runtime.WatchTransport
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The process has one profile-scoped repository authority shared by UI and data listeners. */
class WatchApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableRepository = MutableStateFlow<WatchWorkoutRepository?>(null)
    val repository = mutableRepository.asStateFlow()
    private var observing: Job? = null
    private var restoreJob: Job? = null
    val deviceId: String by lazy { readDeviceId() }
    private val profileFile get() = File(noBackupFilesDir, "watch-selected-profile")

    override fun onCreate() {
        super.onCreate()
        NavRecorder.install(this)
        de.baschek.gymapp.watch.update.WatchUpdater.initialize(applicationContext)
        restoreJob = scope.launch {
            restoreRepository()
            runCatching { WatchTransport.reconcile(this@WatchApplication) }
            if (de.baschek.gymapp.watch.runtime.HeartQueue(this@WatchApplication).pending().isNotEmpty())
                de.baschek.gymapp.watch.runtime.TransferWorker.schedule(this@WatchApplication)
        }
    }

    suspend fun restoreRepository(): WatchWorkoutRepository? {
        repository.value?.let { return it }
        val profile = runCatching { WorkoutProfile.valueOf(profileFile.readText().trim()) }.getOrNull() ?: return null
        return selectProfile(profile)
    }

    suspend fun selectProfile(profile: WorkoutProfile): WatchWorkoutRepository = mutex.withLock {
        mutableRepository.value?.takeIf { it.state.value.profile == profile }?.let { return@withLock it }
        val repo = WatchWorkoutRepository.open(this, profile, deviceId)
        val file = AtomicFile(profileFile)
        val out = file.startWrite()
        try { out.write(profile.name.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (t: Throwable) { file.failWrite(out); throw t }
        observing?.cancelAndJoin()
        mutableRepository.value = repo
        if (profile == WorkoutProfile.PERSONAL) scope.launch { runCatching { WatchTransport.sendHello(this@WatchApplication) } }
        observing = scope.launch {
            repo.state.collect { state ->
                NavRecorder.workoutChanged(state.sessionId.takeIf { state.session?.status == de.baschek.gymapp.core.model.SessionStatus.ACTIVE })
                val sessionId = state.sessionId
                if (sessionId != null && (de.baschek.gymapp.watch.runtime.RuntimeMonitor.state.value.heartSessionId != sessionId ||
                    de.baschek.gymapp.watch.runtime.RuntimeMonitor.state.value.heartProfile?.name != state.profile.name)) {
                    val summary = runCatching { de.baschek.gymapp.watch.runtime.HeartSummaryStore(this@WatchApplication).read(de.baschek.gymapp.core.health.RecordingProfile.valueOf(state.profile.name), sessionId) }.getOrNull()
                    de.baschek.gymapp.watch.runtime.RuntimeMonitor.state.update { it.copy(
                        heartSessionId = sessionId, heartProfile = de.baschek.gymapp.core.health.RecordingProfile.valueOf(state.profile.name), averageBpm = summary?.averageBpm, maxBpm = summary?.maxBpm, bpm = null, pulseSamples = emptyList(),
                        exportLabel = if (state.profile == WorkoutProfile.TEST) "Testprofil: nur lokal" else de.baschek.gymapp.watch.runtime.exportLabel(summary?.takeIf { s -> s.exportVersion == 1L }?.exportState)) }
                }
                RestAlarm.update(this@WatchApplication, state, deviceId)
                de.baschek.gymapp.watch.runtime.WorkoutNotification.update(this@WatchApplication, state)
                runCatching { androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(this@WatchApplication,
                    android.content.ComponentName(this@WatchApplication, de.baschek.gymapp.watch.runtime.WorkoutComplication::class.java)).requestUpdateAll() }
                de.baschek.gymapp.watch.runtime.RuntimeMonitor.state.update { it.copy(
                    phoneLeads = state.lastDeviceId != null && state.lastDeviceId != deviceId,
                    pendingCount = state.pendingEvents + de.baschek.gymapp.watch.runtime.HeartQueue(this@WatchApplication).pending().size) }
                if (state.profile == WorkoutProfile.PERSONAL && state.outbox.isNotEmpty()) de.baschek.gymapp.watch.runtime.TransferWorker.schedule(this@WatchApplication)
                if (state.profile == WorkoutProfile.PERSONAL) runCatching { WatchTransport.sendPending(this@WatchApplication, state.outbox) }
                    .onFailure { de.baschek.gymapp.watch.runtime.RuntimeMonitor.state.update { previous -> previous.copy(connectionLabel = "Sync-Fehler", pendingCount = state.pendingEvents) } }
            }
        }
        repo
    }

    /** Remote switch to test removes personal data from the active watch view. */
    suspend fun invalidatePersonal() {
        restoreJob?.cancelAndJoin()
        mutex.withLock {
        val stored = runCatching { WorkoutProfile.valueOf(profileFile.readText().trim()) }.getOrNull()
        if (mutableRepository.value?.state?.value?.profile == WorkoutProfile.PERSONAL || stored == WorkoutProfile.PERSONAL) {
            observing?.cancelAndJoin()
            mutableRepository.value = null
            profileFile.delete()
            RestAlarm.cancel(this)
        }
        }
    }

    private fun readDeviceId(): String = synchronized(this) {
        val file = File(noBackupFilesDir, "watch-device-id")
        if (file.exists()) return@synchronized UUID.fromString(file.readText().trim()).toString()
        val id = UUID.randomUUID().toString()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(id.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
        catch (t: Throwable) { atomic.failWrite(out); throw t }
        id
    }
}
