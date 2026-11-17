package de.baschek.gymapp.watch.runtime

import de.baschek.gymapp.core.health.RecordingProfile
import kotlinx.coroutines.flow.StateFlow

/** Runtime facts are explicit; a missing connection or permission never blocks training. */
data class WatchPulseSample(val timeMillis: Long, val bpm: Int?)

data class WatchRuntimeState(
    val connectionLabel: String = "Verbindung wird geprüft",
    val pendingCount: Int = 0,
    val bpm: Int? = null,
    val pulseSamples: List<WatchPulseSample> = emptyList(),
    val heartSessionId: String? = null,
    val heartProfile: RecordingProfile? = null,
    val averageBpm: Int? = null,
    val maxBpm: Int? = null,
    val permissionGranted: Boolean = false,
    val recordingLabel: String = "Puls nicht aufgezeichnet",
    val exportLabel: String = "Gesundheitsexport ausstehend",
    val ambient: Boolean = false,
    val phoneLeads: Boolean = false,
    val phoneHealthEnabled: Boolean? = null,
    val phoneHealthBackground: Boolean? = null,
)

interface WatchRuntime {
    val state: StateFlow<WatchRuntimeState>
    fun trackInteraction(screen: String, element: String)
    fun retrySync()
    fun resolveConflict(useLatest: Boolean)
    fun openPhone()
    fun openPhoneHealthSettings() {}
    fun startRecording(sessionId: String, profile: RecordingProfile)
    fun stopRecording()
}
