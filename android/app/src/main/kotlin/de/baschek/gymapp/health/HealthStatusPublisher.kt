package de.baschek.gymapp.health

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.wear.transferHash
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Status contains no measurements and says nothing about Samsung Health importing the data. */
suspend fun publishHealthStatus(context: Context, sessionId: String, version: Long, state: String) = withContext(Dispatchers.IO) {
    if (state !in setOf("EXPORTED", "PENDING", "PERMISSION_REQUIRED", "UNSUPPORTED", "NOT_ENABLED")) return@withContext
    val app = context.applicationContext as? GymApplication ?: return@withContext
    if (app.container.repository.currentProfile() != Profile.PERSONAL) return@withContext
    val id = transferHash(sessionId)
    try {
        val request = PutDataMapRequest.create("/gymapp/health-status/v1/$id").apply {
            dataMap.putString("id", id)
            dataMap.putString("sessionId", sessionId)
            dataMap.putLong("version", version)
            dataMap.putString("state", state)
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(context).putDataItem(request), 30, TimeUnit.SECONDS)
    } catch (_: Exception) {
        // A later export attempt can publish the status again.
    }
}

fun HealthExportResult.watchStatus(): String = when (this) {
    HealthExportResult.Exported -> "EXPORTED"
    HealthExportResult.UnsupportedHistory -> "UNSUPPORTED"
    is HealthExportResult.PermissionRequired -> "PERMISSION_REQUIRED"
    HealthExportResult.ConsentRequired, HealthExportResult.LocalOnly -> "NOT_ENABLED"
    else -> "PENDING"
}
