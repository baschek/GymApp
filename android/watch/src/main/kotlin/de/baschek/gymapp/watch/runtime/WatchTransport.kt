package de.baschek.gymapp.watch.runtime

import de.baschek.gymapp.wear.transport.WorkoutDataItems
import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import de.baschek.gymapp.core.wear.events.*
import de.baschek.gymapp.watch.WatchApplication
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** DataItems are the durable retry layer. Repository transactions precede acknowledgements. */
object WatchTransport {
    private val json = Json { ignoreUnknownKeys = false }
    private val publication = WorkoutPublicationGate()
    private const val MAX_ASSET = 12 * 1024 * 1024

    /** Signed-app Data Layer hello enables the phone bridge without sending workout data. */
    suspend fun sendHello(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as WatchApplication
        if (app.repository.value?.state?.value?.profile != WorkoutProfile.PERSONAL) return@withContext
        val node = WorkoutDataItems.localNodeId(context)
        val id = HeartTransfer.id(node)
        val request = PutDataMapRequest.create("/gymapp/hello/v1/$id")
        request.dataMap.putString("id", id)
        request.dataMap.putString("nodeId", node)
        request.dataMap.putInt("eventProtocol", 2)
        request.dataMap.putString("attempt", java.util.UUID.randomUUID().toString())
        Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()), 30, TimeUnit.SECONDS)
        Unit
    }

    suspend fun sendPending(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as WatchApplication
        publication.publish(read = {
            app.repository.value?.state?.value?.takeIf { it.profile == WorkoutProfile.PERSONAL }?.outbox.orEmpty()
        }) { pending ->
            WorkoutDataItems.publish(context, pending)
        }
    }

    private fun readAsset(context: Context, item: DataItem, key: String = "payload"): ByteArray {
        val asset = requireNotNull(DataMapItem.fromDataItem(item).dataMap.getAsset(key))
        val stream = requireNotNull(Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset), 30, TimeUnit.SECONDS).inputStream)
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
        val app = context.applicationContext as WatchApplication
        val buffer = Tasks.await(Wearable.getDataClient(context).dataItems, 30, TimeUnit.SECONDS)
        try {
            suspend fun attempt(block: suspend () -> Unit) {
                try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.w("WatchTransport", "Could not reconcile wearable item (${e.javaClass.simpleName})")
                    RuntimeMonitor.state.update { it.copy(connectionLabel = "Sync-Fehler") }
                }
            }
            // One malformed or temporarily unavailable item must not block the remaining durable items.
            if (!eventsOnly) buffer.forEach { if (it.uri.path == "/gymapp/catalog/v1/current") attempt { process(context, it, DataEvent.TYPE_CHANGED, false) } }
            app.workoutInbox.catchUp()
            buffer.forEach {
                if (it.uri.path?.startsWith("/gymapp/event-ack/v1/") == true)
                    attempt { process(context, it, DataEvent.TYPE_CHANGED, false) }
            }
            if (!eventsOnly) buffer.forEach {
                if (it.uri.path?.startsWith("/gymapp/heart-ack/v1/") == true) attempt { HeartTransfer.acknowledge(context, it.uri) }
                if (it.uri.path?.startsWith("/gymapp/health-status/v1/") == true || it.uri.path == "/gymapp/health-settings/v1/current") attempt { process(context, it, DataEvent.TYPE_CHANGED, false) }
            }
        } finally { buffer.release() }
    }

    internal suspend fun receive(app: WatchApplication, events: List<WorkoutEvent>) {
        val repo = app.repository.value?.takeIf { it.state.value.profile == WorkoutProfile.PERSONAL } ?: return
        val result = repo.receiveBatch(events)
        if (result.acknowledgements.size < events.size) RuntimeMonitor.state.update { it.copy(connectionLabel = "Konflikt") }
        if (!WorkoutDataItems.acknowledge(app, result.acknowledgements)) {
            RuntimeMonitor.state.update { it.copy(connectionLabel = "Sync-Fehler") }
        }
    }

    private suspend fun process(context: Context, item: DataItem, eventType: Int, rescan: Boolean = true): Unit = withContext(Dispatchers.IO) {
        val app = context.applicationContext as WatchApplication
        val uri = item.uri
        val own = WorkoutDataItems.localNodeId(context)
        // Data Layer isolates application package and signing identity. Delivery may occur after
        // the source disconnects, so connectedNodes is not an authorization list.
        if (uri.host == null || uri.host == own) return@withContext
        val parts = uri.pathSegments
        if (parts == listOf("gymapp", "health-settings", "v1", "current")) {
            if (eventType != DataEvent.TYPE_CHANGED) return@withContext
            val repo = app.restoreRepository() ?: return@withContext
            if (repo.state.value.profile != WorkoutProfile.PERSONAL) return@withContext
            val map = DataMapItem.fromDataItem(item).dataMap
            if (!map.containsKey("enabled") || !map.containsKey("background")) return@withContext
            val enabled = map.getBoolean("enabled")
            val background = map.getBoolean("background")
            context.getSharedPreferences("phone-health-settings", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled).putBoolean("background", background).apply()
            RuntimeMonitor.state.update { it.copy(phoneHealthEnabled = enabled, phoneHealthBackground = background) }
            return@withContext
        }
        if (parts == listOf("gymapp", "catalog", "v1", "current")) {
            if (eventType == DataEvent.TYPE_DELETED) { app.invalidatePersonal(); return@withContext }
            if (eventType != DataEvent.TYPE_CHANGED) return@withContext
            val catalog = json.decodeFromString<WatchCatalog>(readAsset(context, item).decodeToString())
            require(catalog.profile == WorkoutProfile.PERSONAL)
            val current = app.restoreRepository()
            if (current?.state?.value?.profile == WorkoutProfile.TEST) return@withContext
            val repo = current ?: app.selectProfile(WorkoutProfile.PERSONAL)
            val map = DataMapItem.fromDataItem(item).dataMap
            val protocol = map.getInt("eventProtocol", 1)
            val allSessions = if (map.getBoolean("allSessionIdsComplete", false))
                json.decodeFromString<List<String>>(readAsset(context, item, "allSessionIds").decodeToString()).toSet() else null
            check(repo.replaceCatalog(catalog, protocol, allSessions))
            RuntimeMonitor.state.update { it.copy(connectionLabel = "Handy verbunden") }
            if (rescan) reconcile(context, eventsOnly = true)
            return@withContext
        }
        val removedEvent = WorkoutDataItems.eventHash(uri)
        if (eventType == DataEvent.TYPE_DELETED && removedEvent != null) {
            // The phone dropped its event after our ACK, so the ACK item is no longer needed.
            WorkoutDataItems.deleteLocal(context, own, WorkoutDataItems.ACK_PREFIX, removedEvent)
            app.workoutInbox.changed()
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
            "event-ack" -> {
                if (DataMapItem.fromDataItem(item).dataMap.getString("id") != hash) return@withContext
                app.restoreRepository()
                publication.acknowledge {
                    val repo = app.repository.value ?: return@acknowledge
                    val matched = repo.state.value.outbox.filter { HeartTransfer.id(it.eventId) == hash }.map { it.eventId }.toSet()
                    if (matched.isNotEmpty()) repo.acknowledge(matched)
                    WorkoutDataItems.deleteLocal(context, own, WorkoutDataItems.PREFIX, hash)
                }
            }
        }
    }
}

class WatchDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val app = application as WatchApplication
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && WorkoutDataItems.eventHash(event.dataItem.uri) != null) {
                app.workoutInbox.changed()
                return@forEach
            }
            try {
                runBlocking {
                    if (event.dataItem.uri.path?.startsWith("/gymapp/heart-ack/v1/") == true && event.type == DataEvent.TYPE_CHANGED)
                        HeartTransfer.acknowledge(applicationContext, event.dataItem.uri)
                    else WatchTransport.incoming(applicationContext, event)
                }
            } catch (e: Exception) {
                Log.w("WatchDataListener", "Could not process wearable item (${e.javaClass.simpleName})")
                RuntimeMonitor.state.update { it.copy(connectionLabel = "Sync-Fehler") }
            }
        }
    }
}
