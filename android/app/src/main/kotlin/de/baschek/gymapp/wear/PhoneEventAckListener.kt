package de.baschek.gymapp.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.wear.transport.WorkoutDataItems
import kotlinx.coroutines.runBlocking

class PhoneEventAckListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            try {
                val uri = event.dataItem.uri
                val hash = WorkoutDataItems.eventHash(uri, WorkoutDataItems.ACK_PREFIX) ?: continue
                val localNode = WorkoutDataItems.localNodeId(this)
                if (uri.host == null || uri.host == localNode) continue
                if (DataMapItem.fromDataItem(event.dataItem).dataMap.getString("id") != hash) continue
                runBlocking {
                    (application as GymApplication).container.phoneEventPublisher.acknowledge(localNode, hash)
                }
            } catch (e: Exception) {
                Log.w("PhoneEventAckListener", "Could not apply workout acknowledgement (${e.javaClass.simpleName})")
            }
        }
    }
}
