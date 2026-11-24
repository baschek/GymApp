package de.baschek.gymapp.wear

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.health.PrivateHeartStore
import de.baschek.gymapp.health.HealthExportWorker
import de.baschek.gymapp.health.HealthExportConsent
import de.baschek.gymapp.health.publishHealthStatus
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Receives durable watch recordings. An ACK is published only after private persistence. */
class HeartDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            try {
                val item = event.dataItem
                val localNode = Tasks.await(Wearable.getNodeClient(this).localNode, 10, TimeUnit.SECONDS)
                if (item.uri.host == localNode.id) continue
                val path = item.uri.path.orEmpty().split('/')
                if (path.size != 6 || path[1] != "gymapp" || path[2] != "heart" || path[3] != "v1") continue
                val hash = path[4]
                val version = path[5].toLongOrNull() ?: continue
                if (!hash.matches(Regex("[0-9a-f]{64}")) || version < 0) continue
                val map = DataMapItem.fromDataItem(item).dataMap
                if (map.getString("id") != hash || map.getLong("version", -1) != version) continue
                val asset = map.getAsset("payload") ?: continue
                val response = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset), 30, TimeUnit.SECONDS)
                val bytes = response.inputStream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(out.size() + count <= MAX_BYTES)
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                } ?: continue
                val recording = Json.decodeFromString<HeartRecording>(bytes.toString(Charsets.UTF_8))
                if (recording.profile != RecordingProfile.PERSONAL || recording.version != version || hash(recording.sessionId) != hash) continue
                val accepted = runBlocking {
                    if ((application as GymApplication).container.repository.currentProfile() != Profile.PERSONAL) return@runBlocking null
                    val store = PrivateHeartStore(this@HeartDataListener)
                    val existing = store.read(recording.profile, recording.sessionId)
                    if (existing != null && existing.version == recording.version && existing != recording)
                        return@runBlocking null
                    val changed = existing == null || existing.version < recording.version
                    if (changed) store.save(recording)
                    changed
                } ?: continue
                if (accepted) {
                    val allowed = HealthExportConsent(this).enabled()
                    runBlocking { publishHealthStatus(this@HeartDataListener, recording.sessionId, recording.version, if (allowed) "PENDING" else "NOT_ENABLED") }
                    if (allowed) {
                        HealthExportWorker.enqueue(this, recording.sessionId)
                        val container = (application as GymApplication).container
                        container.health.onRecordingChanged(container.appScope)
                    }
                    // A late or corrected recording goes to TESS with the next sync run (own switch, personal profile only).
                    if (de.baschek.gymapp.health.TessHeartConsent(this).enabled())
                        (application as GymApplication).container.scheduler.requestSync()
                }
                val ack = PutDataMapRequest.create("/gymapp/heart-ack/v1/$hash/$version").apply {
                    dataMap.putString("attempt", java.util.UUID.randomUUID().toString())
                    dataMap.putString("id", hash)
                    dataMap.putLong("version", version)
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(this).putDataItem(ack), 30, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Remain unacknowledged: the watch retains this item for a later retry.
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 12 * 1024 * 1024
        private fun hash(id: String): String = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
