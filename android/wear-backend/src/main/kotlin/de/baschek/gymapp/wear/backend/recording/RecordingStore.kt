package de.baschek.gymapp.wear.backend.recording

import android.content.Context
import android.util.AtomicFile
import de.baschek.gymapp.core.health.RecordingProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.serialization.json.jsonObject

/** The single locally retained recording. Reading errors are reported, never treated as an empty file. */
@Serializable
data class LocalRecording(
    val sessionId: String,
    val startedAtMillis: Long,
    val status: RecordingStatus,
    val bootElapsedAtStartMillis: Long,
    val profile: RecordingProfile,
    val samples: List<LocalSample> = emptyList(),
    val endedAtMillis: Long? = null,
    val error: String? = null,
    val sampleLimitReached: Boolean = false,
    val durationLimitReached: Boolean = false,
    val gaps: List<LocalGap> = emptyList(),
    val openGapAtMillis: Long? = null,
    val lastUpdateAtMillis: Long? = null,
)

@Serializable
enum class RecordingStatus { STARTING, ACTIVE, STOPPING, ENDED, INTERRUPTED, ERROR }

@Serializable
data class LocalGap(val startMillis: Long, val endMillis: Long)

@Serializable
data class LocalSample(val timeMillis: Long, val bpm: Double, val reliable: Boolean)

/**
 * Atomic metadata points to a committed prefix of an append-only sample journal. Only one
 * recorder/store may own this recording at a time. Old journal generations are kept until
 * the new metadata has been durably committed; incomplete tails are ignored after a crash.
 */
class RecordingStore(context: Context) {
    private val directory = context.applicationContext.noBackupFilesDir
    private val base = File(directory, "wear-heart-recording.json")
    private val file = AtomicFile(base)
    private val json = Json { ignoreUnknownKeys = true }
    private companion object {
        val lock = Any()
        const val MAX_BYTES = 16 * 1024 * 1024
        const val SAMPLE_BYTES = 17L
        const val PREFIX = "wear-heart-samples-"
        const val SUFFIX = ".bin"
    }

    @Serializable
    private data class Checkpoint(
        val format: Int = 1,
        val recording: LocalRecording,
        val generation: String,
        val committedCount: Int,
        val committedBytes: Long,
    )

    private data class Loaded(val recording: LocalRecording, val checkpoint: Checkpoint?)

    fun read(): LocalRecording? = synchronized(lock) { load()?.recording }

    private fun journal(generation: String): File {
        require(runCatching { UUID.fromString(generation).toString() == generation }.getOrDefault(false))
        return File(directory, "$PREFIX$generation$SUFFIX")
    }

    private fun load(): Loaded? {
        if (java.nio.file.Files.notExists(base.toPath()) && java.nio.file.Files.notExists(File(base.path + ".bak").toPath())) return null
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Recording metadata too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = bytes.decodeToString()
        // Read the previous single-file format too, and migrate it on the next write.
        if ("recording" !in json.parseToJsonElement(text).jsonObject) {
            return Loaded(json.decodeFromString<LocalRecording>(text).also(::validate), null)
        }
        val checkpoint = json.decodeFromString<Checkpoint>(text)
        require(checkpoint.format == 1 && checkpoint.recording.samples.isEmpty())
        require(checkpoint.committedCount in 0..100_000 && checkpoint.committedBytes == checkpoint.committedCount * SAMPLE_BYTES)
        val path = journal(checkpoint.generation)
        check(path.isFile) { "Committed sample journal is missing" }
        val samples = RandomAccessFile(path, "r").use { input ->
            check(input.length() >= checkpoint.committedBytes) { "Committed sample journal is truncated" }
            List(checkpoint.committedCount) { LocalSample(input.readLong(), input.readDouble(), input.readBoolean()) }
        }
        return Loaded(checkpoint.recording.copy(samples = samples).also(::validate), checkpoint)
    }

    fun clear() = synchronized(lock) {
        file.delete()
        check(java.nio.file.Files.notExists(base.toPath()) && java.nio.file.Files.notExists(File(base.path + ".bak").toPath())) { "Could not clear recording metadata" }
        cleanup(null)
    }

