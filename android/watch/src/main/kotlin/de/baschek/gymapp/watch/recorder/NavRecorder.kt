package de.baschek.gymapp.watch.recorder

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.baschek.gymapp.recorder.shared.DeviceRecorder
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NavRecorder : DeviceRecorder("watch") {
    /** The send button closes the current recording. Each recording is one immutable, retryable Asset. */
    suspend fun export(context: Context = appContext) = withContext(Dispatchers.IO) {
        val sessions = finishForTransfer().await()
        require(sessions.isNotEmpty()) { "Keine Aufzeichnung vorhanden." }
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
            } finally { archive.delete() }
        }
    }
}
