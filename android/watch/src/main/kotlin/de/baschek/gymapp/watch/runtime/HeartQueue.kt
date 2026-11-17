package de.baschek.gymapp.watch.runtime

import android.content.Context
import android.util.AtomicFile
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.wear.backend.recording.LocalRecording
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private, no-backup queue. Acknowledged personal items alone may be removed. */
class HeartQueue(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "watch-heart-queue")
    private val json = Json
    private fun ensure() { check(directory.isDirectory || directory.mkdirs()) }
    private fun save(name: String, bytes: ByteArray): File = synchronized(lock) {
        require(bytes.size <= MAX_BYTES)
        ensure()
        val target = File(directory, name)
        if (target.exists()) {
            require(target.length() <= MAX_BYTES && target.readBytes().contentEquals(bytes)) { "Recording version already exists with different content" }
            return@synchronized target
        }
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (t: Throwable) { atomic.failWrite(stream); throw t }
        target
    }
    fun stage(recording: HeartRecording): File {
        require(recording.profile == RecordingProfile.PERSONAL)
        return save("PERSONAL-${HeartTransfer.id(recording.sessionId)}-${recording.version}.json", json.encodeToString(recording).encodeToByteArray())
    }
    fun archiveLocal(recording: LocalRecording): File = save(
        "LOCAL-${recording.profile.name}-${HeartTransfer.id(recording.sessionId)}.json",
        json.encodeToString(recording).encodeToByteArray(),
    )
    fun pending(): List<File> = synchronized(lock) {
        ensure()
        directory.listFiles()?.filter { it.isFile && it.name.matches(PENDING) }?.sortedBy { it.name } ?: emptyList()
    }
    fun acknowledge(id: String, version: Long) = synchronized(lock) {
        require(id.matches(Regex("[0-9a-f]{64}")) && version >= 0)
        val file = File(directory, "PERSONAL-$id-$version.json")
        if (file.exists()) check(file.delete()) { "Acknowledged recording could not be removed" }
    }
    companion object {
        const val MAX_BYTES = 12 * 1024 * 1024
        private val PENDING = Regex("PERSONAL-[0-9a-f]{64}-[0-9]+\\.json")
        private val lock = Any()
    }
}
