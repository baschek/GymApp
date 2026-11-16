package de.baschek.gymapp.watch.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.HeartSample
import de.baschek.gymapp.core.health.RecordingProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HeartQueueTest {
    @Test fun recordingsSurviveRecreationAndOnlyMatchingAckRemovesOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queue = HeartQueue(context)
        val first = HeartRecording(RecordingProfile.PERSONAL, "one", 100, 300, 1, listOf(HeartSample(200, 80)))
        val second = first.copy(sessionId = "two")
        queue.stage(first)
        queue.stage(second)
        assertEquals(2, HeartQueue(context).pending().size)
        HeartQueue(context).acknowledge(HeartTransfer.id("one"), 2)
        assertEquals(2, queue.pending().size)
        HeartQueue(context).acknowledge(HeartTransfer.id("one"), 1)
        assertEquals(1, queue.pending().size)
        queue.acknowledge(HeartTransfer.id("two"), 1)
    }
    @Test fun phoneStatusIsVersionedAndSeparatedFromTestProfile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = HeartSummaryStore(context)
        val id = java.util.UUID.randomUUID().toString()
        store.updateStatus(id, 2, "EXPORTED")
        store.updateStatus(id, 1, "PENDING")
        assertEquals("EXPORTED", HeartSummaryStore(context).read(RecordingProfile.PERSONAL, id)?.exportState)
        assertNull(store.read(RecordingProfile.TEST, id))
    }
    @Test fun testProfileCannotEnterTransferQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recording = HeartRecording(RecordingProfile.TEST, "test", 100, 300, 1, emptyList())
        assertThrows(IllegalArgumentException::class.java) { HeartQueue(context).stage(recording) }
    }
}
