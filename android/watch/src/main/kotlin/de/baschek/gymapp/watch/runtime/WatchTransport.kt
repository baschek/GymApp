package de.baschek.gymapp.watch.runtime

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import de.baschek.gymapp.core.wear.events.*
import de.baschek.gymapp.watch.WatchApplication
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** DataItems are the durable retry layer. Repository transactions precede acknowledgements. */
object WatchTransport {
    private val json = Json { ignoreUnknownKeys = false }
    private const val MAX_ASSET = 8 * 1024 * 1024

    /** Signed-app Data Layer hello enables the phone bridge without sending workout data. */
    suspend fun sendHello(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as WatchApplication
        if (app.repository.value?.state?.value?.profile != WorkoutProfile.PERSONAL) return@withContext
        val node = Tasks.await(Wearable.getNodeClient(context).localNode, 30, java.util.concurrent.TimeUnit.SECONDS).id
        val id = HeartTransfer.id(node)
        val request = PutDataMapRequest.create("/gymapp/hello/v1/$id")
        request.dataMap.putString("id", id)
        request.dataMap.putString("nodeId", node)
        request.dataMap.putString("attempt", java.util.UUID.randomUUID().toString())
        Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()), 30, java.util.concurrent.TimeUnit.SECONDS)
        Unit
    }

    suspend fun sendPending(context: Context, events: List<WorkoutEvent>) = withContext(Dispatchers.IO) {
        val pending = events.filter { it.profile == WorkoutProfile.PERSONAL }
        RuntimeMonitor.state.update { it.copy(pendingCount = pending.size + HeartQueue(context).pending().size) }
        val client = Wearable.getDataClient(context)
        for (event in pending) {
            val hash = HeartTransfer.id(event.eventId)
            val bytes = json.encodeToString(event).encodeToByteArray()
            require(bytes.size <= MAX_ASSET)
            val temp = File.createTempFile("watch-event-", ".json", context.cacheDir)
            try {
                temp.outputStream().use { it.write(bytes); it.fd.sync() }
                val request = PutDataMapRequest.create("/gymapp/event/v1/$hash")
                ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    request.dataMap.putAsset("payload", Asset.createFromFd(fd))
                    request.dataMap.putString("id", hash)
                    request.dataMap.putString("attempt", java.util.UUID.randomUUID().toString())
                    Tasks.await(client.putDataItem(request.asPutDataRequest().setUrgent()))
                }
            } finally { temp.delete() }
        }
    }

    private fun readAsset(context: Context, item: DataItem): ByteArray {
        val asset = requireNotNull(DataMapItem.fromDataItem(item).dataMap.getAsset("payload"))
        val stream = requireNotNull(Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset)).inputStream)
        return stream.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_ASSET)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    suspend fun incoming(context: Context, event: DataEvent): Unit = process(context, event.dataItem, event.type)

    /** Replays durable items after process death or before a catalog became available. */
    suspend fun reconcile(context: Context, eventsOnly: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val buffer = Tasks.await(Wearable.getDataClient(context).dataItems)
        try {
            // Catalog before events so an initially unconfigured watch can accept a session.
            if (!eventsOnly) buffer.forEach { if (it.uri.path == "/gymapp/catalog/v1/current") process(context, it, DataEvent.TYPE_CHANGED, false) }
            buffer.forEach {
                if (it.uri.path?.startsWith("/gymapp/event/v1/") == true || it.uri.path?.startsWith("/gymapp/event-ack/v1/") == true)
                    process(context, it, DataEvent.TYPE_CHANGED, false)
            }
            if (!eventsOnly) buffer.forEach {
                if (it.uri.path?.startsWith("/gymapp/heart-ack/v1/") == true) HeartTransfer.acknowledge(context, it.uri)
                if (it.uri.path?.startsWith("/gymapp/health-status/v1/") == true) process(context, it, DataEvent.TYPE_CHANGED, false)
            }
        } finally { buffer.release() }
    }

    private suspend fun process(context: Context, item: DataItem, eventType: Int, rescan: Boolean = true): Unit = withContext(Dispatchers.IO) {
        val app = context.applicationContext as WatchApplication
        val uri = item.uri
        val nodeClient = Wearable.getNodeClient(context)
        val own = Tasks.await(nodeClient.localNode).id
        // Data Layer isolates application package and signing identity. Delivery may occur after
        // the source disconnects, so connectedNodes is not an authorization list.
        if (uri.host == null || uri.host == own) return@withContext
        val parts = uri.pathSegments
        if (parts == listOf("gymapp", "catalog", "v1", "current")) {
            if (eventType == DataEvent.TYPE_DELETED) { app.invalidatePersonal(); return@withContext }
            if (eventType != DataEvent.TYPE_CHANGED) return@withContext
            val catalog = json.decodeFromString<WatchCatalog>(readAsset(context, item).decodeToString())
            require(catalog.profile == WorkoutProfile.PERSONAL)
            val current = app.restoreRepository()
            if (current?.state?.value?.profile == WorkoutProfile.TEST) return@withContext
            val repo = current ?: app.selectProfile(WorkoutProfile.PERSONAL)
            check(repo.replaceCatalog(catalog))
            RuntimeMonitor.state.update { it.copy(connectionLabel = "Handy verbunden") }
            if (rescan) reconcile(context, eventsOnly = true)
            return@withContext
        }
        if (eventType == DataEvent.TYPE_DELETED && parts.size == 4 && parts[0] == "gymapp" && parts[1] == "event" && parts[2] == "v1" &&
            parts[3].matches(Regex("[0-9a-f]{64}"))) {
            // The phone dropped its event after our ACK, so the ACK item is no longer needed.
            val ackUri = Uri.Builder().scheme("wear").authority(own).path("/gymapp/event-ack/v1/${parts[3]}").build()
            Tasks.await(Wearable.getDataClient(context).deleteDataItems(ackUri))
            return@withContext
        }
        if (eventType != DataEvent.TYPE_CHANGED || parts.size != 4 || parts[0] != "gymapp" || parts[2] != "v1") return@withContext
        val hash = parts[3]
        if (!hash.matches(Regex("[0-9a-f]{64}"))) return@withContext
        when (parts[1]) {
            "health-status" -> {
                val map = DataMapItem.fromDataItem(item).dataMap
                val session = map.getString("sessionId") ?: return@withContext
                val version = map.getLong("version", -1)
                val status = map.getString("state")?.uppercase(java.util.Locale.ROOT) ?: return@withContext
                if (session.isBlank() || session.length > 256 || HeartTransfer.id(session) != hash || map.getString("id") != hash || version < 0) return@withContext
                val summary = HeartSummaryStore(context).updateStatus(session, version, status)
                RuntimeMonitor.state.update { current ->
                    if (current.heartSessionId == session && current.heartProfile == de.baschek.gymapp.core.health.RecordingProfile.PERSONAL && summary.exportVersion == 1L)
                        current.copy(exportLabel = exportLabel(summary.exportState)) else current
                }
            }
            "event" -> {
                val incoming = json.decodeFromString<WorkoutEvent>(readAsset(context, item).decodeToString())
                require(incoming.profile == WorkoutProfile.PERSONAL && HeartTransfer.id(incoming.eventId) == hash)
                val repo = app.restoreRepository() ?: return@withContext
                if (repo.state.value.profile != WorkoutProfile.PERSONAL) return@withContext
                val result = repo.receive(incoming)
                if (result is EventResult.Applied || result is EventResult.Replayed ||
                    (result is EventResult.Rejected && EventRejections.shouldAcknowledge(result))) {
                    run {
                        val ack = PutDataMapRequest.create("/gymapp/event-ack/v1/$hash")
                        ack.dataMap.putString("id", hash)
                        ack.dataMap.putString("attempt", java.util.UUID.randomUUID().toString())
                        Tasks.await(Wearable.getDataClient(context).putDataItem(ack.asPutDataRequest().setUrgent()))
                    }
                } else RuntimeMonitor.state.update { it.copy(connectionLabel = "Konflikt") }
            }
            "event-ack" -> {
                val repo = app.restoreRepository() ?: return@withContext
                val matched = repo.state.value.outbox.filter { HeartTransfer.id(it.eventId) == hash }.map { it.eventId }.toSet()
                if (matched.isNotEmpty()) repo.acknowledge(matched)
                val uriToDelete = Uri.Builder().scheme("wear").authority(own).path("/gymapp/event/v1/$hash").build()
                Tasks.await(Wearable.getDataClient(context).deleteDataItems(uriToDelete))
            }
        }
    }
}

class WatchDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        // WearableListenerService invokes this on its worker thread; finish durable work before return.
        events.forEach { event ->
            try {
                runBlocking {
                    if (event.dataItem.uri.path?.startsWith("/gymapp/heart-ack/v1/") == true && event.type == DataEvent.TYPE_CHANGED)
                        HeartTransfer.acknowledge(applicationContext, event.dataItem.uri)
                    else WatchTransport.incoming(applicationContext, event)
                }
            } catch (_: Exception) {
                RuntimeMonitor.state.update { it.copy(connectionLabel = "Sync-Fehler") }
            }
        }
    }
}
