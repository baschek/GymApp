package de.baschek.gymapp.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.HeartSample
import de.baschek.gymapp.core.health.SampleQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    private val berlin = java.time.ZoneId.of("Europe/Berlin")

    @Test fun `exercise record carries device title zone offsets and a bumped version`() {
        val start = java.time.Instant.parse("2026-07-01T10:00:00Z").toEpochMilli()
        val watch = HeartRecording(RecordingProfile.PERSONAL, "synthetic", start, start + 3_600_000, 2,
            listOf(HeartSample(start + 1000, 90)))
        val exercise = recordsFor(watch, " Push A ", berlin).filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals("Push A", exercise.title)
        assertEquals(java.time.ZoneOffset.ofHours(2), exercise.startZoneOffset)
        assertEquals(java.time.ZoneOffset.ofHours(2), exercise.endZoneOffset)
        assertEquals(androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, exercise.metadata.recordingMethod)
        assertEquals(androidx.health.connect.client.records.metadata.Device.TYPE_WATCH, exercise.metadata.device?.type)
        assertEquals(2L + EXERCISE_FORMAT_VERSION_OFFSET, exercise.metadata.clientRecordVersion)
        val heart = recordsFor(watch, null, berlin).filterIsInstance<HeartRateRecord>().single()
        assertEquals(java.time.ZoneOffset.ofHours(2), heart.startZoneOffset)
        assertEquals(2L, heart.metadata.clientRecordVersion)
    }

    @Test fun `without heart data the phone is the device, a blank title is dropped and winter offset applies`() {
        val start = java.time.Instant.parse("2026-01-15T10:00:00Z").toEpochMilli()
        val phone = HeartRecording(RecordingProfile.PERSONAL, "synthetic", start, start + 1000, 0, emptyList())
        val records = recordsFor(phone, "  ", berlin)
        val exercise = records.single() as ExerciseSessionRecord
        assertEquals(null, exercise.title)
        assertEquals(androidx.health.connect.client.records.metadata.Device.TYPE_PHONE, exercise.metadata.device?.type)
        assertEquals(java.time.ZoneOffset.ofHours(1), exercise.startZoneOffset)
        assertEquals(records, recordsFor(phone, "  ", berlin))
    }

    @Test fun `exported client ids cover exercise heart chunks and the legacy heart id`() {
        val (exercise, heart) = exportedClientIds("synthetic")
        val id = java.util.UUID.nameUUIDFromBytes("synthetic".toByteArray()).toString()
        assertEquals(listOf("gymapp-exercise-$id"), exercise)
        assertTrue("gymapp-heart-$id-0" in heart && "gymapp-heart-$id-99" in heart && "gymapp-heart-$id" in heart)
        val recorded = recordsFor(HeartRecording(RecordingProfile.PERSONAL, "synthetic", 0, 10000, 1, listOf(HeartSample(1000, 90))))
        recorded.forEach { assertTrue(it.metadata.clientRecordId in exercise + heart) }
    }
}
