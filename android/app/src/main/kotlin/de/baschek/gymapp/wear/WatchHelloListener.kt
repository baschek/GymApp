package de.baschek.gymapp.wear

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import de.baschek.gymapp.GymApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Data Layer is restricted to the same application ID and signing identity. */
class WatchHelloListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            try {
                val item = event.dataItem
                val parts = item.uri.path.orEmpty().split('/')
                if (parts.size != 5 || parts[1] != "gymapp" || parts[2] != "hello" || parts[3] != "v1") continue
                val nodeId = item.uri.host ?: continue
                val local = Tasks.await(Wearable.getNodeClient(this).localNode, 10, TimeUnit.SECONDS)
                if (nodeId == local.id) continue
                val data = DataMapItem.fromDataItem(item).dataMap
                if (data.getString("nodeId") != nodeId || data.getString("id") != parts[4] || transferHash(nodeId) != parts[4]) continue
                runBlocking { (application as GymApplication).container.repository.enableWatchCompanion(data.getInt("eventProtocol", 1)) }
            } catch (_: Exception) {
                // The watch retries the durable handshake.
            }
        }
    }
}
