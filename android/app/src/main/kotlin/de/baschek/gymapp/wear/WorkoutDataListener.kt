package de.baschek.gymapp.wear

import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.wear.events.EventRejections
import de.baschek.gymapp.core.wear.events.EventResult
import de.baschek.gymapp.core.wear.events.WorkoutEvent
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** DataItem event stays on the watch until a durable Room transaction is acknowledged. */
class WorkoutDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED && event.type != DataEvent.TYPE_DELETED) continue
            try {
                val item = event.dataItem
                val localNode = Tasks.await(Wearable.getNodeClient(this).localNode, 10, TimeUnit.SECONDS)
                if (item.uri.host == localNode.id) continue
                val parts = item.uri.path.orEmpty().split('/')
                if (parts.size != 5 || parts[1] != "gymapp" || parts[2] != "event" || parts[3] != "v1") continue
                val id = parts[4]
                if (!id.matches(Regex("[0-9a-f]{64}"))) continue
                if (event.type == DataEvent.TYPE_DELETED) {
                    // The watch dropped its event after our ACK, so the ACK item is no longer needed.
                    val ackUri = Uri.Builder().scheme("wear").authority(localNode.id).path("/gymapp/event-ack/v1/$id").build()
                    Tasks.await(Wearable.getDataClient(this).deleteDataItems(ackUri), 30, TimeUnit.SECONDS)
                    continue
                }
                val map = DataMapItem.fromDataItem(item).dataMap
                if (map.getString("id") != id) continue
                val asset = map.getAsset("payload") ?: continue
                val response = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset), 30, TimeUnit.SECONDS)
                val bytes = response.inputStream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(output.size() + n <= MAX_BYTES)
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                } ?: continue
                val operation = GymJson.decodeFromString(WorkoutEvent.serializer(), bytes.toString(Charsets.UTF_8))
                if (operation.profile != WorkoutProfile.PERSONAL || hash(operation.eventId) != id) continue
                val result = runBlocking { (application as GymApplication).container.repository.importWatchEvent(operation) }
                // Transient rejections stay unacknowledged for a retry; permanent ones are dropped by the sender.
                if (result is EventResult.Rejected && !EventRejections.shouldAcknowledge(result)) continue
                if (result is EventResult.Applied) (application as GymApplication).container.scheduler.requestSync()
                if (result is EventResult.Applied && result.state.lastOperation?.eventId == operation.eventId) {
                    runBlocking {
                        val container = (application as GymApplication).container
                        if (container.repository.getSetting("watch_leader_event") == operation.eventId) {
                            container.timer.applyRemote(result.state.timer)
                            container.workout.setFocus(result.state.focusExerciseId, fromRemote = true)
                        }
                    }
                }
                val ack = PutDataMapRequest.create("/gymapp/event-ack/v1/$id").apply {
                    dataMap.putString("attempt", java.util.UUID.randomUUID().toString())
                    dataMap.putString("id", id)
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(this).putDataItem(ack), 30, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // No ACK: replay on a later delivery, retaining the local watch event.
            }
        }
    }
    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
