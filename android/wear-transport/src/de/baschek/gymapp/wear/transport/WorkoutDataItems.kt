package de.baschek.gymapp.wear.transport

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.wear.events.WorkoutEvent
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.core.wear.events.WorkoutPublicationGrace
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    const val ACK_PREFIX = "/gymapp/event-ack/v1/"
    private const val TAG = "WorkoutDataItems"
    private val hash = Regex("[0-9a-f]{64}")
    private const val MAX_BYTES = 2 * 1024 * 1024
    private fun <T> Task<T>.waitForResult(): T = Tasks.await(this, 30, TimeUnit.SECONDS)

    internal data class Member(val uri: Uri, val metadata: DataMap)
    internal data class Inbox(val events: List<WorkoutEvent>, val hasPending: Boolean, val rescanAt: Long?)

    /** An old sender has no publication metadata. Its individual events can be read immediately. */
    internal fun readablePublications(
        items: List<Member>, grace: WorkoutPublicationGrace<Pair<String?, String?>>, now: Long,
    ): Pair<List<List<Member>>, Long?> {
        val publications = items.groupBy { it.uri.host to (it.metadata.getString("batchId") ?: it.uri.path) }
        val selection = grace.select(publications.mapValues { (_, group) ->
            group.all { it.metadata.getInt("batchSize", 1) == group.size }
        }, now)
        return selection.ready.map(publications::getValue) to selection.rescanAt
    }

    fun localNodeId(context: Context): String = Wearable.getNodeClient(context).localNode.waitForResult().id

    fun eventHash(uri: Uri, prefix: String = PREFIX): String? =
        uri.lastPathSegment?.takeIf { it.matches(hash) && uri.path == "$prefix$it" }

    /** Called on IO. Check the entire durable inbox because callbacks are lossy across restarts. */
    suspend fun ready(context: Context, grace: WorkoutPublicationGrace<Pair<String?, String?>>): Inbox {
        val own = localNodeId(context)
        val client = Wearable.getDataClient(context)
        val prefix = Uri.Builder().scheme("wear").authority("*").path(PREFIX).build()
        val buffer = client.getDataItems(prefix, DataClient.FILTER_PREFIX).waitForResult()
        try {
            val pending = mutableListOf<Member>()
            var hasPending = false
            for (item in buffer) {
                val uri = item.uri
                if (uri.host == null || uri.host == own || eventHash(uri) == null) continue
                hasPending = true
                val map = attempt("read event metadata") { DataMapItem.fromDataItem(item).dataMap } ?: continue
                pending += Member(uri, map)
            }
            val (ready, rescanAt) = readablePublications(pending, grace, SystemClock.elapsedRealtime())
            val events = ready.flatten().mapNotNull { member ->
                currentCoroutineContext().ensureActive()
                attempt("read event payload") { readEvent(client, member) }
            }
            return Inbox(events, hasPending, rescanAt)
        } finally { buffer.release() }
    }

    /** Publish v1 items with v2.8.1 metadata. Existing peers can still ACK each operation separately. */
    suspend fun publish(context: Context, events: List<WorkoutEvent>) {
        val client = Wearable.getDataClient(context)
        val publication = WorkoutPublication(events)
        var failed = false
        for (event in events) {
            currentCoroutineContext().ensureActive()
            if (attempt("publish event") { publishEvent(context, client, event, publication) } == null) failed = true
        }
        if (failed) throw IOException("One or more workout events could not be published")
    }

    private fun publishEvent(context: Context, client: DataClient, event: WorkoutEvent, publication: WorkoutPublication) {
        require(event.profile == WorkoutProfile.PERSONAL)
        val id = workoutHash(event.eventId)
        val bytes = GymJson.encodeToString(WorkoutEvent.serializer(), event).encodeToByteArray()
        require(bytes.size <= MAX_BYTES)
        val file = File.createTempFile("workout-event-", ".json", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val request = PutDataMapRequest.create("$PREFIX$id").apply {
                    publication.mark(dataMap)
                    dataMap.putString("id", id)
                    dataMap.putString("attempt", UUID.randomUUID().toString())
                    dataMap.putAsset("payload", Asset.createFromFd(fd))
                }
                client.putDataItem(request.asPutDataRequest().setUrgent()).waitForResult()
            }
        } finally { file.delete() }
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

    fun acknowledge(context: Context, eventIds: List<String>): Boolean {
        var sentAll = true
        for (eventId in eventIds) {
            if (attempt("acknowledge event") { acknowledgeEvent(context, eventId) } == null) sentAll = false
        }
        return sentAll
    }

    private fun acknowledgeEvent(context: Context, eventId: String) {
        val id = workoutHash(eventId)
        val ack = PutDataMapRequest.create("$ACK_PREFIX$id").apply {
            dataMap.putString("id", id)
            dataMap.putString("attempt", UUID.randomUUID().toString())
        }
        Wearable.getDataClient(context).putDataItem(ack.asPutDataRequest().setUrgent()).waitForResult()
    }

    fun deleteLocal(context: Context, localNode: String, prefix: String, id: String) {
        val uri = Uri.Builder().scheme("wear").authority(localNode).path("$prefix$id").build()
        Wearable.getDataClient(context).deleteDataItems(uri).waitForResult()
    }

    private inline fun <T> attempt(operation: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not $operation (${e.javaClass.simpleName})")
        null
    }
}
