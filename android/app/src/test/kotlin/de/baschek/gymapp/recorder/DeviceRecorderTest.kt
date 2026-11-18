package de.baschek.gymapp.recorder

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.recorder.Recording
import de.baschek.gymapp.recorder.shared.DeviceRecorder
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DeviceRecorderTest {
    private class Recorder : DeviceRecorder("phone") {
        suspend fun logs() = snapshot { dirs -> dirs.map { File(it, "events.jsonl").readText() } }
    }

    @Test fun idleTimeoutAndWorkoutEndSwitchOffAndSeparateRecordings() = runBlocking {
        val recorder = Recorder()
        recorder.install(ApplicationProvider.getApplicationContext())
        assertFalse(recorder.recording.value)
        recorder.start()
        recorder.workoutChanged(null)
        recorder.workoutChanged("synthetic-session")
        recorder.log("training", "81.25 kg × 17")
        recorder.log("training", "synthetic_note")
        recorder.workoutChanged(null)
        assertFalse(recorder.recording.value)
        recorder.log("training", "back")
        recorder.start()
        recorder.tap("next")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Recording.LIMIT_MS))
        assertFalse(recorder.recording.value)
        val logs = recorder.logs()
        assertEquals(2, logs.size)
        assertEquals(listOf(1, 2), logs.map { it.trimEnd().lines().size }.sorted())
        assertTrue(logs.none { "synthetic" in it || "81.25" in it || "× 17" in it || "back" in it })
    }
}
