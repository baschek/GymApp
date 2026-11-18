package de.baschek.gymapp.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.health.HeartRecording
import de.baschek.gymapp.core.health.HeartSample
import de.baschek.gymapp.core.health.RecordingProfile
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AutomaticHealthExportTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val completed = Session(status = SessionStatus.COMPLETED, startedAt = "2026-01-01T00:00:00Z", completedAt = "2026-01-01T01:00:00Z", durationSeconds = 3600)

    @Test fun `one explicit consent covers subsequent completed sessions and can be revoked`() = runTest {
        val consent = HealthExportConsent(context)
        consent.revoke()
        assertFalse(consent.allows(completed))
        consent.setEnabled(true, 1767225600000L)
        assertTrue(consent.allows(completed))
        assertFalse(consent.allows(completed.copy(status = SessionStatus.ACTIVE)))
        assertFalse(consent.allows(completed.copy(completedAt = null)))
        assertFalse(consent.allows(completed.copy(completedAt = "2025-12-31T23:59:59Z")))
        consent.revoke()
        assertFalse(consent.allows(completed))
    }

    @Test fun `completion without pulse creates version zero and later watch data supersedes without duplicate receipt`() = runTest {
        val id = UUID.randomUUID().toString()
        val empty = requireNotNull(recordingForCompleted(context, id, completed))
        assertEquals(0L, empty.version)
        assertTrue(empty.samples.isEmpty())
        val receipts = HealthExportReceipts(context)
        assertFalse(receipts.isCurrent(id, empty))
        receipts.record(id, empty)
        assertTrue(receipts.isCurrent(id, empty))
        val watch = empty.copy(version = 1, samples = listOf(HeartSample(empty.startMillis + 500, 92)))
        PrivateHeartStore(context).save(watch)
        assertFalse(receipts.isCurrent(id, watch))
        receipts.record(id, watch)
        assertTrue(receipts.isCurrent(id, watch))
        assertEquals(watch, recordingForCompleted(context, id, completed))
        assertNull(recordingForCompleted(context, id, completed.copy(status = SessionStatus.ACTIVE)))
    }

    @Test fun `concurrent empty creation never overwrites watch data and test profile stays separate`() = runTest {
        val id = UUID.randomUUID().toString()
        val start = 1000L
        val store = PrivateHeartStore(context)
        val watch = HeartRecording(RecordingProfile.PERSONAL, id, start, start + 1000, 1, listOf(HeartSample(start + 100, 80)))
        listOf(async { store.ensureEmpty(RecordingProfile.PERSONAL, id, start, start + 1000) }, async { store.save(watch) }).awaitAll()
        assertEquals(watch, store.read(RecordingProfile.PERSONAL, id))
        assertNull(store.read(RecordingProfile.TEST, id))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AutomaticHealthCompletionObserverTest {
    @Test fun `completion schedules once after consent and test profile cannot schedule`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, de.baschek.gymapp.data.db.GymDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = de.baschek.gymapp.data.GymRepository(db)
        val consent = HealthExportConsent(context)
        consent.revoke()
        context.getSharedPreferences("health_export_receipts", Context.MODE_PRIVATE).edit().clear().commit()
        consent.setEnabled(true, 1767225600000L)
        val id = UUID.randomUUID().toString()
        val active = Session(status = SessionStatus.ACTIVE, gymId = "gym", startedAt = "2026-01-01T00:00:00Z")
        repo.syncStore.applyRemote(listOf(de.baschek.gymapp.core.model.Document(id, "session", 1,
            "2026-01-01T00:00:00Z", false, "server", de.baschek.gymapp.core.model.GymJson.encodeToJsonElement(Session.serializer(), active).let { it as kotlinx.serialization.json.JsonObject })))
        val scheduled = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val job = AutomaticHealthExport(context, repo, schedule = { scheduled.add(it) }, scheduleDelete = {}).start(scope)
        try {
            repo.updateSession(id) { it.copy(status = SessionStatus.COMPLETED, completedAt = "2026-01-01T01:00:00Z", durationSeconds = 3600) }
            kotlinx.coroutines.withTimeout(5000) { while (scheduled.isEmpty()) kotlinx.coroutines.delay(20) }
            assertEquals(listOf(id), scheduled.toList())
            repo.updateSession(id) { it.copy(notes = "cosmetic") }
            kotlinx.coroutines.delay(150)
            assertEquals(listOf(id), scheduled.toList())
            repo.setProfile(de.baschek.gymapp.data.Profile.TEST)
            repo.seedDemoData()
            val testId = repo.startSession("demo-plan", gymId = "demo-gym")!!
            repo.finishSession(testId)
            kotlinx.coroutines.delay(150)
            assertEquals(listOf(id), scheduled.toList())
            consent.revoke()
        } finally { job.cancel(); db.close() }
    }
}
