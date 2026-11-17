package de.baschek.gymapp.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.data.GymRepository
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

internal fun transferHash(id: String): String = MessageDigest.getInstance("SHA-256")
    .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Queue lives in Room, not in a transient callback; DataItems persist until acknowledged. */
class PhoneEventPublisher(private val context: Context, private val repository: GymRepository) {
    fun start(scope: CoroutineScope) = scope.launch {
        publishWhileActive(repository.observePhoneWatchEvents()) { events ->
              withContext(Dispatchers.IO) {
                for (event in events) {
                    try {
                        val id = transferHash(event.eventId)
                        val bytes = GymJson.encodeToString(de.baschek.gymapp.core.wear.events.WorkoutEvent.serializer(), event).toByteArray(Charsets.UTF_8)
                        require(bytes.size <= 2 * 1024 * 1024)
                        withTransferAsset(context, bytes) { asset ->
                            val request = PutDataMapRequest.create("/gymapp/event/v1/$id").apply {
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
