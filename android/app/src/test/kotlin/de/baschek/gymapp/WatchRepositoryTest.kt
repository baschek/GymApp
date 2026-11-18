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

    private suspend fun orphanConflict(tombstone: Boolean) {
        start()
        repo.updateSession(sessionId) { set(it, 0, 30.0, "2026-01-01T00:01:00Z") }
        val watch = finish(set(base, 0, 40.0, "2026-01-01T00:01:01Z"))
        assertTrue(repo.importWatchEvent(event(base, watch)) is EventResult.Applied)
        assertEquals(1, repo.observeWatchConflicts().first().size)
        val dao = db.dao()
        val row = dao.getDoc(Profile.PERSONAL, "session", sessionId)!!
        if (tombstone) dao.upsertDoc(row.copy(deleted = true, data = "{}"))
        else dao.deleteDoc(Profile.PERSONAL, "session", sessionId)
    }

    @Test fun choosingMineAfterDeletionDismissesStaleNotice() = runBlocking {
        orphanConflict(tombstone = true)
        assertTrue(repo.resolveWatchConflicts(sessionId, ConflictChoice.MINE))
        assertTrue(repo.observeWatchConflicts().first().isEmpty())
        assertTrue(db.dao().getDoc(Profile.PERSONAL, "session", sessionId)!!.deleted)
        assertNull(db.dao().getSetting("watch_progression_pending:$sessionId"))
    }

    @Test fun startupRemovesLegacyConflictWithMissingSession() = runBlocking {
        orphanConflict(tombstone = false)
        val restarted = GymRepository(db)
        restarted.cleanOrphanWatchConflicts()
        assertTrue(restarted.observeWatchConflicts().first().isEmpty())
        assertNull(db.dao().getSetting("watch_progression_pending:$sessionId"))
        assertNull(restarted.getSession(sessionId))
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

    @Test fun phoneHandoffAppliesReversedChainOnceAndLeavesMissingPredecessorPending() = runBlocking {
        val logged = set(base, 0, 30.0, "2026-01-01T00:01:00Z")
        val first = event(base, logged)
        // The predecessor establishes order even if clocks or callback order disagree.
        val last = event(logged, finish(logged)).copy(predecessorEventId = first.eventId,
            occurredAtMillis = first.occurredAtMillis + 1)
        assertTrue(repo.importWatchEvents(listOf(last)).acknowledgements.isEmpty())
        assertNull(repo.getSession(sessionId))
        val result = repo.importWatchEvents(listOf(last, first))
        assertEquals(listOf(first.eventId, last.eventId), result.acknowledgements)
        assertEquals(last.eventId, result.lastLeader?.lastOperation?.eventId)
        assertEquals(SessionStatus.COMPLETED, repo.getSession(sessionId)!!.status)
        assertTrue(repo.lastSessionEditFromWatch(sessionId))
        val milestones = repo.observeMilestones().first()
        assertEquals(0, repo.importWatchEvents(listOf(first, last)).appliedCount)
        assertEquals(milestones, repo.observeMilestones().first())
    }

    @Test fun rejectedHeadIsAcknowledgedAndItsSuccessorSurvivesRestart() = runBlocking {
        val rejected = event(base, base.copy(gymId = null))
        val successor = event(base, finish(set(base, 0, 30.0, "2026-01-01T00:01:00Z"))).copy(
            predecessorEventId = rejected.eventId, occurredAtMillis = rejected.occurredAtMillis + 1)
        val firstResult = repo.importWatchEvents(listOf(rejected))
        assertEquals(listOf(rejected.eventId), firstResult.acknowledgements)
        assertEquals(0, firstResult.appliedCount)
        assertNull(repo.getSession(sessionId))

        val restarted = GymRepository(db)
        val result = restarted.importWatchEvents(listOf(successor))
        assertEquals(listOf(successor.eventId), result.acknowledgements)
        assertEquals(SessionStatus.COMPLETED, restarted.getSession(sessionId)!!.status)
        assertEquals(0, restarted.importWatchEvents(listOf(rejected, successor)).appliedCount)
    }

    @Test fun exceptionRollsBackOnlyItsOwnEventAndPreservesItForRetry() = runBlocking {
        start()
        var failOnce = true
        // enqueue needs an ID after the session row is written.
        repo = GymRepository(db, newId = {
            if (failOnce) {
                failOnce = false
                error("synthetic outbox failure")
            }
            UUID.randomUUID().toString()
        })
        val withNote = base.copy(notes = "watch note")
        val failed = event(base, withNote)
        val independent = event(base, set(base, 0, 30.0, "2026-01-01T00:01:00Z"))
            .copy(occurredAtMillis = failed.occurredAtMillis + 1)
        val dependent = event(withNote, withNote.copy(notes = "corrected watch note")).copy(
            predecessorEventId = failed.eventId, occurredAtMillis = failed.occurredAtMillis + 2)
        val result = repo.importWatchEvents(listOf(dependent, independent, failed))
        assertEquals(listOf(independent.eventId), result.acknowledgements)
        assertNull(repo.getSession(sessionId)!!.notes)
        assertEquals(SetStatus.COMPLETED, repo.getSession(sessionId)!!.exercises.single().sets.first().status)

        val retry = repo.importWatchEvents(listOf(dependent, failed))
        assertEquals(listOf(failed.eventId, dependent.eventId), retry.acknowledgements)
        assertEquals("corrected watch note", repo.getSession(sessionId)!!.notes)
    }

    @Test fun phoneBurstAndCrossEditsStillAllowWatchToFinish() = runBlocking {
        val exercise = base.exercises.single()
        val initial = base.copy(exercises = listOf(exercise.copy(sets = exercise.sets +
            listOf(SetLog(id = "set-c", order = 3), SetLog(id = "set-d", order = 4)))))
        val started = event(initial, initial)
        assertEquals(listOf(started.eventId), repo.importWatchEvents(listOf(started)).acknowledgements)
        repo.enableWatchCompanion(peerEventProtocol = 2)
        repo.updateSession(sessionId) { set(it, 0, 30.0, "2026-01-01T00:01:00Z") }
        repo.updateSession(sessionId) { set(it, 1, 30.0, "2026-01-01T00:01:10Z") }
        val phoneBurst = repo.observePhoneWatchEvents().first()
        assertEquals(2, phoneBurst.size)
        assertEquals(phoneBurst.first().eventId, phoneBurst.last().predecessorEventId)
        val handedOff = repo.getSession(sessionId)!!
        for (operation in phoneBurst) repo.acknowledgePhoneWatchEvent(operation.eventId)

        repo.updateSession(sessionId) { set(it, 0, 32.0, "2026-01-01T00:01:20Z") }
        val phoneCorrection = repo.observePhoneWatchEvents().first().single()
        val watchCorrection = set(handedOff, 0, 31.0, "2026-01-01T00:01:21Z")
        val withThird = set(watchCorrection, 2, 30.0, "2026-01-01T00:02:00Z")
        val withFourth = set(withThird, 3, 30.0, "2026-01-01T00:03:00Z")
        val beforeFinish = withFourth.copy(notes = "watch note")
        val stamp = phoneCorrection.occurredAtMillis
        val correction = event(handedOff, watchCorrection).copy(occurredAtMillis = stamp + 1)
        val third = event(watchCorrection, withThird).copy(predecessorEventId = correction.eventId, occurredAtMillis = stamp + 2)
        val fourth = event(withThird, beforeFinish).copy(predecessorEventId = third.eventId, occurredAtMillis = stamp + 3)
        val ended = event(beforeFinish, finish(beforeFinish)).copy(predecessorEventId = fourth.eventId, occurredAtMillis = stamp + 4)
        val backlog = listOf(correction, third, fourth, ended)
        val result = repo.importWatchEvents(backlog.reversed())
        assertEquals(backlog.map { it.eventId }, result.acknowledgements)
        repo.acknowledgePhoneWatchEvent(phoneCorrection.eventId)
        assertTrue(repo.observePhoneWatchEvents().first().isEmpty())
        val received = repo.getSession(sessionId)!!
        assertEquals(SessionStatus.COMPLETED, received.status)
        assertEquals("watch note", received.notes)
        assertEquals(listOf(32.0, 30.0, 30.0, 30.0), received.exercises.single().sets.map { it.weightKg })
        assertTrue(repo.observeWatchConflicts().first().isNotEmpty())
    }

    @Test fun testProfileCannotImportOrExportCatalog() = runBlocking {
        repo.setProfile(Profile.TEST)
        assertTrue(repo.importWatchEvent(event(base, base, WorkoutProfile.TEST)) is EventResult.Rejected)
        assertNull(repo.watchCatalog())
        assertNull(repo.getSession(sessionId))
    }

    @Test fun editingUnrelatedCompletedSessionQueuesNoWatchEvent() = runBlocking {
        val old = finish(set(base, 0, 30.0, "2026-01-01T00:01:00Z"))
        repo.syncStore.applyRemote(listOf(Document(sessionId, "session", 1, "2026-01-01T00:00:00Z", false, "server", raw(old))))
        repo.enableWatchCompanion()
        assertTrue(repo.observePhoneWatchEvents().first().isEmpty())
        repo.updateSession(sessionId) { it.copy(notes = "late edit") }
        assertTrue(db.dao().getSettingsPrefix("watch_outgoing:").isEmpty())
        assertNull(db.dao().getSetting("watch_replica:$sessionId"))
    }

    @Test fun finishedSessionReplicaIsPrunedOnceNothingIsPending() = runBlocking {
        start()
        val finished = finish(set(base, 0, 30.0, "2026-01-01T00:01:00Z"))
        assertTrue(repo.importWatchEvent(event(base, finished)) is EventResult.Applied)
        assertNotNull(db.dao().getSetting("watch_replica:$sessionId"))
        repo.acknowledgePhoneWatchEvent("unrelated")
        assertNull(db.dao().getSetting("watch_replica:$sessionId"))
    }

    @Test fun lateEditAfterFinishedReplicaPruningStillFindsItsPredecessor() = runBlocking {
        start()
        val finished = finish(set(base, 0, 30.0, "2026-01-01T00:01:00Z"))
        val ended = event(base, finished)
        assertEquals(listOf(ended.eventId), repo.importWatchEvents(listOf(ended)).acknowledgements)
        repo.acknowledgePhoneWatchEvent("unrelated")
        assertNull(db.dao().getSetting("watch_replica:$sessionId"))

        val edited = event(finished, finished.copy(notes = "late watch note")).copy(
            predecessorEventId = ended.eventId, occurredAtMillis = ended.occurredAtMillis + 1)
        val restarted = GymRepository(db)
        assertEquals(listOf(edited.eventId), restarted.importWatchEvents(listOf(edited)).acknowledgements)
        assertEquals("late watch note", restarted.getSession(sessionId)!!.notes)
        assertEquals(0, restarted.importWatchEvents(listOf(ended)).appliedCount)
    }

    @Test fun replicaWithConflictIsKept() = runBlocking {
        start()
        repo.updateSession(sessionId) { set(it, 0, 30.0, "2026-01-01T00:01:00Z") }
        val watch = finish(set(set(base, 0, 40.0, "2026-01-01T00:01:01Z"), 1, 30.0, "2026-01-01T00:03:00Z"))
        assertTrue(repo.importWatchEvent(event(base, watch)) is EventResult.Applied)
        repo.acknowledgePhoneWatchEvent("unrelated")
        assertNotNull(db.dao().getSetting("watch_replica:$sessionId"))
        assertTrue(repo.observeWatchConflicts().first().isNotEmpty())
    }
}
