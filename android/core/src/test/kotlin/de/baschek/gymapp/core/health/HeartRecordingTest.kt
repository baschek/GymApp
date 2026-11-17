package de.baschek.gymapp.core.health

import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HeartRecordingTest {
    @Test fun `recording preserves gaps and quality across serialization`() {
        val recording = HeartRecording(RecordingProfile.PERSONAL, "test", 0, 10000, 1,
            listOf(HeartSample(1000, 80), HeartSample(9000, 120, SampleQuality.UNRELIABLE)),
            listOf(RecordingGap(2000, 8000)))
        assertEquals(recording, Json.decodeFromString<HeartRecording>(Json.encodeToString(recording)))
        assertFailsWith<IllegalArgumentException> { recording.copy(samples = listOf(HeartSample(3000, 80))) }
        assertFailsWith<IllegalArgumentException> { recording.copy(samples = listOf(HeartSample(1000, 80), HeartSample(1000, 81))) }
        assertFailsWith<IllegalArgumentException> { HeartSample(0, 301) }
    }

    @Test fun `known set durations bound between-set phase`() {
        val session = Session(exercises = listOf(SessionExercise(sets = listOf(
            SetLog(id = "a", status = SetStatus.COMPLETED, completedAt = "1970-01-01T00:00:10Z", setDurationSeconds = 3),
            SetLog(id = "b", status = SetStatus.COMPLETED, completedAt = "1970-01-01T00:00:20Z", setDurationSeconds = 4),
        ))))
        assertEquals(listOf(
            TimedPhase(0, 7000, Phase.UNKNOWN), TimedPhase(7000, 10000, Phase.SET, "a"),
            TimedPhase(10000, 16000, Phase.BETWEEN_SETS), TimedPhase(16000, 20000, Phase.SET, "b"),
            TimedPhase(20000, 25000, Phase.UNKNOWN),
        ), correlatePhases(session, 0, 25000))
        val missing = session.copy(exercises = listOf(session.exercises.single().copy(sets = listOf(
            session.exercises.single().sets.first().copy(setDurationSeconds = null)))))
        assertEquals(listOf(TimedPhase(0, 25000, Phase.UNKNOWN)), correlatePhases(missing, 0, 25000))
    }
}