    fun write(recording: LocalRecording) = synchronized(lock) {
        validate(recording)
        val old = load()
        if (old != null) {
            check(old.recording.sessionId == recording.sessionId && old.recording.profile == recording.profile) { "Clear the previous recording before replacing it" }
            if (old.recording.status in setOf(RecordingStatus.ENDED, RecordingStatus.INTERRUPTED, RecordingStatus.ERROR))
                check(old.recording == recording) { "A terminal recording cannot be changed" }
        }
        val previous = old?.checkpoint
        // Reclaim only unreferenced generations after resolving the metadata backup.
        if (java.nio.file.Files.notExists(File(base.path + ".bak").toPath())) cleanup(previous?.generation)
        val samePrefix = old != null && previous != null && old.recording.sessionId == recording.sessionId &&
            old.recording.profile == recording.profile && old.recording.samples.size <= recording.samples.size &&
            old.recording.samples.indices.all { old.recording.samples[it] == recording.samples[it] }
        val generation = if (samePrefix) previous!!.generation else UUID.randomUUID().toString()
        val path = journal(generation)
        val prefixCount = if (samePrefix) previous!!.committedCount else 0
        if (!samePrefix) check(path.createNewFile()) { "Could not create sample journal" }
        RandomAccessFile(path, "rw").use { output ->
            val committed = prefixCount * SAMPLE_BYTES
            check(output.length() >= committed) { "Committed sample journal is truncated" }
            // Discard any append that was not committed by metadata before process death.
            output.setLength(committed)
            output.seek(committed)
            for (i in prefixCount until recording.samples.size) {
                val sample = recording.samples[i]
                output.writeLong(sample.timeMillis)
                output.writeDouble(sample.bpm)
                output.writeBoolean(sample.reliable)
            }
            output.fd.sync()
        }
        val checkpoint = Checkpoint(recording = recording.copy(samples = emptyList()), generation = generation,
            committedCount = recording.samples.size, committedBytes = recording.samples.size * SAMPLE_BYTES)
        val bytes = json.encodeToString(Checkpoint.serializer(), checkpoint).encodeToByteArray()
        require(bytes.size <= MAX_BYTES) { "Recording metadata too large" }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (t: Throwable) {
            file.failWrite(stream)
            throw t
        }
        // AtomicFile may silently fail to delete a legacy backup. openRead resolves any such
        // backup; never delete the old generation unless the new checkpoint is confirmed.
        val confirmed = load()
        check(confirmed?.checkpoint == checkpoint && java.nio.file.Files.notExists(File(base.path + ".bak").toPath())) {
            "Recording checkpoint could not be confirmed"
        }
        cleanup(generation)
    }

    private fun cleanup(keep: String?) {
        directory.listFiles()?.filter { it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) && it.name != "$PREFIX$keep$SUFFIX" }
            ?.forEach { it.delete() }
    }

    private fun validate(r: LocalRecording) {
        require(r.sessionId.isNotBlank() && r.sessionId.length <= 256)
        require(r.startedAtMillis >= 0 && r.bootElapsedAtStartMillis >= 0)
        require(r.samples.size <= 100_000 && r.gaps.size <= 10_000)
        require(r.samples.all { it.timeMillis >= r.startedAtMillis && it.timeMillis - r.startedAtMillis <= 86_400_000 && it.bpm.isFinite() && it.bpm in 1.0..300.0 })
        require(r.samples.zipWithNext().all { (a, b) -> a.timeMillis < b.timeMillis })
        require(r.endedAtMillis == null || r.endedAtMillis >= r.startedAtMillis)
        require(r.openGapAtMillis == null || r.openGapAtMillis >= r.startedAtMillis)
        require(r.gaps.all { it.startMillis >= r.startedAtMillis && it.endMillis > it.startMillis && it.endMillis - r.startedAtMillis <= 86_400_000 })
        require(r.lastUpdateAtMillis == null || r.lastUpdateAtMillis in r.startedAtMillis..(r.startedAtMillis + 86_400_000))
        require(r.gaps.zipWithNext().all { (a, b) -> a.endMillis <= b.startMillis })
    }
}
