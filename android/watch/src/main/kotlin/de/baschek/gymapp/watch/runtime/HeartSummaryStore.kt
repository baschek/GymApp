package de.baschek.gymapp.watch.runtime

import android.content.Context
import android.util.AtomicFile
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.wear.backend.recording.LocalRecording
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HeartSummary(val sessionId: String, val averageBpm: Int?, val maxBpm: Int?, val exportVersion: Long? = null, val exportState: String? = null)

/** Local summary and actual phone export result, separated by profile and session. */
class HeartSummaryStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "watch-heart-summaries")
    private fun file(profile: RecordingProfile, id: String): AtomicFile {
        require(id.isNotBlank() && id.length <= 256)
        check(directory.isDirectory || directory.mkdirs())
        return AtomicFile(File(directory, "${profile.name}-${HeartTransfer.id(id)}"))
    }
    private fun write(profile: RecordingProfile, summary: HeartSummary) {
        val target = file(profile, summary.sessionId)
        val output = target.startWrite()
        try { output.write(Json.encodeToString(summary).encodeToByteArray()); target.finishWrite(output) }
        catch (t: Throwable) { target.failWrite(output); throw t }
    }
    fun save(recording: LocalRecording) = synchronized(lock) {
        val samples = recording.samples.filter { it.reliable }
        val old = readUnlocked(recording.profile, recording.sessionId)
        write(recording.profile, HeartSummary(recording.sessionId,
            samples.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toInt(), samples.maxOfOrNull { it.bpm }?.toInt(),
            old?.exportVersion, old?.exportState))
    }
    fun updateStatus(sessionId: String, version: Long, status: String): HeartSummary = synchronized(lock) {
        require(version >= 0 && status in STATUS)
        val previous = readUnlocked(RecordingProfile.PERSONAL, sessionId) ?: HeartSummary(sessionId, null, null)
        if (previous.exportVersion != null && version < previous.exportVersion) return@synchronized previous
        val next = previous.copy(exportVersion = version, exportState = status)
        write(RecordingProfile.PERSONAL, next)
        next
    }
    fun read(profile: RecordingProfile, sessionId: String): HeartSummary? = synchronized(lock) { readUnlocked(profile, sessionId) }
    private fun readUnlocked(profile: RecordingProfile, sessionId: String): HeartSummary? {
        val target = file(profile, sessionId)
        val input = try { target.openRead() } catch (e: java.io.FileNotFoundException) {
            if (java.nio.file.Files.notExists(target.baseFile.toPath()) && java.nio.file.Files.notExists(File(target.baseFile.path + ".bak").toPath())) return null
            throw e
        }
        return input.use { stream ->
            val bytes = ByteArray(2049)
            var count = 0
            while (count < bytes.size) {
                val n = stream.read(bytes, count, bytes.size - count)
                if (n < 0) break
                count += n
            }
            require(count <= 2048)
            Json.decodeFromString<HeartSummary>(bytes.copyOf(count).decodeToString()).also { require(it.sessionId == sessionId) }
        }
    }
    companion object {
        private val lock = Any()
        private val STATUS = setOf("EXPORTED", "PENDING", "PERMISSION_REQUIRED", "UNSUPPORTED", "UNSUPPORTED_HISTORY", "FAILED", "NOT_ENABLED")
    }
}

internal fun exportLabel(state: String?): String = when (state) {
    "EXPORTED" -> "Health Connect aktuell"
    "NOT_ENABLED" -> "Gesundheitsexport nicht aktiviert"
    "PERMISSION_REQUIRED" -> "Gesundheitserlaubnis fehlt"
    "UNSUPPORTED", "UNSUPPORTED_HISTORY" -> "Gesundheitsexport nicht unterstützt"
    "FAILED" -> "Gesundheitsexport fehlgeschlagen"
    else -> "Gesundheitsexport ausstehend"
}
