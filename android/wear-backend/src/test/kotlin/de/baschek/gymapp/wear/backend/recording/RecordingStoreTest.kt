package de.baschek.gymapp.wear.backend.recording

import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.health.RecordingProfile
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingStoreTest {
    @Test fun recordingSurvivesNewStoreAndCanBeCleared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RecordingStore(context)
        store.clear()
        val recording = LocalRecording("synthetic", 100, RecordingStatus.ACTIVE, 10, RecordingProfile.PERSONAL,
            listOf(LocalSample(101, 80.0, true), LocalSample(103, 90.0, false)))
        store.write(recording)
        assertEquals(recording, RecordingStore(context).read())
        store.write(recording.copy(status = RecordingStatus.ENDED, endedAtMillis = 104))
        assertEquals(RecordingStatus.ENDED, RecordingStore(context).read()?.status)
        store.clear()
        assertNull(RecordingStore(context).read())
    }

    @Test fun appendKeepsPrefixAndIgnoresInterruptedTailAcrossRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RecordingStore(context)
        store.clear()
        val first = LocalRecording("journal", 100, RecordingStatus.ACTIVE, 10, RecordingProfile.PERSONAL,
            samples = listOf(LocalSample(101, 80.0, true)))
        store.write(first)
        fun journals() = context.noBackupFilesDir.listFiles()!!.filter { it.name.startsWith("wear-heart-samples-") }
        val original = journals().single()
        val prefix = original.readBytes()
        // Simulate process death after appending bytes but before publishing the checkpoint.
        original.appendBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(first, RecordingStore(context).read())
        val second = first.copy(samples = first.samples + LocalSample(102, 90.0, false))
        RecordingStore(context).write(second)
        assertEquals(original.name, journals().single().name)
        assertArrayEquals(prefix, original.readBytes().copyOf(prefix.size))
        assertEquals(34L, original.length())
        assertEquals(second, RecordingStore(context).read())
        store.clear()
    }

    @Test fun earlierSampleUsesNewGenerationAndMissingCommittedDataFails() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RecordingStore(context)
        store.clear()
        val first = LocalRecording("journal", 100, RecordingStatus.ACTIVE, 10, RecordingProfile.PERSONAL,
            samples = listOf(LocalSample(103, 80.0, true)))
        store.write(first)
        val old = context.noBackupFilesDir.listFiles()!!.single { it.name.startsWith("wear-heart-samples-") }
        val second = first.copy(samples = listOf(LocalSample(101, 90.0, false)) + first.samples)
        store.write(second)
        val current = context.noBackupFilesDir.listFiles()!!.single { it.name.startsWith("wear-heart-samples-") }
        assertNotEquals(old.name, current.name)
        assertEquals(second, RecordingStore(context).read())
        java.io.RandomAccessFile(current, "rw").use { it.setLength(3) }
        assertThrows(IllegalStateException::class.java) { RecordingStore(context).read() }
        store.clear()
    }

    @Test fun unrelatedOrTerminalRecordingCannotBeOverwritten() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RecordingStore(context)
        store.clear()
        val first = LocalRecording("one", 100, RecordingStatus.ACTIVE, 10, RecordingProfile.PERSONAL)
        store.write(first)
        assertThrows(IllegalStateException::class.java) { store.write(first.copy(sessionId = "two")) }
        assertThrows(IllegalStateException::class.java) { store.write(first.copy(profile = RecordingProfile.TEST)) }
        val ended = first.copy(status = RecordingStatus.ENDED, endedAtMillis = 200)
        store.write(ended)
        assertThrows(IllegalStateException::class.java) { store.write(ended.copy(status = RecordingStatus.ACTIVE)) }
        assertEquals(ended, RecordingStore(context).read())
        store.clear()
    }

    @Test fun outOfOrderAndRepeatedUpdatesPreserveFirstReading() {
        val previous = listOf(LocalSample(5, 100.0, true))
        val received = listOf(LocalSample(7, 101.0, false), LocalSample(5, 120.0, true), LocalSample(3, 99.0, false))
        assertEquals(listOf(3L, 5L, 7L), mergeSamples(previous, received).map { it.timeMillis })
        assertEquals(100.0, mergeSamples(previous, received)[1].bpm, 0.0)
    }

    @Test fun testProfileCannotBeExported() {
        val recording = LocalRecording("test", 100, RecordingStatus.ENDED, 10, RecordingProfile.TEST,
            endedAtMillis = 200)
        assertThrows(IllegalArgumentException::class.java) { recording.toHeartRecording() }
    }

    @Test fun cachedSampleSplitsGapAtItsTimestamp() {
        val recording = LocalRecording("personal", 100, RecordingStatus.ENDED, 10, RecordingProfile.PERSONAL,
            samples = listOf(LocalSample(150, 90.0, true)), endedAtMillis = 200,
            gaps = listOf(LocalGap(120, 180)))
        val result = recording.toHeartRecording()
        assertEquals(listOf(120L to 150L, 150L to 180L), result.gaps.map { it.startMillis to it.endMillis })
    }

    @Test fun corruptDataIsNotMistakenForMissingRecording() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RecordingStore(context)
        store.clear()
        java.io.File(context.noBackupFilesDir, "wear-heart-recording.json").writeText("not json")
        assertThrows(Exception::class.java) { RecordingStore(context).read() }
        store.clear()
    }
}
