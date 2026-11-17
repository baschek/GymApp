package de.baschek.gymapp.health

import android.content.Context
import android.util.AtomicFile
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.RecordingProfile
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-only recordings, excluded from Android Auto Backup. */
class PrivateHeartStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "heart-recordings")
    private val json = Json { ignoreUnknownKeys = false }

    private fun file(profile: RecordingProfile, id: String): AtomicFile {
        require(id.isNotBlank() && id.length <= 256)
        check(directory.exists() || directory.mkdirs())
        val name = UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString()
        return AtomicFile(File(directory, "${profile.name}-$name.json"))
    }

    suspend fun save(recording: HeartRecording) = withContext(Dispatchers.IO) { healthStoreMutex.withLock { saveUnlocked(recording) } }

    private fun saveUnlocked(recording: HeartRecording) {
        val target = file(recording.profile, recording.sessionId)
        val existing = readUnlocked(recording.profile, recording.sessionId)
        require(existing == null || recording.version > existing.version || recording == existing) {
            "Changed recordings need a higher version"
        }
        val bytes = json.encodeToString(recording).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }

    /** Creates the initial no-pulse version atomically against a concurrent watch import. */
    suspend fun ensureEmpty(profile: RecordingProfile, sessionId: String, startMillis: Long, endMillis: Long): HeartRecording =
        withContext(Dispatchers.IO) { healthStoreMutex.withLock {
            readUnlocked(profile, sessionId) ?: HeartRecording(profile, sessionId, startMillis, endMillis, 0, emptyList()).also { saveUnlocked(it) }
        } }

    suspend fun read(profile: RecordingProfile, sessionId: String): HeartRecording? = withContext(Dispatchers.IO) { healthStoreMutex.withLock { readUnlocked(profile, sessionId) } }

    internal fun readUnlocked(profile: RecordingProfile, sessionId: String): HeartRecording? {
        val target = file(profile, sessionId)
        val input = try { target.openRead() } catch (e: FileNotFoundException) {
            val base = target.baseFile
            if (java.nio.file.Files.notExists(base.toPath()) &&
                java.nio.file.Files.notExists(File(base.path + ".bak").toPath())) return null
            throw e
        }
        val bytes = input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_BYTES)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        require(bytes.size <= MAX_BYTES)
        return json.decodeFromString<HeartRecording>(bytes.toString(Charsets.UTF_8)).also {
            require(it.sessionId == sessionId && it.profile == profile)
        }
    }

    suspend fun delete(profile: RecordingProfile, sessionId: String) = withContext(Dispatchers.IO) { healthStoreMutex.withLock { file(profile, sessionId).delete(); Unit } }
    private companion object { const val MAX_BYTES = 12 * 1024 * 1024 }
}

/** Serializes local edits with export and its checkpoint. */
internal val healthStoreMutex = Mutex()
