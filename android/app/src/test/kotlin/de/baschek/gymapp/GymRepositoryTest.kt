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
@Config(sdk = [34], application = TestApplication::class)
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

    @Test
    fun existingServerCreatedMachineProfileIsReusedInsteadOfCreatingASecondOne() = runBlocking {
        repo.syncStore.applyRemote(
            listOf(
                doc("plan", "p1", 5, plan), doc("exercise", "ex1", 6, exercise),
                doc("machine_profile", "11111111-aaaa", 7, """{"gym_id":"gym-a","exercise_id":"ex1","available_weights_kg":[10,20,30],"server_note":"keep"}"""),
            ),
        )
        val id = repo.startSession("p1", gymId = "gym-a")!!
        completeTwoSets(id, 30.0)
        repo.finishSession(id)
        val ops = repo.syncStore.pendingOps(100).filter { it.entity == "machine_profile" }
        assertEquals(1, ops.size)
        assertEquals("11111111-aaaa", ops.single().id)
        assertEquals(7L, ops.single().baseRev)
        assertEquals("\"keep\"", ops.single().data["server_note"].toString())
        assertTrue(ops.single().data["available_weights_kg"] != null)
    }

    @Test
    fun reconnectBackoffDoublesAndIsClearedBySuccess() = runBlocking {
        assertNull(repo.nextReconnectAttemptAt())
        val first = repo.recordConnectionLost(0)
        val second = repo.recordConnectionLost(0)
        assertEquals(15 * 60_000L, first)
        assertEquals(30 * 60_000L, second)
        assertTrue(repo.connectionLost.first())
        repo.recordSyncSuccess(emptyList())
        assertNull(repo.nextReconnectAttemptAt())
        assertTrue(!repo.connectionLost.first())
    }

    @Test
    fun goalIsASingletonDocumentMarkedAsEditedByTheApp() = runBlocking {
        assertNull(repo.observeGoal().first())
        repo.saveGoal { it.copy(trainingDaysPerWeek = 4, targetSets = it.targetSets.copy(brust = 12)) }
        val goal = repo.observeGoal().first()!!.goal
        assertEquals(4, goal.trainingDaysPerWeek)
        assertEquals(12, goal.targetSets.brust)
        assertEquals("app", goal.updatedBy)
        val ops = repo.syncStore.pendingOps(10).filter { it.entity == "goal" }
        assertEquals(listOf("weekly"), ops.map { it.id })
    }

    @Test
    fun machineProfileEditsReuseTheExistingDocumentAndKeepUnknownFields() = runBlocking {
        repo.syncStore.applyRemote(
            listOf(doc("machine_profile", "mp-1", 4, """{"gym_id":"gym-a","exercise_id":"ex1","available_weights_kg":[10,20],"server_note":"keep"}""")),
        )
        repo.saveMachineProfile("gym-a", "ex1") { it.copy(addonWeightsKg = listOf(1.25, 2.5)) }
        repo.saveMachineProfile("gym-b", "ex1") { it.copy(machineModel = "Synthetic") }
        val ops = repo.syncStore.pendingOps(10).filter { it.entity == "machine_profile" }
        assertEquals(2, ops.size)
        val existing = ops.first { it.id == "mp-1" }
        assertEquals(4L, existing.baseRev)
        assertEquals("\"keep\"", existing.data["server_note"].toString())
        assertNotNull(existing.data["addon_weights_kg"])
        assertEquals(null, ops.first { it.id != "mp-1" }.baseRev)
        val info = repo.getMachineInfo("gym-a")["ex1"]!!
        assertEquals(listOf(10.0, 20.0), info.levels)
        assertTrue(11.25 in info.candidates && 12.5 in info.candidates && 13.75 in info.candidates)
        assertTrue(21.25 in info.candidates && 23.75 in info.candidates)
    }

    @Test
    fun keepingMyVersionAfterAConflictWritesTheLosingEditBackAsNewEdit() = runBlocking {
        repo.syncStore.applyRemote(listOf(doc("plan", "p1", 5, plan)))
        repo.updatePlan("p1") { it.copy(name = "Mine") }
        val op = repo.syncStore.pendingOps(10).single()
        repo.syncStore.resolveConflict(op, doc("plan", "p1", 8, plan))
        repo.recordSyncSuccess(listOf("conflict:plan:p1"))
        assertEquals("Synthetic plan", repo.observePlan("p1").first()!!.value.name)

        repo.resolveConflict("conflict:plan:p1", keepMine = true)
        assertEquals("Mine", repo.observePlan("p1").first()!!.value.name)
        val again = repo.syncStore.pendingOps(10).single()
        assertEquals(8L, again.baseRev)
        assertTrue(repo.syncNotices.first().isEmpty())
    }

    @Test
    fun keepingTheNewestAfterAConflictOnlyClearsTheNotice() = runBlocking {
        repo.syncStore.applyRemote(listOf(doc("plan", "p1", 5, plan)))
        repo.updatePlan("p1") { it.copy(name = "Mine") }
        repo.syncStore.resolveConflict(repo.syncStore.pendingOps(10).single(), doc("plan", "p1", 8, plan))
        repo.recordSyncSuccess(listOf("conflict:plan:p1"))
        repo.resolveConflict("conflict:plan:p1", keepMine = false)
        assertEquals("Synthetic plan", repo.observePlan("p1").first()!!.value.name)
        assertTrue(repo.syncStore.pendingOps(10).isEmpty())
        assertTrue(repo.syncNotices.first().isEmpty())
    }

    @Test
    fun packingChecksResetOnANewDayAndStartFromTheGymDefaults() = runBlocking {
        repo.togglePackItem("gym-a", "bottle", "2026-11-02", defaults = setOf("towel"))
        assertEquals("2026-11-02|bottle\ntowel", repo.observePackChecked("gym-a").first())
        repo.togglePackItem("gym-a", "bottle", "2026-11-03", defaults = setOf("towel"))
        assertEquals("2026-11-03|bottle\ntowel", repo.observePackChecked("gym-a").first())
    }

    @Test
    fun assistantNoticeStaysSeenUntilTheServerChangesThePlanAgain() = runBlocking {
        repo.markPlanSeen("p1", "2026-10-28T10:00:00Z")
        repo.markPlanSeen("p2", "2026-10-29T10:00:00Z")
        repo.markPlanSeen("p1", "2026-11-01T10:00:00Z")
        assertEquals(mapOf("p2" to "2026-10-29T10:00:00Z", "p1" to "2026-11-01T10:00:00Z"), repo.plansSeen.first())
    }

    private fun adjustmentDoc(id: String, sessionId: String, ops: String, status: String = "pending") = doc(
        "workout_adjustment", id, 9,
        """{"session_id":"$sessionId","status":"$status","reason":"Letzter Satz war leicht.","created_at":"2026-11-04T09:00:00Z","future":"keep-me","ops":$ops}""",
    )

    @Test
    fun assistantAdjustmentIsAppliedToTheRunningSessionAnsweredBackAndCanBeUndone() = runBlocking {
        repo.syncStore.applyRemote(listOf(doc("plan", "p1", 5, plan), doc("exercise", "ex1", 6, exercise)))
        val id = repo.startSession("p1", gymId = "gym-a")!!
        val exerciseId = repo.getSession(id)!!.exercises.single().id
        repo.syncStore.applyRemote(
            listOf(adjustmentDoc("adj1", id, """[{"action":"set_weight","session_exercise_id":"$exerciseId","exercise_name":"Synthetic machine","weight_kg":35}]""")),
        )

        assertEquals(1, repo.applyPendingAdjustments())
        assertEquals(0, repo.applyPendingAdjustments()) // resolved ones are not applied twice
        assertEquals(listOf(35.0, 35.0), repo.getSession(id)!!.exercises.single().sets.map { it.weightKg })
        val answer = repo.syncStore.pendingOps(100).single { it.entity == "workout_adjustment" }
        assertEquals("\"applied\"", answer.data["status"].toString())
        assertEquals("\"keep-me\"", answer.data["future"].toString())
        assertEquals(9L, answer.baseRev)
        val banner = repo.observeAdjustmentBanner(id).first()!!
        assertTrue(banner.text.startsWith("Claude hat angepasst: Synthetic machine: 35 kg (Letzter Satz war leicht.)"))

        assertTrue(repo.undoAdjustment("adj1"))
        assertEquals(listOf(null, null), repo.getSession(id)!!.exercises.single().sets.map { it.weightKg })
        val undone = repo.syncStore.pendingOps(100).single { it.entity == "workout_adjustment" }
        assertEquals("\"rejected\"", undone.data["status"].toString())
        assertEquals("\"undone\"", undone.data["status_reason"].toString())
        assertNull(repo.observeAdjustmentBanner(id).first())
    }

    @Test
    fun adjustmentsNeverTouchLoggedSetsAndAreRejectedWithoutARunningSession() = runBlocking {
        repo.syncStore.applyRemote(listOf(doc("plan", "p1", 5, plan), doc("exercise", "ex1", 6, exercise)))
        val id = repo.startSession("p1", gymId = "gym-a")!!
        val ex = repo.getSession(id)!!.exercises.single()
        repo.updateSession(id) { s -> s.copy(exercises = s.exercises.map { e -> e.copy(sets = e.sets.mapIndexed { i, set -> if (i == 0) set.copy(status = "completed", weightKg = 20.0, reps = 10, effort = "two_three") else set }) }) }
        repo.syncStore.applyRemote(
            listOf(
                adjustmentDoc("adj1", id, """[{"action":"set_weight","session_exercise_id":"${ex.id}","weight_kg":30}]"""),
                adjustmentDoc("adj2", "no-such-session", """[{"action":"set_rest","session_exercise_id":"x","rest_seconds":60}]"""),
            ),
        )
        assertEquals(2, repo.applyPendingAdjustments())
        val sets = repo.getSession(id)!!.exercises.single().sets
        assertEquals(20.0, sets[0].weightKg) // the logged set is untouched
        assertEquals(30.0, sets[1].weightKg)
        val ops = repo.syncStore.pendingOps(100).filter { it.entity == "workout_adjustment" }.associateBy { it.id }
        assertEquals("\"applied\"", ops["adj1"]!!.data["status"].toString())
        assertEquals("\"no_active_session\"", ops["adj2"]!!.data["status_reason"].toString())
    }

    @Test
    fun adjustmentsAreNeverAppliedInTheTestProfile() = runBlocking {
        repo.setProfile(Profile.TEST)
        assertEquals(0, repo.applyPendingAdjustments())
    }

    @Test
    fun manualWorkingWeightIsTheBaselineOfRecalculationsInsteadOfBeingOverwritten() = runBlocking {
        val id = seedGymA()
        repo.saveMachineProfile("gym-a", "ex1") {
            it.copy(workingWeightKg = 40.0, workingWeightUpdatedAt = "2099-01-01T00:00:00.000Z", workingWeightSourceSessionId = "manual")
        }
        repo.reassignSessionGym(id, "gym-b") // without the baseline gym-a would lose its weight here
        val a = repo.getMachineInfo("gym-a")["ex1"]!!
        assertEquals(40.0, a.workingWeightKg!!, 1e-9)
        assertEquals(30.0, repo.getMachineInfo("gym-b")["ex1"]!!.workingWeightKg!!, 1e-9)
    }
}
