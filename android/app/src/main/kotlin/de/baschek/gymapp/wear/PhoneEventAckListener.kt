package de.baschek.gymapp.wear

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import de.baschek.gymapp.GymApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class PhoneEventAckListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            try {
                val localNode = Tasks.await(Wearable.getNodeClient(this).localNode, 10, TimeUnit.SECONDS)
                if (event.dataItem.uri.host == localNode.id) continue
                val parts = event.dataItem.uri.path.orEmpty().split('/')
                if (parts.size != 5 || parts[1] != "gymapp" || parts[2] != "event-ack" || parts[3] != "v1") continue
                val hash = parts[4]
                if (!hash.matches(Regex("[0-9a-f]{64}")) || DataMapItem.fromDataItem(event.dataItem).dataMap.getString("id") != hash) continue
                runBlocking {
                    val repo = (application as GymApplication).container.repository
                    repo.observePhoneWatchEvents().first().firstOrNull { transferHash(it.eventId) == hash }
                        ?.let { repo.acknowledgePhoneWatchEvent(it.eventId) }
                }
            } catch (_: Exception) { /* Keep the outbox for replay. */ }
        }
    }
}
