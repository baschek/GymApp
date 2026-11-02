package de.baschek.gymapp

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.baschek.gymapp.core.model.DocKey
import de.baschek.gymapp.core.model.Document
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.data.db.GymDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
        val id = repo.startSession("p1", gymId = null)!!
        repo.updateSession(id) { it.copy(notes = "changed") }
        val ops = repo.syncStore.pendingOps(100)
        assertEquals(1, ops.size)
        assertEquals("session", ops.single().entity)
        assertEquals(null, ops.single().baseRev)

        repo.finishSession(id)
        val after = repo.syncStore.pendingOps(100).map { it.entity }.sorted()
        assertEquals(listOf("plan", "session"), after.distinct())
        val planOp = repo.syncStore.pendingOps(100).first { it.entity == "plan" }
        assertEquals(5L, planOp.baseRev)
        assertEquals("keep-me", (planOp.data as JsonObject)["future"].toString().trim('"'))
    }

    @Test
    fun snapshotKeepsPendingAndDropsStaleSyncedDocuments() = runBlocking {
        repo.syncStore.applyRemote(listOf(doc("plan", "old", 1, plan), doc("plan", "p1", 2, plan), doc("exercise", "ex1", 3, exercise)))
        val id = repo.startSession("p1", gymId = null)!!
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
}
