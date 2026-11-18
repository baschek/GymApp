package de.baschek.gymapp.watch.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.wear.events.ConflictChoice
import de.baschek.gymapp.watch.WatchApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class AndroidWatchRuntime(private val app: WatchApplication, private val requestPermission: (String) -> Unit) : WatchRuntime {
    override val state = RuntimeMonitor.state
    private var pending: Pair<String, RecordingProfile>? = null
    private val permission: String get() = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else "android.permission.BODY_SENSORS"

    fun refreshPermission() {
        RuntimeMonitor.state.update { it.copy(permissionGranted = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) }
    }
    fun onPermissionResult(granted: Boolean) {
        RuntimeMonitor.state.update { it.copy(permissionGranted = granted, recordingLabel = if (granted) it.recordingLabel else "Pulserlaubnis fehlt") }
        val request = pending
        pending = null
        if (granted && request != null) startRecording(request.first, request.second)
    }
    fun setAmbient(value: Boolean) { RuntimeMonitor.state.update { it.copy(ambient = value) } }

    override fun startRecording(sessionId: String, profile: RecordingProfile) {
        val state = app.repository.value?.state?.value ?: return
        if (state.sessionId != sessionId || state.session?.status != "active" || state.profile.name != profile.name) return
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            pending = sessionId to profile
            requestPermission(permission)
            return
        }
        val intent = Intent(app, HeartRecordingService::class.java).setAction(HeartRecordingService.ACTION_START)
            .putExtra("session", sessionId).putExtra("profile", profile.name)
        try { ContextCompat.startForegroundService(app, intent) }
        catch (e: Exception) { RuntimeMonitor.state.update { it.copy(recordingLabel = "Pulsaufzeichnung nicht verfügbar", heartError = "foreground service could not start: ${e.javaClass.simpleName}") } }
    }
    override fun stopRecording() {
        if (state.value.recordingLabel != "Aufzeichnung läuft" && state.value.recordingLabel != "Aufzeichnung wird beendet") return
        val intent = Intent(app, HeartRecordingService::class.java).setAction(HeartRecordingService.ACTION_STOP)
        try { ContextCompat.startForegroundService(app, intent) }
        catch (_: Exception) { RuntimeMonitor.state.update { it.copy(recordingLabel = "Aufzeichnung konnte nicht beendet werden") } }
    }
    override fun trackInteraction(screen: String, element: String) {
        if (!screen.matches(Regex("[a-z_]{1,32}")) || !element.matches(Regex("[a-z_]{1,48}"))) return
        app.scope.launch {
            val prefs = app.getSharedPreferences("watch-telemetry", Context.MODE_PRIVATE)
            val today = java.time.LocalDate.now()
            val cutoff = today.minusDays(31).toString()
            val key = "$today:$screen:$element"
            val edit = prefs.edit()
            prefs.all.keys.filter { it.substringBefore(':') < cutoff }.forEach { edit.remove(it) }
            edit.putInt(key, (prefs.getInt(key, 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).apply()
        }
    }
    override fun retrySync() {
        TransferWorker.schedule(app)
        app.scope.launch {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes)
                RuntimeMonitor.state.update { it.copy(connectionLabel = if (nodes.isEmpty()) "Nur Uhr" else "Synchronisiere …") }
                WatchTransport.sendHello(app)
                WatchTransport.sendPending(app)
                HeartTransfer.retry(app)
                RuntimeMonitor.state.update { it.copy(connectionLabel = if (nodes.isEmpty()) "Nur Uhr" else "Handy verbunden", pendingCount = (app.repository.value?.state?.value?.pendingEvents ?: 0) + HeartQueue(app).pending().size) }
            } catch (_: Exception) { RuntimeMonitor.state.update { it.copy(connectionLabel = "Sync-Fehler") } }
        }
    }
    override fun resolveConflict(useLatest: Boolean) {
        app.scope.launch {
            try { app.repository.value?.resolve(if (useLatest) ConflictChoice.NEWEST else ConflictChoice.MINE) }
            catch (_: Exception) { RuntimeMonitor.state.update { it.copy(connectionLabel = "Konflikt") } }
        }
    }
    override fun openPhoneHealthSettings() {
        app.scope.launch {
            try {
                val repo = app.repository.value ?: return@launch
                if (repo.state.value.profile != de.baschek.gymapp.core.wear.events.WorkoutProfile.PERSONAL) return@launch
                val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes)
                if (nodes.isEmpty()) { RuntimeMonitor.state.update { it.copy(connectionLabel = "Nur Uhr") }; return@launch }
                nodes.forEach { Tasks.await(Wearable.getMessageClient(app).sendMessage(it.id, "/gymapp/open-health/v1", byteArrayOf())) }
            } catch (_: Exception) { RuntimeMonitor.state.update { it.copy(connectionLabel = "Sync-Fehler") } }
        }
    }
    override fun openPhone() {
        app.scope.launch {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes)
                if (nodes.isEmpty()) { RuntimeMonitor.state.update { it.copy(connectionLabel = "Nur Uhr") }; return@launch }
                nodes.forEach { Tasks.await(Wearable.getMessageClient(app).sendMessage(it.id, "/gymapp/open-phone/v1", byteArrayOf())) }
            } catch (_: Exception) { RuntimeMonitor.state.update { it.copy(connectionLabel = "Sync-Fehler") } }
        }
    }
}
