package de.baschek.gymapp.health

import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.HeartSample
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PrivateHeartStoreTest {
    @Test fun `round trip is private and changed data requires a new version`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = PrivateHeartStore(context)
        val recording = HeartRecording(RecordingProfile.PERSONAL, "store-test", 0, 1000, 1, listOf(HeartSample(500, 90)))
        store.delete(recording.profile, recording.sessionId)
        store.save(recording)
        val testRecording = recording.copy(profile = RecordingProfile.TEST, samples = listOf(HeartSample(500, 100)))
        store.save(testRecording)
        assertEquals(testRecording, store.read(RecordingProfile.TEST, recording.sessionId))
        assertEquals(HealthExportResult.LocalOnly, HealthConnectExporter(context).export(recording.sessionId, RecordingProfile.TEST, 1, true))
        store.delete(RecordingProfile.TEST, recording.sessionId)
        assertEquals(recording, store.read(recording.profile, recording.sessionId))
        try {
            store.save(recording.copy(samples = listOf(HeartSample(500, 91))))
            throw AssertionError("Expected a version check")
        } catch (_: IllegalArgumentException) { }
        store.save(recording.copy(version = 2, samples = listOf(HeartSample(500, 91))))
        store.delete(recording.profile, recording.sessionId)
        assertNull(store.read(recording.profile, recording.sessionId))
    }
}
