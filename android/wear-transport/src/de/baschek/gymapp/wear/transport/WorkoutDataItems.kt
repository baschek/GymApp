package de.baschek.gymapp.wear.transport

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.wear.events.WorkoutEvent
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Optional DataMap metadata. Older peers still read the original event and ACK its individual ID. */
internal class WorkoutPublication(events: List<WorkoutEvent>) {
    private val size = events.size
    private val id = if (size > 1) workoutHash(events.joinToString(",") { it.eventId }) else null

    fun mark(map: DataMap) {
        if (id != null) {
            map.putString("batchId", id)
            map.putInt("batchSize", size)
        }
    }
}

internal fun workoutHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal object WorkoutDataItems {
    const val PREFIX = "/gymapp/event/v1/"
    private val hash = Regex("[0-9a-f]{64}")
    private const val MAX_BYTES = 2 * 1024 * 1024
    private fun <T> Task<T>.waitForResult(): T = Tasks.await(this, 30, TimeUnit.SECONDS)

    internal data class Member(val uri: Uri, val metadata: DataMap)

    /** An old sender has no publication metadata. Its individual events can be read immediately. */
    internal fun completePublications(items: List<Member>): List<List<Member>> =
        items.groupBy { it.uri.host to (it.metadata.getString("batchId") ?: it.uri.path) }
            .values.filter { group -> group.all { it.metadata.getInt("batchSize", 1) == group.size } }

    /** Called on IO. Read the durable inbox, since one publication can span many callbacks. */
    fun ready(context: Context, changed: Set<Uri>?): List<WorkoutEvent> {
        val own = Wearable.getNodeClient(context).localNode.waitForResult().id
        val client = Wearable.getDataClient(context)
        val prefix = Uri.Builder().scheme("wear").authority("*").path(PREFIX).build()
        val buffer = client.getDataItems(prefix, DataClient.FILTER_PREFIX).waitForResult()
        try {
            val pending = mutableListOf<Member>()
            for (item in buffer) {
                val uri = item.uri
                val path = uri.path ?: continue
                val id = uri.lastPathSegment?.takeIf { it.matches(hash) } ?: continue
                if (uri.host != null && uri.host != own && path == "$PREFIX$id") {
                    val map = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull() ?: continue
                    pending += Member(uri, map)
                }
            }
            val events = mutableListOf<WorkoutEvent>()
            for (group in completePublications(pending)) {
                if (changed != null && group.none { it.uri in changed }) continue
                // A temporarily unreadable asset holds its group until the next outbox retry.
                try {
                    events += group.map { readEvent(client, it) }
                } catch (_: Exception) { continue }
            }
            return events
        } finally { buffer.release() }
    }

    private fun readEvent(client: DataClient, item: Member): WorkoutEvent {
        val id = item.uri.lastPathSegment
        val map = item.metadata
        require(map.getString("id") == id)
        val asset = requireNotNull(map.getAsset("payload"))
        val bytes = requireNotNull(client.getFdForAsset(asset).waitForResult().inputStream).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val event = GymJson.decodeFromString(WorkoutEvent.serializer(), bytes.decodeToString())
        require(event.profile == WorkoutProfile.PERSONAL && workoutHash(event.eventId) == id)
        return event
    }

    fun acknowledge(context: Context, eventId: String) {
        val id = workoutHash(eventId)
        val ack = PutDataMapRequest.create("/gymapp/event-ack/v1/$id").apply {
            dataMap.putString("id", id)
            dataMap.putString("attempt", UUID.randomUUID().toString())
        }
        Wearable.getDataClient(context).putDataItem(ack.asPutDataRequest().setUrgent()).waitForResult()
    }
}
