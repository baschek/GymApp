package de.baschek.gymapp.health

import android.content.Context
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.HeartRateRecord
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.HeartSample
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExportCheckpointTest {
    @Test fun `partial delete failure retries its fenced version and stale exports are rejected`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val writer = FakeWriter()
        val initial = HeartRecording(RecordingProfile.PERSONAL, id, 0, 10000, 1, listOf(HeartSample(1000, 90)))
        assertEquals(HealthExportResult.Exported, exportCurrent(context, initial, writer, restrictedHistory = false))
        val empty = initial.copy(version = 2, samples = emptyList())
        writer.failDelete = true
        try {
            exportCurrent(context, empty, writer, restrictedHistory = false)
            throw AssertionError("Expected delete failure")
        } catch (_: IllegalStateException) { }
        writer.failDelete = false
        assertEquals(HealthExportResult.Exported, exportCurrent(context, empty, writer, restrictedHistory = false))
        assertTrue(writer.deleted.flatten().all { it.startsWith("gymapp-heart-") })
        val calls = writer.writes
        assertEquals(HealthExportResult.RecordingChanged, exportCurrent(context, initial, writer, restrictedHistory = false))
        assertEquals(HealthExportResult.RecordingChanged, exportCurrent(context, empty.copy(samples = listOf(HeartSample(2000, 92))), writer, restrictedHistory = false))
        assertEquals(calls, writer.writes)
    }

    @Test fun `interrupted long export followed by shorter version clears trailing chunks`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val writer = FakeWriter()
        val id = UUID.randomUUID().toString()
        val long = HeartRecording(RecordingProfile.PERSONAL, id, 0, 3000, 1, (0..2000).map { HeartSample(it.toLong(), 90) })
        writer.failWriteAfterPersist = true
        try {
            exportCurrent(context, long, writer, restrictedHistory = false)
            throw AssertionError("Expected simulated interruption")
        } catch (_: IllegalStateException) { }
        writer.failWriteAfterPersist = false
        val shorter = long.copy(version = 2, samples = long.samples.take(10))
        assertEquals(HealthExportResult.Exported, exportCurrent(context, shorter, writer, restrictedHistory = false))
        assertTrue(writer.deleted.flatten().any { it.endsWith("-1") })
        assertEquals(HealthExportResult.RecordingChanged, exportCurrent(context, long, writer, restrictedHistory = false))
        assertTrue(writer.batchSizes.all { it <= 10 })
    }

    @Test fun `older own exports on Android 13 fail before any provider write`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val writer = FakeWriter()
        val recording = HeartRecording(RecordingProfile.PERSONAL, UUID.randomUUID().toString(), 0, 1000, 1, emptyList())
        assertEquals(HealthExportResult.UnsupportedHistory, exportCurrent(context, recording, writer,
            restrictedHistory = true, nowMillis = 31L * 24 * 60 * 60 * 1000))
        assertEquals(0, writer.writes)
    }

    @Test fun `malformed checkpoint fails closed before any provider write`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val name = UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString()
        val directory = java.io.File(context.noBackupFilesDir, "heart-export-checkpoints").apply { mkdirs() }
        java.io.File(directory, name).writeText("x".repeat(1025))
        val writer = FakeWriter()
        try {
            exportCurrent(context, HeartRecording(RecordingProfile.PERSONAL, id, 0, 1000, 1, emptyList()), writer, restrictedHistory = false)
            throw AssertionError("Expected invalid checkpoint rejection")
        } catch (_: IllegalStateException) { }
        assertEquals(0, writer.writes)
    }

    private class FakeWriter : ExportWriter {
        var failDelete = false
        var failWriteAfterPersist = false
        var writes = 0
        val deleted = mutableListOf<List<String>>()
        val batchSizes = mutableListOf<Int>()
        val stored = mutableMapOf<String, OwnedHeart>()
        override suspend fun write(records: List<Record>) {
            writes++; batchSizes += records.size
            records.filterIsInstance<HeartRateRecord>().forEach {
                val id = requireNotNull(it.metadata.clientRecordId)
                stored[id] = OwnedHeart(id, id)
            }
            if (failWriteAfterPersist) error("simulated interruption")
        }
        override suspend fun ownHeartRecords(start: Long, end: Long): List<OwnedHeart> = stored.values.toList()
        override suspend fun deleteHeart(ids: List<String>) {
            if (failDelete) error("simulated failure")
            deleted.add(ids)
            ids.forEach { stored.remove(it) }
        }
    }
}
