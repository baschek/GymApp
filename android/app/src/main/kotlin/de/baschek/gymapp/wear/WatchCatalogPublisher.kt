package de.baschek.gymapp.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.AppPrefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Persistent DataItem is eventually delivered by Play services, including after reconnect. */
class WatchCatalogPublisher(private val context: Context, private val repository: GymRepository, private val prefs: AppPrefs) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) = scope.launch {
        repository.observeWatchCatalogChanges().combine(prefs.accent) { _, accent -> accent }.debounce(500).collect { accent ->
            withContext(Dispatchers.IO) {
                try {
                    val client = Wearable.getDataClient(context)
                    val catalog = repository.watchCatalog()
                    if (catalog == null) {
                        val uri = android.net.Uri.Builder().scheme("wear").authority("*").path(PATH).build()
                        Tasks.await(client.deleteDataItems(uri), 30, TimeUnit.SECONDS)
                    } else {
                        val bytes = GymJson.encodeToString(catalog.copy(accent = accent.key)).toByteArray(Charsets.UTF_8)
                        require(bytes.size <= 12 * 1024 * 1024)
                        withTransferAsset(context, bytes) { asset ->
                            val request = PutDataMapRequest.create(PATH).apply {
                                dataMap.putAsset("payload", asset)
                            }.asPutDataRequest().setUrgent()
                            Tasks.await(client.putDataItem(request), 30, TimeUnit.SECONDS)
                        }
                    }
                } catch (_: Exception) {
                    // Local state remains authoritative. The next catalog change retries publication.
                }
            }
        }
    }
    private companion object { const val PATH = "/gymapp/catalog/v1/current" }
}
