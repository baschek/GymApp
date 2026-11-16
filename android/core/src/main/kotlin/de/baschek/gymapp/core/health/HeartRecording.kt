package de.baschek.gymapp.core.health

import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SetStatus
import java.time.Instant
import kotlinx.serialization.Serializable

/** Epoch milliseconds make the data independent of device time zones. */
@Serializable
enum class SampleQuality { RELIABLE, UNRELIABLE }

@Serializable
data class HeartSample(val timeMillis: Long, val bpm: Int, val quality: SampleQuality = SampleQuality.RELIABLE) {
    init { require(timeMillis >= 0 && bpm in 1..300) }
}

/** Explicit collection outages; an absent measurement is never filled in. */
@Serializable
data class RecordingGap(val startMillis: Long, val endMillis: Long) {
    init { require(startMillis >= 0 && endMillis > startMillis) }
}

@Serializable
enum class RecordingProfile { PERSONAL, TEST }

@Serializable
data class HeartRecording(
    val profile: RecordingProfile,
    val sessionId: String,
    val startMillis: Long,
    val endMillis: Long,
    /** Increase whenever exported content changes; retain the version for retries. */
    val version: Long,
    val samples: List<HeartSample>,
    val gaps: List<RecordingGap> = emptyList(),
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 256)
        require(startMillis >= 0 && endMillis > startMillis && endMillis - startMillis <= 24 * 60 * 60 * 1000L)
        require(version >= 0 && samples.size <= 100_000 && gaps.size <= 10_000)
        require(samples.all { it.timeMillis in startMillis..endMillis })
        require(samples.zipWithNext().all { (a, b) -> a.timeMillis < b.timeMillis })
        require(gaps.all { it.startMillis >= startMillis && it.endMillis <= endMillis })
        require(gaps.zipWithNext().all { (a, b) -> a.endMillis <= b.startMillis })
        var gapIndex = 0
        samples.forEach { sample ->
            while (gapIndex < gaps.size && gaps[gapIndex].endMillis <= sample.timeMillis) gapIndex++
            require(gapIndex == gaps.size || sample.timeMillis <= gaps[gapIndex].startMillis)
        }
    }
}

/** Half-open intervals; UNKNOWN includes incomplete, conflicting, or unrecorded timing. */
enum class Phase { SET, BETWEEN_SETS, UNKNOWN }
data class TimedPhase(val startMillis: Long, val endMillis: Long, val phase: Phase, val setId: String? = null)

/** Uses actual recorded set duration only. Time before the first and after the last set is unknown. */
fun correlatePhases(session: Session, startMillis: Long, endMillis: Long): List<TimedPhase> {
    require(startMillis >= 0 && endMillis > startMillis)
    val sets = session.exercises.flatMap { it.sets }.filter { it.status == SetStatus.COMPLETED }
    // A completed set with missing timing can fall anywhere, making inferred rest unsafe.
    val intervals = sets.map { set ->
        val end = runCatching { Instant.parse(set.completedAt).toEpochMilli() }.getOrNull()
        val duration = set.setDurationSeconds
        if (end == null || duration == null || duration <= 0 || duration > 24 * 60 * 60) null
        else TimedPhase(end - duration * 1000L, end, Phase.SET, set.id)
    }
    if (intervals.any { it == null }) return listOf(TimedPhase(startMillis, endMillis, Phase.UNKNOWN))
    val sorted = intervals.filterNotNull().sortedBy { it.startMillis }
    if (sorted.any { it.startMillis < startMillis || it.endMillis > endMillis } ||
        sorted.zipWithNext().any { (a, b) -> a.endMillis > b.startMillis })
        return listOf(TimedPhase(startMillis, endMillis, Phase.UNKNOWN))
    if (sorted.isEmpty()) return listOf(TimedPhase(startMillis, endMillis, Phase.UNKNOWN))
    val result = mutableListOf<TimedPhase>()
    if (startMillis < sorted.first().startMillis) result += TimedPhase(startMillis, sorted.first().startMillis, Phase.UNKNOWN)
    sorted.forEachIndexed { i, current ->
        if (i > 0 && sorted[i - 1].endMillis < current.startMillis)
            result += TimedPhase(sorted[i - 1].endMillis, current.startMillis, Phase.BETWEEN_SETS)
        result += current
    }
    if (sorted.last().endMillis < endMillis) result += TimedPhase(sorted.last().endMillis, endMillis, Phase.UNKNOWN)
    return result
}
