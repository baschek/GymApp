package de.baschek.gymapp.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.HeartSample
import de.baschek.gymapp.core.health.SampleQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HealthExportTest {
    @Test fun `retry preserves identifiers and version while filtering unreliable samples`() {
        val recording = HeartRecording(RecordingProfile.PERSONAL, "synthetic", 0, 10000, 2, listOf(
            HeartSample(1000, 90), HeartSample(2000, 100, SampleQuality.UNRELIABLE)))
        val first = recordsFor(recording)
        val retry = recordsFor(recording)
        assertEquals(first, retry)
        val heart = first.filterIsInstance<HeartRateRecord>().single()
        assertEquals(listOf(90L), heart.samples.map { it.beatsPerMinute })
        assertEquals(1L, java.time.Duration.between(heart.startTime, heart.endTime).toMillis())
        assertEquals(2L, heart.metadata.clientRecordVersion)
        val next = recordsFor(recording.copy(version = 3))
        assertEquals(heart.metadata.clientRecordId, next.filterIsInstance<HeartRateRecord>().single().metadata.clientRecordId)
        assertNotEquals(first.filterIsInstance<ExerciseSessionRecord>().single().metadata.clientRecordId,
            heart.metadata.clientRecordId)
    }
}
