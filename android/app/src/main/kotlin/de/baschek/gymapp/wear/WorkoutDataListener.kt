package de.baschek.gymapp.wear

import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.wear.transport.WorkoutDataItems
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Acknowledge individual operations only after the whole Room transaction commits. */
class WorkoutDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val own = runCatching { Tasks.await(Wearable.getNodeClient(this).localNode, 30, TimeUnit.SECONDS).id }
            .getOrNull() ?: return
        val changed = mutableSetOf<Uri>()
        for (event in events) {
            val uri = event.dataItem.uri
            if (uri.host == own || uri.path?.startsWith(WorkoutDataItems.PREFIX) != true) continue
            val id = uri.lastPathSegment?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: continue
            if (event.type == DataEvent.TYPE_CHANGED) changed += uri
            if (event.type == DataEvent.TYPE_DELETED) runCatching {
                val ack = Uri.Builder().scheme("wear").authority(own).path("/gymapp/event-ack/v1/$id").build()
                Tasks.await(Wearable.getDataClient(this).deleteDataItems(ack), 30, TimeUnit.SECONDS)
            }
        }
        if (changed.isNotEmpty()) runCatching { runBlocking { receivePending(changed) } }
    }

    private suspend fun receivePending(changed: Set<Uri>) {
        val events = WorkoutDataItems.ready(this, changed)
        if (events.isEmpty()) return
        val container = (application as GymApplication).container
        val result = container.repository.importWatchEvents(events)
        if (result.appliedCount > 0) container.scheduler.requestSync()
        val state = result.lastLeader
        if (state != null && state.lastOperation?.eventId == container.repository.getSetting("watch_leader_event")) {
            container.timer.applyRemote(state.timer)
            container.workout.setFocus(state.focusExerciseId, fromRemote = true)
        }
        for (id in result.acknowledgements) runCatching { WorkoutDataItems.acknowledge(this, id) }
    }
}
