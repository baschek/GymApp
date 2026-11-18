package de.baschek.gymapp.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.wear.transport.WorkoutDataItems

class WorkoutDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val inbox = (application as GymApplication).container.workoutInbox
        for (event in events) {
            val uri = event.dataItem.uri
            val id = WorkoutDataItems.eventHash(uri) ?: continue
            if (event.type == DataEvent.TYPE_CHANGED) {
                inbox.changed()
            } else if (event.type == DataEvent.TYPE_DELETED) {
                try {
                    val own = WorkoutDataItems.localNodeId(this)
                    if (uri.host != null && uri.host != own) {
                        WorkoutDataItems.deleteLocal(this, own, WorkoutDataItems.ACK_PREFIX, id)
                        inbox.changed()
                    }
                } catch (e: Exception) {
                    Log.w("WorkoutDataListener", "Could not clean up acknowledged workout item (${e.javaClass.simpleName})")
                }
            }
        }
    }
}
