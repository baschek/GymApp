package de.baschek.gymapp.watch.runtime

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** DataItem and its asset are stored by Play services and retried over the paired data layer. */
object HeartTransfer {
    fun id(sessionId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(sessionId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    suspend fun enqueue(context: Context, file: File) = withContext(Dispatchers.IO) {
        val match = Regex("PERSONAL-([0-9a-f]{64})-([0-9]+)\\.json").matchEntire(file.name)
            ?: error("Invalid pending recording")
        require(file.length() in 1..HeartQueue.MAX_BYTES.toLong())
        val (id, versionText) = match.destructured
        val version = versionText.toLong()
        val request = PutDataMapRequest.create("/gymapp/heart/v1/$id/$version")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            request.dataMap.putAsset("payload", Asset.createFromFd(descriptor))
            request.dataMap.putString("id", id)
            request.dataMap.putString("attempt", java.util.UUID.randomUUID().toString())
            request.dataMap.putLong("version", version)
            Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()))
        }
    }

    suspend fun retry(context: Context) {
        HeartQueue(context).pending().forEach { enqueue(context, it) }
    }

    /** Acknowledgements originate at a paired device with the same signed application identity. */
    suspend fun acknowledge(context: Context, itemUri: Uri) = withContext(Dispatchers.IO) {
        val parts = itemUri.pathSegments
        if (parts.size != 5 || parts.take(3) != listOf("gymapp", "heart-ack", "v1")) return@withContext
        val id = parts[3]
        val versionText = parts[4]
        val version = versionText.toLongOrNull() ?: return@withContext
        if (!id.matches(Regex("[0-9a-f]{64}")) || version < 0) return@withContext
        val client = Wearable.getNodeClient(context)
        val node = Tasks.await(client.localNode).id
        if (itemUri.host == null || itemUri.host == node) return@withContext
        val queue = HeartQueue(context)
        queue.acknowledge(id, version)
        val workoutPending = (context.applicationContext as? de.baschek.gymapp.watch.WatchApplication)?.repository?.value?.state?.value?.pendingEvents ?: 0
        RuntimeMonitor.state.update { it.copy(pendingCount = workoutPending + queue.pending().size) }
        val original = Uri.Builder().scheme("wear").authority(node).path("/gymapp/heart/v1/$id/$version").build()
        Tasks.await(Wearable.getDataClient(context).deleteDataItems(original))
    }
}
