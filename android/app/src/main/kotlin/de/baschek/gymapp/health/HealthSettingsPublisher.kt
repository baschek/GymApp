package de.baschek.gymapp.health

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.GymApplication
import de.baschek.gymapp.data.Profile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Carries only opt-in and capability, not health measurements or identifiers. */
suspend fun publishHealthSettings(context: Context) = withContext(Dispatchers.IO) {
    val app = context.applicationContext as? GymApplication ?: return@withContext
    if (app.container.repository.currentProfile() != Profile.PERSONAL) return@withContext
    val enabled = HealthExportConsent(context).enabled()
    val background = enabled && runCatching { HealthConnectExporter(context).backgroundReadGranted() }.getOrDefault(false)
    try {
        val request = PutDataMapRequest.create("/gymapp/health-settings/v1/current").apply {
            dataMap.putBoolean("enabled", enabled)
            dataMap.putBoolean("background", background)
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(context).putDataItem(request), 30, TimeUnit.SECONDS)
    } catch (_: Exception) { /* Retried on phone resume or settings change. */ }
}
