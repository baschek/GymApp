package de.baschek.gymapp.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.AppPrefs
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString

/** Persistent DataItem is eventually delivered by Play services, including after reconnect. */
class WatchCatalogPublisher(private val context: Context, private val repository: GymRepository, private val prefs: AppPrefs) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) = scope.launch {
        var lastPublished: String? = null
        publishWhileActive(repository.observeWatchCatalogChanges().combine(prefs.accent) { _, accent -> accent }.debounce(500)) { accent ->
            withContext(Dispatchers.IO) {
                try {
                    val client = Wearable.getDataClient(context)
                    val snapshot = repository.watchCatalogSnapshot()
                    if (snapshot == null) {
                        val uri = android.net.Uri.Builder().scheme("wear").authority("*").path(PATH).build()
                        Tasks.await(client.deleteDataItems(uri), 30, TimeUnit.SECONDS)
                        lastPublished = null
                    } else {
                        val (catalog, sessionIds) = snapshot
                        val bytes = GymJson.encodeToString(catalog.copy(accent = accent.key)).toByteArray(Charsets.UTF_8)
                        require(bytes.size <= 12 * 1024 * 1024)
                        val sessionBytes = GymJson.encodeToString(sessionIds).toByteArray(Charsets.UTF_8)
                        require(sessionBytes.size <= 12 * 1024 * 1024)
                        val digest = MessageDigest.getInstance("SHA-256").digest(bytes + sessionBytes).joinToString("") { "%02x".format(it) }
                        if (digest == lastPublished) return@withContext
                        withTransferAsset(context, bytes) { asset ->
                          withTransferAsset(context, sessionBytes) { idsAsset ->
                            val request = PutDataMapRequest.create(PATH).apply {
                                dataMap.putAsset("payload", asset)
                                dataMap.putInt("eventProtocol", 2)
                                dataMap.putAsset("allSessionIds", idsAsset)
                                dataMap.putBoolean("allSessionIdsComplete", true)
                            }.asPutDataRequest().setUrgent()
                            Tasks.await(client.putDataItem(request), 30, TimeUnit.SECONDS)
                          }
                        }
                        lastPublished = digest
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Local state remains authoritative. The periodic retry will publish it again.
                }
            }
        }
    }
    private companion object { const val PATH = "/gymapp/catalog/v1/current" }
}
