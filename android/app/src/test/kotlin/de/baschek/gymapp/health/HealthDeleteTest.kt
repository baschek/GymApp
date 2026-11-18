package de.baschek.gymapp.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.model.Document
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HealthDeleteTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeDeleter : ExportDeleter {
        var fail = false
        val calls = mutableListOf<Pair<List<String>, List<String>>>()
        override suspend fun delete(exerciseClientIds: List<String>, heartClientIds: List<String>) {
            calls += exerciseClientIds to heartClientIds
            if (fail) error("simulated Health Connect failure")
        }
    }

    private object NoopWriter : ExportWriter {
        override suspend fun write(records: List<androidx.health.connect.client.records.Record>) {}
        override suspend fun ownHeartRecords(start: Long, end: Long) = emptyList<OwnedHeart>()
        override suspend fun deleteHeart(ids: List<String>) {}
    }

    private fun checkpoint(id: String) =
        File(File(context.noBackupFilesDir, "heart-export-checkpoints"), UUID.nameUUIDFromBytes(id.toByteArray()).toString())

    @Test fun `delete uses export client ids, clears receipt and checkpoint, and a failure keeps them for retry`() = runTest {
        val id = UUID.randomUUID().toString()
        val recording = HeartRecording(RecordingProfile.PERSONAL, id, 0, 10000, 1, emptyList())
        exportCurrent(context, recording, NoopWriter, restrictedHistory = false)
        val receipts = HealthExportReceipts(context)
        receipts.record(id, recording)
        assertTrue(checkpoint(id).exists())
        val deleter = FakeDeleter().apply { fail = true }
        try { deleteExportedCurrent(context, id, deleter); throw AssertionError("expected failure") } catch (_: IllegalStateException) { }
        assertTrue(receipts.has(id))
        assertTrue(checkpoint(id).exists())
        deleter.fail = false
        deleteExportedCurrent(context, id, deleter)
        assertEquals(2, deleter.calls.size)
        val stable = UUID.nameUUIDFromBytes(id.toByteArray()).toString()
        assertEquals(listOf("gymapp-exercise-$stable"), deleter.calls.last().first)
        assertTrue("gymapp-heart-$stable-0" in deleter.calls.last().second)
        assertFalse(receipts.has(id))
        assertFalse(id in receipts.exportedIds())
        assertFalse(checkpoint(id).exists())
    }

    private fun sessionDoc(id: String, rev: Long, deleted: Boolean = false): Document {
        val session = Session(status = SessionStatus.COMPLETED, gymId = "gym", startedAt = "2026-01-01T00:00:00Z",
            completedAt = "2026-01-01T01:00:00Z", durationSeconds = 3600)
        val data = if (deleted) JsonObject(emptyMap()) else GymJson.encodeToJsonElement(Session.serializer(), session) as JsonObject
        return Document(id, "session", rev, "2026-01-0${rev}T00:00:00Z", deleted, "server", data)
    }

    private suspend fun runTombstone(exported: Boolean, consent: Boolean): List<String> {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, de.baschek.gymapp.data.db.GymDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = de.baschek.gymapp.data.GymRepository(db)
        val settings = HealthExportConsent(context)
        settings.revoke()
        if (consent) settings.setEnabled(true, 1767225600000L)
        val id = UUID.randomUUID().toString()
        if (exported) HealthExportReceipts(context).record(id, HeartRecording(RecordingProfile.PERSONAL, id, 0, 1000, 0, emptyList()))
        val deletes = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            repo.syncStore.applyRemote(listOf(sessionDoc(id, 1)))
            AutomaticHealthExport(context, repo, schedule = {}, scheduleDelete = { deletes.add(it) }).start(scope)
            kotlinx.coroutines.delay(300)
            assertTrue(deletes.isEmpty())
            repo.syncStore.applyRemote(listOf(sessionDoc(id, 2, deleted = true)))
            kotlinx.coroutines.delay(500)
            return deletes.toList().map { if (it == id) "DELETE" else it }
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); settings.revoke(); db.close() }
    }

    @Test fun `tombstone of an exported session schedules exactly one delete`() = kotlinx.coroutines.runBlocking {
        assertEquals(listOf("DELETE"), runTombstone(exported = true, consent = true))
    }

    @Test fun `tombstone of a session without receipt schedules nothing`() = kotlinx.coroutines.runBlocking {
        assertEquals(emptyList<String>(), runTombstone(exported = false, consent = true))
    }

    @Test fun `tombstone without consent schedules nothing`() = kotlinx.coroutines.runBlocking {
        assertEquals(emptyList<String>(), runTombstone(exported = true, consent = false))
    }
}
