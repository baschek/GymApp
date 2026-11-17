package de.baschek.gymapp.wear.backend.recording

import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.HeartSample
import de.baschek.gymapp.core.health.SampleQuality
import de.baschek.gymapp.core.health.RecordingGap
import de.baschek.gymapp.core.health.RecordingProfile
import kotlin.math.roundToInt

/**
 * Builds a stable version-1 snapshot after a normally ended exercise. Interrupted recordings
 * remain inspectable locally, but are not exported as complete: the actual end time is unknown.
 * Recordings exceeding the duration or sample limit stay local and are not exported as complete.
 */
fun LocalRecording.toHeartRecording(): HeartRecording {
    require(profile == RecordingProfile.PERSONAL) { "Test-profile recordings remain local" }
    require(status == RecordingStatus.ENDED) { "Only normally ended recordings have a known end" }
    require(!sampleLimitReached) { "Recording exceeded the supported sample limit" }
    require(!durationLimitReached) { "Recording exceeded the supported 24-hour duration" }
    val end = requireNotNull(endedAtMillis)
    val exportedSamples = samples.map {
        HeartSample(it.timeMillis, it.bpm.roundToInt(), if (it.reliable) SampleQuality.RELIABLE else SampleQuality.UNRELIABLE)
    }
    // A cached sample can arrive after an outage was recorded; do not claim it is missing.
    val exportedGaps = gaps.flatMap { gap ->
        val boundaries = listOf(gap.startMillis) + samples.asSequence().map { it.timeMillis }
            .filter { it > gap.startMillis && it < gap.endMillis }.toList() + gap.endMillis
        boundaries.zipWithNext().filter { (start, finish) -> finish > start }
            .map { (start, finish) -> RecordingGap(start, finish) }
    }
    return HeartRecording(sessionId = sessionId, profile = profile, startMillis = startedAtMillis, endMillis = end, version = 1, samples = exportedSamples, gaps = exportedGaps)
}
