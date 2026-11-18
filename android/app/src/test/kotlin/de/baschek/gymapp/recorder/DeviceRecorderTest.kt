package de.baschek.gymapp.recorder

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.recorder.RecorderConfig
import de.baschek.gymapp.core.recorder.Recording
import de.baschek.gymapp.core.recorder.RetentionLimits
import de.baschek.gymapp.recorder.shared.DeviceRecorder
import de.baschek.gymapp.recorder.shared.ScreenCapture
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
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
    private val day = RecorderConfig.DAY_MS

    private class Recorder(
        override val config: RecorderConfig = RecorderConfig.PHONE,
        var shots: Boolean = true,
    ) : DeviceRecorder("phone") {
        override fun shotsAllowed() = shots
        val dir get() = root
        suspend fun logs() = snapshot { dirs -> dirs.map { File(it, "events.jsonl").readText() } }
        suspend fun names() = snapshot { dirs -> dirs.map { it.name }.toSet() }
        suspend fun archive(file: File) = snapshot { zip(it, file) }
        suspend fun transfer() = closeForTransfer().await().map { it.name }
        suspend fun deleteClosedSessions() = deleteClosed()
    }

    private fun config(sessions: Int = 20, ageDays: Long = 14, bytes: Long = 100L * 1024 * 1024, interval: Long = 2_000, shots: Int = 300) =
        RecorderConfig(interval, shots, RetentionLimits(sessions, ageDays * day, bytes))

    private fun recorder(config: RecorderConfig = RecorderConfig.PHONE) =
        Recorder(config).also { it.install(ApplicationProvider.getApplicationContext()) }

    /** A finished synthetic session folder with the given age and payload size. */
    private fun session(root: File, ageMs: Long, bytes: Int = 10): String {
        val dir = File(root, "phone-${UUID.randomUUID()}").apply { mkdirs() }
        val events = File(dir, "events.jsonl").apply { writeText("x".repeat(bytes)) }
        val time = System.currentTimeMillis() - ageMs
        events.setLastModified(time)
        dir.setLastModified(time)
        return dir.name
    }

    @Test fun startsOnItsOwnAndRollsOverOnWorkoutChangeWithoutEverStopping() = runBlocking {
        val recorder = recorder()
        assertFalse(recorder.recording.value)
        recorder.start()
        assertTrue(recorder.recording.value)
        recorder.workoutChanged(null)
        recorder.workoutChanged("synthetic-session")
        recorder.screen("training")
        recorder.log("training", "81.25 kg × 17")
        recorder.log("training", "synthetic_note")
        recorder.workoutChanged(null)
        assertTrue(recorder.recording.value)
        recorder.log("training", "back")
        recorder.tap("next")
        val logs = recorder.logs()
        assertEquals(2, logs.size)
        val lines = logs.map { it.trimEnd().lines() }.sortedBy { it.size }
        assertEquals(listOf(3, 3), lines.map { it.size }) // the second session starts with the carried screen
        assertTrue(lines.first().first().contains("\"type\":\"screen\"") && lines.first().first().contains("\"screen\":\"training\""))
        assertTrue(logs.none { "synthetic" in it || "81.25" in it || "× 17" in it })
    }

    @Test fun emptySessionsAreNeitherRolledOverNorWrittenToDisk() = runBlocking {
        val recorder = recorder()
        recorder.start()
        recorder.workoutChanged("a")
        recorder.workoutChanged(null)
        recorder.workoutChanged("b")
        assertTrue(recorder.names().isEmpty())
    }

    @Test fun limitRollsOverToANewSessionInsteadOfStopping() = runBlocking {
        val recorder = recorder()
        recorder.start()
        recorder.tap("next")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Recording.LIMIT_MS))
        assertTrue(recorder.recording.value)
        recorder.tap("back")
        val logs = recorder.logs()
        assertEquals(2, logs.size)
        assertEquals(listOf(1, 1), logs.map { it.trimEnd().lines().size })
        // No rollover before the limit is reached again.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Recording.LIMIT_MS - 1))
        recorder.tap("next")
        assertEquals(2, recorder.names().size)
    }

    @Test fun startPrunesByCountKeepingTheNewest() = runBlocking {
        val recorder = recorder(config(sessions = 3))
        val old = List(5) { session(recorder.dir, ageMs = (5 - it) * 1_000L * 60) }
        recorder.start()
        assertEquals(old.takeLast(3).toSet(), recorder.names())
    }

    @Test fun startPrunesByAge() = runBlocking {
        val recorder = recorder(config(ageDays = 14))
        val stale = session(recorder.dir, ageMs = 15 * day)
        val fresh = session(recorder.dir, ageMs = 13 * day)
        recorder.start()
        val names = recorder.names()
        assertFalse(stale in names)
        assertTrue(fresh in names)
    }

    @Test fun startPrunesBySizeOldestFirst() = runBlocking {
        val recorder = recorder(config(bytes = 1_000))
        val oldest = session(recorder.dir, ageMs = 3_000_000, bytes = 400)
        val middle = session(recorder.dir, ageMs = 2_000_000, bytes = 400)
        val newest = session(recorder.dir, ageMs = 1_000_000, bytes = 400)
        recorder.start()
        assertEquals(setOf(middle, newest), recorder.names())
        assertFalse(oldest in recorder.names())
    }

    @Test fun retentionNeverDeletesTheCurrentSessionAndRunsOnRollover() = runBlocking {
        val recorder = recorder(config(sessions = 2, bytes = 1))
        recorder.start()
        recorder.tap("next")
        val current = recorder.logs().single()
        assertTrue(current.isNotEmpty())
        val closed = List(3) { session(recorder.dir, ageMs = (it + 1) * 1_000L) }
        recorder.prune()
        val names = recorder.names()
        assertEquals(1, names.size) // the size cap of one byte removes every closed session, never the current one
        assertTrue(closed.none { it in names })
        recorder.tap("back")
        assertEquals(2, recorder.logs().single().trimEnd().lines().size)
        // Rollover prunes too: a stale session disappears when the workout changes.
        val stale = session(recorder.dir, ageMs = 20 * day)
        recorder.workoutChanged("next-workout")
        assertFalse(stale in recorder.names())
    }

    @Test fun staleShareArchivesAndReceiveFoldersAreRemoved() = runBlocking {
        val recorder = recorder()
        val shared = File(recorder.dir, "shared").apply { mkdirs() }
        val zip = File(shared, "old.zip").apply { writeText("z"); setLastModified(System.currentTimeMillis() - 2 * day) }
        val fresh = File(shared, "fresh.zip").apply { writeText("z") }
        val staging = File(recorder.dir, ".receiving-1").apply { mkdirs(); setLastModified(System.currentTimeMillis() - 2 * day) }
        recorder.start()
        recorder.names()
        assertFalse(zip.exists())
        assertTrue(fresh.exists())
        assertFalse(staging.exists())
    }

    @Test fun pruningNeverBreaksRecordingWhenTheStorageIsGone() = runBlocking {
        val recorder = recorder()
        recorder.dir.writeText("not a folder") // root is a plain file: every disk operation fails
        recorder.start()
        recorder.tap("next")
        recorder.prune()
        recorder.workoutChanged("a")
        recorder.tap("back")
        assertTrue(recorder.names().isEmpty())
        assertTrue(recorder.recording.value)
    }

    private fun capturing(recorder: Recorder): () -> Int {
        var requests = 0
        recorder.screenshot = ScreenCapture { _ -> requests++; CompletableDeferred<android.graphics.Bitmap?>(null) }
        return { requests }
    }

    @Test fun imageBudgetCapsShotsPerSessionButNotEvents() = runBlocking {
        val recorder = recorder(config(interval = 0, shots = 2))
        val requests = capturing(recorder)
        recorder.start()
        for (screen in listOf("home", "plans", "history", "progress", "settings")) recorder.screen(screen)
        assertEquals(2, requests())
        assertEquals(5, recorder.logs().single().trimEnd().lines().size)
        // A new session gets a fresh budget.
        recorder.workoutChanged("next-workout")
        recorder.screen("training")
        recorder.screen("pause")
        assertEquals(4, requests())
    }

    @Test fun noShotsWhileNotAllowedAndTheBudgetIsKept() = runBlocking {
        val recorder = Recorder(config(interval = 0, shots = 2), shots = false)
            .also { it.install(ApplicationProvider.getApplicationContext()) }
        val requests = capturing(recorder)
        recorder.start()
        recorder.screen("home")
        recorder.screen("plans")
        assertEquals(0, requests())
        recorder.shots = true
        recorder.screen("history")
        recorder.screen("progress")
        recorder.screen("settings")
        assertEquals(2, requests())
        assertEquals(5, recorder.logs().single().trimEnd().lines().size)
    }

    @Test fun exportSnapshotKeepsTheCurrentSessionRecording() = runBlocking {
        val recorder = recorder()
        recorder.start()
        recorder.tap("next")
        val archive = File(recorder.dir.parentFile, "export.zip")
        recorder.archive(archive)
        assertTrue(recorder.recording.value)
        recorder.tap("back")
        val logs = recorder.logs()
        assertEquals(1, logs.size)
        assertEquals(2, logs.single().trimEnd().lines().size)
    }

    @Test fun transferClosesTheSessionAndRecordingContinuesInANewOne() = runBlocking {
        val recorder = recorder()
        recorder.start()
        recorder.tap("next")
        val sent = recorder.transfer()
        assertEquals(1, sent.size)
        assertTrue(recorder.recording.value)
        recorder.tap("back")
        val names = recorder.names()
        assertEquals(2, names.size)
        assertEquals(sent.toSet(), names.intersect(sent.toSet()))
        assertEquals(names, recorder.transfer().toSet()) // the next transfer closes the second one as well
        assertTrue(recorder.recording.value)
    }

    @Test fun deleteClosedKeepsTheCurrentSession() = runBlocking {
        val recorder = recorder()
        recorder.start()
        recorder.tap("next")
        recorder.workoutChanged("a")
        recorder.tap("back")
        recorder.workoutChanged("b")
        recorder.tap("done")
        assertEquals(3, recorder.names().size)
        assertEquals(2, recorder.deleteClosedSessions())
        assertEquals(1, recorder.names().size)
        recorder.tap("save")
        assertEquals(2, recorder.logs().single().trimEnd().lines().size)
    }
}
