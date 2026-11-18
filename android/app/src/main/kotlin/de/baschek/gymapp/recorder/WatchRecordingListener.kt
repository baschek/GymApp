package de.baschek.gymapp.recorder

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import de.baschek.gymapp.recorder.shared.DeviceRecorder
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class WatchRecordingListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val client = Wearable.getDataClient(this)
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            val path = item.uri.path ?: continue
            val name = path.substringAfterLast('/')
            if (path != "${DeviceRecorder.TRANSFER}/$name" || !isWatchRecording(name)) continue
            try {
                val local = Tasks.await(Wearable.getNodeClient(this).localNode, 10, TimeUnit.SECONDS)
                if (item.uri.host == local.id) continue
                val asset = DataMapItem.fromDataItem(item).dataMap.getAsset("payload") ?: continue
                val response = Tasks.await(client.getFdForAsset(asset), 60, TimeUnit.SECONDS)
                requireNotNull(response.inputStream).use { receiveRecording(File(filesDir, "navrec"), name, it) }
                Tasks.await(client.deleteDataItems(item.uri), 30, TimeUnit.SECONDS)
            } catch (_: Exception) {
                Log.w("NavRecorder", "Watch recording transfer incomplete")
            }
        }
    }
}

private fun isWatchRecording(name: String) = name.startsWith("watch-") && DeviceRecorder.SESSION_DIR.matches(name)

/** Publish a complete directory in one rename. A duplicate delivery is harmless and can be acknowledged. */
internal fun receiveRecording(root: File, name: String, source: InputStream) {
    require(isWatchRecording(name))
    val target = File(root, name)
    if (target.isDirectory) return
    root.mkdirs()
    val staging = Files.createTempDirectory(root.toPath(), ".receiving-").toFile()
    try {
        var bytes = 0L
        val seen = mutableSetOf<String>()
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val leaf = entry.name.substringAfterLast('/')
                require(entry.name == "$name/$leaf" && DeviceRecorder.ENTRY_NAME.matches(leaf))
                require(!entry.isDirectory && seen.add(leaf) && seen.size <= 5_401)
                File(staging, leaf).outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count == -1) break
                        bytes += count
                        require(bytes <= 256L * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        }
        require("events.jsonl" in seen)
        check(staging.renameTo(target) || target.isDirectory)
    } finally { staging.deleteRecursively() }
}
