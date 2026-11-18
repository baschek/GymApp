package de.baschek.gymapp.watch.recorder

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.core.recorder.RecorderConfig
import de.baschek.gymapp.recorder.shared.DeviceRecorder
import de.baschek.gymapp.watch.runtime.RuntimeMonitor
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NavRecorder : DeviceRecorder("watch") {
    override val config = RecorderConfig.WATCH

    /** Battery: images only while the screen is on and the app is not in ambient mode. Events stay cheap. */
    override fun shotsAllowed(): Boolean =
        !RuntimeMonitor.state.value.ambient && appContext.getSystemService(PowerManager::class.java)?.isInteractive != false

    /**
     * The send button rolls over to a new session first, then sends the closed ones that were not sent before. The
     * current session keeps recording. Each recording is one immutable, retryable Asset. Returns how many were sent.
     */
    suspend fun export(context: Context = appContext): Int = withContext(Dispatchers.IO) {
        val sessions = closeForTransfer().await().filterNot { File(it, SENT).exists() }
        for (dir in sessions) {
            val archive = File.createTempFile("gymapp-navrec-", ".zip", root)
            try {
                zip(listOf(dir), archive)
                val request = PutDataMapRequest.create("$TRANSFER/${dir.name}")
                ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    request.dataMap.putAsset("payload", Asset.createFromFd(fd))
                    request.dataMap.putString("attempt", UUID.randomUUID().toString())
                    Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()), 60, TimeUnit.SECONDS)
                }
                runCatching { File(dir, SENT).createNewFile() }
            } finally { archive.delete() }
        }
        sessions.size
    }

    /** Marker next to the session files; it is not part of the ZIP (see [DeviceRecorder.ENTRY_NAME]). */
    private const val SENT = ".sent"
}
