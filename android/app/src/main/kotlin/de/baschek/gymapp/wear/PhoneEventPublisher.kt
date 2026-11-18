package de.baschek.gymapp.wear

import android.content.Context
import de.baschek.gymapp.core.wear.events.WorkoutPublicationGate
import de.baschek.gymapp.core.wear.events.afterWorkoutQuietWindow
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.wear.transport.WorkoutDataItems
import de.baschek.gymapp.wear.transport.workoutHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun transferHash(id: String): String = workoutHash(id)

/** Queue lives in Room, and DataItems persist until acknowledged. */
internal class PhoneEventPublisher(private val context: Context, private val repository: GymRepository) {
    private val publication = WorkoutPublicationGate()
    private val events = repository.observePhoneWatchEvents()

    fun start(scope: CoroutineScope) = scope.launch {
        publishWhileActive(events.afterWorkoutQuietWindow()) { snapshot ->
            if (snapshot.isNotEmpty()) withContext(Dispatchers.IO) {
                publication.publish(read = { events.first() }) { latest ->
                    // The ticker may arrive while another edit is still inside the quiet window.
                    if (latest == snapshot) WorkoutDataItems.publish(context, latest)
                }
            }
        }
    }

    suspend fun acknowledge(localNode: String, hash: String) = publication.acknowledge {
        events.first().firstOrNull { transferHash(it.eventId) == hash }
            ?.let { repository.acknowledgePhoneWatchEvent(it.eventId) }
        WorkoutDataItems.deleteLocal(context, localNode, WorkoutDataItems.PREFIX, hash)
    }
}
