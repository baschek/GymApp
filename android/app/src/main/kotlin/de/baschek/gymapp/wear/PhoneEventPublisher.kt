package de.baschek.gymapp.wear

import de.baschek.gymapp.wear.transport.WorkoutPublication
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.wear.transport.workoutHash
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

internal fun transferHash(id: String): String = workoutHash(id)

/** Queue lives in Room, not in a transient callback; DataItems persist until acknowledged. */
class PhoneEventPublisher(private val context: Context, private val repository: GymRepository) {
    fun start(scope: CoroutineScope) = scope.launch {
        publishWhileActive(repository.observePhoneWatchEvents()) { events ->
              withContext(Dispatchers.IO) {
                val publication = WorkoutPublication(events)
                for (event in events) {
                    try {
                        val id = transferHash(event.eventId)
                        val bytes = GymJson.encodeToString(de.baschek.gymapp.core.wear.events.WorkoutEvent.serializer(), event).toByteArray(Charsets.UTF_8)
                        require(bytes.size <= 2 * 1024 * 1024)
                        withTransferAsset(context, bytes) { asset ->
                            val request = PutDataMapRequest.create("/gymapp/event/v1/$id").apply {
                                publication.mark(dataMap)
                                dataMap.putString("id", id)
                                dataMap.putLong("attempt", System.currentTimeMillis())
                                dataMap.putAsset("payload", asset)
                            }.asPutDataRequest().setUrgent()
                            Tasks.await(Wearable.getDataClient(context).putDataItem(request), 30, TimeUnit.SECONDS)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep the durable outbox for a later attempt.
                    }
                }
              }
        }
    }
}
