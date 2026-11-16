package de.baschek.gymapp

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.Document
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.wear.events.ConflictChoice
import de.baschek.gymapp.core.wear.events.EventResult
import de.baschek.gymapp.core.wear.events.WorkoutEvent
import de.baschek.gymapp.core.wear.events.WorkoutProfile
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.data.db.GymDatabase
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WatchRepositoryTest {
    private lateinit var db: GymDatabase
    private lateinit var repo: GymRepository
    private val sessionId = UUID.randomUUID().toString()
    private val base = Session(gymId = "gym", status = SessionStatus.ACTIVE, startedAt = "2026-01-01T00:00:00Z",
        exercises = listOf(SessionExercise(id = "exercise", exerciseId = "machine", name = "Machine",
            target = Target(metric = "reps", min = 8.0, max = 12.0),
            sets = listOf(SetLog(id = "set-a", order = 1), SetLog(id = "set-b", order = 2)))))

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java).allowMainThreadQueries().build()
        repo = GymRepository(db)
    }
    @After fun tearDown() = db.close()
    private fun raw(session: Session): JsonObject = GymJson.encodeToJsonElement(Session.serializer(), session).jsonObject
    private fun event(before: Session, after: Session, profile: WorkoutProfile = WorkoutProfile.PERSONAL) = WorkoutEvent(
        UUID.randomUUID().toString(), "watch-test", 2_000_000_000_000L, profile, sessionId, raw(before), raw(after))
    private fun set(session: Session, index: Int, weight: Double, time: String) = session.copy(exercises = session.exercises.map { ex ->
        ex.copy(sets = ex.sets.mapIndexed { i, value -> if (i == index) value.copy(status = SetStatus.COMPLETED,
            weightKg = weight, reps = 10, effort = "two_three", completedAt = time) else value })
    })
    private fun finish(session: Session): Session {
        val first = session.exercises.single().sets.first()
        val both = if (session.exercises.single().sets[1].status == SetStatus.PENDING)
            set(session, 1, first.weightKg ?: 30.0, "2026-01-01T00:03:00Z") else session
        return both.copy(status = SessionStatus.COMPLETED, completedAt = "2026-01-01T00:10:00Z", durationSeconds = 600)
    }
    private suspend fun start() { assertTrue(repo.importWatchEvent(event(base, base)) is EventResult.Applied) }

    @Test fun duplicateSameSetAndReplayDoNotDoubleCount() = runBlocking {
        start()
        val phone = set(base, 0, 30.0, "2026-01-01T00:01:00Z")
        repo.updateSession(sessionId) { phone }
        val watch = set(base, 0, 30.0, "2026-01-01T00:01:01Z")
        val operation = event(base, watch)
        assertTrue(repo.importWatchEvent(operation) is EventResult.Applied)
        assertTrue(repo.importWatchEvent(operation) is EventResult.Replayed)
        assertEquals(1, repo.getSession(sessionId)!!.exercises.single().sets.count { it.status == SetStatus.COMPLETED })
        assertTrue(repo.observeWatchConflicts().first().isEmpty())
    }

    @Test fun offlineDifferentSetsMergeWithoutDiscardingPhoneChange() = runBlocking {
        start()
        repo.updateSession(sessionId) { set(it, 0, 30.0, "2026-01-01T00:01:00Z") }
        val watch = set(base, 1, 35.0, "2026-01-01T00:02:00Z")
        assertTrue(repo.importWatchEvent(event(base, watch)) is EventResult.Applied)
        assertEquals(listOf(30.0, 35.0), repo.getSession(sessionId)!!.exercises.single().sets.map { it.weightKg })
    }

    @Test fun completedConflictDefersProgressionUntilResolution() = runBlocking {
        start()
        repo.updateSession(sessionId) { set(it, 0, 30.0, "2026-01-01T00:01:00Z") }
        val watch = finish(set(set(base, 0, 40.0, "2026-01-01T00:01:01Z"), 1, 30.0, "2026-01-01T00:03:00Z"))
        assertTrue(repo.importWatchEvent(event(base, watch)) is EventResult.Applied)
        assertTrue(repo.observeWatchConflicts().first().isNotEmpty())
        assertTrue(repo.observeMachineProfiles().first().isEmpty())
        assertTrue(repo.resolveWatchConflicts(sessionId, ConflictChoice.MINE))
        assertTrue(repo.observeWatchConflicts().first().isEmpty())
        assertTrue(repo.observeMachineProfiles().first().isNotEmpty())
    }

    @Test fun finishUndoAndRefinishRecalculateWeightsAndMilestones() = runBlocking {
        start()
        val finished = finish(set(base, 0, 30.0, "2026-01-01T00:01:00Z"))
        assertTrue(repo.importWatchEvent(event(base, finished)) is EventResult.Applied)
        assertEquals(30.0, repo.observeMachineProfiles().first().single().value.workingWeightKg!!, 0.01)
        assertTrue(repo.observeMilestones().first().isNotEmpty())
        val reopened = base
        assertTrue(repo.importWatchEvent(event(finished, reopened)) is EventResult.Applied)
        assertNull(repo.observeMachineProfiles().first().single().value.workingWeightKg)
        assertTrue(repo.observeMilestones().first().isEmpty())
        val again = finish(set(reopened, 0, 35.0, "2026-01-01T00:02:00Z"))
        assertTrue(repo.importWatchEvent(event(reopened, again)) is EventResult.Applied)
        assertEquals(35.0, repo.observeMachineProfiles().first().single().value.workingWeightKg!!, 0.01)
    }

    @Test fun noPhoneEventsAccumulateUntilTrustedWatchHandshake() = runBlocking {
        repo.syncStore.applyRemote(listOf(Document(sessionId, "session", 1, "2026-01-01T00:00:00Z", false, "server", raw(base))))
        repo.updateSession(sessionId) { it.copy(notes = "phone edit") }
        assertTrue(repo.observePhoneWatchEvents().first().isEmpty())
        repo.enableWatchCompanion()
        val initial = repo.observePhoneWatchEvents().first()
        assertEquals(1, initial.size)
        assertEquals(initial.single().before, initial.single().after)
        repo.updateSession(sessionId) { it.copy(notes = "another edit") }
        assertEquals(2, repo.observePhoneWatchEvents().first().size)
        repo.enableWatchCompanion()
        assertEquals(2, repo.observePhoneWatchEvents().first().size)
    }

    @Test fun testProfileCannotImportOrExportCatalog() = runBlocking {
        repo.setProfile(Profile.TEST)
        assertTrue(repo.importWatchEvent(event(base, base, WorkoutProfile.TEST)) is EventResult.Rejected)
        assertNull(repo.watchCatalog())
        assertNull(repo.getSession(sessionId))
    }
}
