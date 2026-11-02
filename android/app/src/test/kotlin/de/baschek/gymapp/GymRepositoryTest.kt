package de.baschek.gymapp

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.model.DocKey
import de.baschek.gymapp.core.model.Document
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.data.db.GymDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import de.baschek.gymapp.timer.TimerController
import de.baschek.gymapp.timer.TimerPhase
import org.junit.Assert.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GymRepositoryTest {
    private lateinit var db: GymDatabase
    private lateinit var repo: GymRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = GymRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun doc(entity: String, id: String, rev: Long, json: String) = Document(
        id, entity, rev, "2026-01-01T00:00:00Z", false, "server", GymJson.parseToJsonElement(json).jsonObject,
    )

    private val plan = """{"name":"Synthetic plan","future":"keep-me","exercises":[{"id":"pe1","exercise_id":"ex1","order":1,
        "mode_sets":{"normal":2,"fast":1,"ultra":1},"target":{"metric":"reps","min":8,"max":12,"rir_min":1,"rir_max":2},"starting_weight_kg":20,"rest_seconds":60}]}"""
    private val exercise = """{"name":"Synthetic machine","measurement_type":"load_reps","load_direction":"higher","load_basis":"stack"}"""

    @Test
    fun testProfileNeverProducesOutboxEntries() = runBlocking {
        repo.setProfile(Profile.TEST)
        assertTrue(repo.seedDemoData())
        val plans = repo.observePlans().first()
        assertEquals(1, plans.size)
        val sessionId = repo.startSession(plans.single().id, gymId = "demo-gym")
        assertNotNull(sessionId)
        repo.finishSession(sessionId!!)
        assertTrue(repo.syncStore.pendingOps(100).isEmpty())
    }

    @Test
    fun demoDataIsRefusedInPersonalProfile() = runBlocking {
        assertFalse(repo.seedDemoData())
        assertTrue(repo.observePlans().first().isEmpty())
    }

    @Test
    fun personalEditsAreQueuedOncePerDocumentAndKeepUnknownFields() = runBlocking {
        repo.syncStore.applyRemote(listOf(doc("plan", "p1", 5, plan), doc("exercise", "ex1", 6, exercise)))
        val id = repo.startSession("p1", gymId = "gym-a")!!
        repo.updateSession(id) { it.copy(notes = "changed") }
        val ops = repo.syncStore.pendingOps(100)
        assertEquals(1, ops.size)
        assertEquals("session", ops.single().entity)
        assertEquals(null, ops.single().baseRev)

        repo.finishSession(id)
        // Nothing was proven, so only the session itself is queued; the plan is no longer touched.
        assertEquals(listOf("session"), repo.syncStore.pendingOps(100).map { it.entity }.distinct())
    }

    @Test
    fun snapshotKeepsPendingAndDropsStaleSyncedDocuments() = runBlocking {
        repo.syncStore.applyRemote(listOf(doc("plan", "old", 1, plan), doc("plan", "p1", 2, plan), doc("exercise", "ex1", 3, exercise)))
        val id = repo.startSession("p1", gymId = "gym-a")!!
        repo.syncStore.applySnapshot(listOf(doc("plan", "p1", 9, plan)), 9, repo.syncStore.pendingKeys())
        val plans = repo.observePlans().first().map { it.id }
        assertEquals(listOf("p1"), plans)
        assertNotNull(repo.observeSession(id).first())
        assertEquals(9L, repo.syncStore.cursor())
    }

    @Test
    fun snapshotNeverTouchesTestProfile() = runBlocking {
        repo.setProfile(Profile.TEST)
        repo.seedDemoData()
        repo.setProfile(Profile.PERSONAL)
        repo.syncStore.applySnapshot(emptyList(), 3, emptySet())
        repo.setProfile(Profile.TEST)
        assertEquals(1, repo.observePlans().first().size)
        assertTrue(repo.syncStore.pendingKeys().isEmpty() && DocKey("plan", "demo-plan") !in repo.syncStore.pendingKeys())
    }

    private suspend fun completeTwoSets(sessionId: String, weight: Double) {
        repo.updateSession(sessionId) { sess ->
            sess.copy(
                exercises = sess.exercises.map { ex ->
                    ex.copy(
                        sets = ex.sets.map {
                            it.copy(status = "completed", weightKg = weight, reps = 10, effort = "two_three")
                        },
                    )
                },
            )
        }
    }

    private suspend fun seedGymA(): String {
        repo.syncStore.applyRemote(listOf(doc("plan", "p1", 5, plan), doc("exercise", "ex1", 6, exercise)))
        val id = repo.startSession("p1", gymId = "gym-a")!!
        completeTwoSets(id, 30.0)
        repo.finishSession(id)
        return id
    }

    @Test
    fun finishStoresWorkingWeightPerGymAndFeedsCrossGymEstimate() = runBlocking {
        seedGymA()
        val mp = repo.syncStore.pendingOps(100).first { it.entity == "machine_profile" }
        assertEquals("30", mp.data["working_weight_kg"].toString().removeSuffix(".0"))
        assertEquals("gym-a", mp.data["gym_id"].toString().trim('"'))

        val second = repo.startSession("p1", gymId = "gym-b")!!
        val ex = repo.getSession(second)!!.exercises.single()
        assertEquals("cross_gym_estimate", ex.suggestionReason)
        assertEquals("gym-a", ex.suggestionSourceGymId)
        assertEquals(25.5, ex.suggestedWeightKg!!, 1e-9)
    }

    @Test
    fun reassigningTheGymRecalculatesAndClearsGymFlags() = runBlocking {
        val id = seedGymA()
        repo.updateSession(id) { it.copy(reviewFlags = listOf("gym_suspect", "other_flag")) }
        repo.reassignSessionGym(id, "gym-b")

        val session = repo.getSession(id)!!
        assertEquals("gym-b", session.gymId)
        assertEquals(listOf("other_flag"), session.reviewFlags)
        val profiles = repo.syncStore.pendingOps(100).filter { it.entity == "machine_profile" }
        val byGym = profiles.associateBy { it.data["gym_id"].toString().trim('"') }
        assertTrue(byGym["gym-a"]!!.data["working_weight_kg"] == null)
        assertEquals("30", byGym["gym-b"]!!.data["working_weight_kg"].toString().removeSuffix(".0"))
    }

    @Test
    fun timerStateSurvivesRestartWhileSessionIsActive() = runBlocking {
        repo.syncStore.applyRemote(listOf(doc("plan", "p1", 5, plan), doc("exercise", "ex1", 6, exercise)))
        repo.startSession("p1", gymId = "gym-a")
        var now = 1_000_000L
        val first = TimerController(repo, CoroutineScope(Dispatchers.Default)) { now }
        first.start(TimerPhase.REST, "Synthetic machine", 90)
        waitForTimerSetting()

        now += 30_000
        val second = TimerController(repo, CoroutineScope(Dispatchers.Default)) { now }
        second.restore()
        val restored = second.state.value!!
        assertEquals(TimerPhase.REST, restored.phase)
        assertEquals(1_000_000L, restored.startedAtMillis)
        assertEquals(1_090_000L, restored.endsAtMillis)
        assertEquals(30, restored.elapsedSeconds(now))
    }

    @Test
    fun staleTimerWithoutActiveSessionIsDropped() = runBlocking {
        val first = TimerController(repo, CoroutineScope(Dispatchers.Default)) { 5_000L }
        first.start(TimerPhase.SET, "x", 0)
        waitForTimerSetting()
        val second = TimerController(repo, CoroutineScope(Dispatchers.Default)) { 6_000L }
        second.restore()
        assertNull(second.state.value)
    }

    private suspend fun waitForTimerSetting() {
        withTimeout(5_000) {
            while (repo.getSetting("timer_state") == null) delay(20)
        }
    }

    @Test
    fun unknownSessionFieldsSurviveLocalEdits() = runBlocking {
        repo.syncStore.applyRemote(
            listOf(doc("session", "s1", 3, """{"plan_name":"x","status":"completed","started_at":"2026-01-01T10:00:00Z","gym_id":"gym-a","future":"keep-me","exercises":[]}""")),
        )
        repo.updateSession("s1") { it.copy(notes = "edited") }
        val op = repo.syncStore.pendingOps(10).single()
        assertEquals("\"keep-me\"", op.data["future"].toString())
        assertEquals(3L, op.baseRev)
    }
}
