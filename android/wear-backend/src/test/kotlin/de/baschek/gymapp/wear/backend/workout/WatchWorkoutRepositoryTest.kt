package de.baschek.gymapp.wear.backend.workout

import de.baschek.gymapp.core.model.*
import de.baschek.gymapp.core.wear.events.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WatchWorkoutRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private inline fun <reified T> raw(x: T) = DocumentCodec.encodeMerged(x, null)
    private fun catalog(profile: WorkoutProfile) = WatchCatalog(profile = profile,
        gyms = mapOf("gym" to raw(Gym(name = "Synthetic"))),
        exercises = mapOf("exercise" to raw(Exercise(name = "Synthetic"))),
        plans = mapOf("plan" to raw(Plan(name = "Plan", exercises = listOf(PlanExercise(id = "p1", exerciseId = "exercise", modeSets = ModeSets(2, 2, 2), target = Target(Metric.REPS, 8.0, 12.0, 1.0, 2.0)))))))
    @Test fun `offline session and events survive restart and final set finishes`() = runTest {
        val file = temporary.newFile("personal.json").also { it.delete() }
        val repository = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch", now = { 1767261600000L })
        assertTrue(repository.replaceCatalog(catalog(WorkoutProfile.PERSONAL)))
        val id = repository.startPlan("plan", "gym", WorkoutMode.NORMAL)
        assertNotNull(id)
        val exercise = repository.state.value.session!!.exercises.single()
        assertTrue(repository.logSet(exercise.id, exercise.sets[0].id, WatchSetInput(20.0, 12.0, "one_two")))
        assertTrue(repository.skipRest())
        val restored = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch", now = { 1767261700000L })
        assertEquals(id, restored.state.value.sessionId)
        assertEquals(SetStatus.COMPLETED, restored.state.value.session!!.exercises.single().sets.first().status)
        assertTrue(restored.logSet(exercise.id, exercise.sets[1].id, WatchSetInput(20.0, 12.0, "one_two")))
        assertEquals(SessionStatus.COMPLETED, restored.state.value.session!!.status)
        assertTrue(restored.state.value.pendingEvents >= 4)
        assertEquals(1, restored.state.value.milestones.size)
    }
    @Test fun `test profile stays local and remote events are rejected`() = runTest {
        val file = temporary.newFile("test.json").also { it.delete() }
        val repository = WatchWorkoutRepository.at(file, WorkoutProfile.TEST, "watch")
        assertTrue(repository.replaceCatalog(catalog(WorkoutProfile.TEST)))
        assertNotNull(repository.startSpontaneous("exercise", "gym"))
        val session = repository.state.value.session!!; val exercise = session.exercises.single()
        assertTrue(repository.logSet(exercise.id, exercise.sets.single().id, WatchSetInput(10.0, 10.0, "one_two")))
        assertEquals(0, repository.state.value.pendingEvents)
        assertFalse(repository.replaceCatalog(catalog(WorkoutProfile.PERSONAL)))
    }
    @Test fun `undo reopens a logged set while workout remains active`() = runTest {
        val file = temporary.newFile("undo.json").also { it.delete() }
        val repository = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch")
        repository.replaceCatalog(catalog(WorkoutProfile.PERSONAL)); repository.startPlan("plan", "gym", WorkoutMode.NORMAL)
        val exercise = repository.state.value.session!!.exercises.single()
        repository.logSet(exercise.id, exercise.sets[0].id, WatchSetInput(20.0, 10.0, "one_two"))
        assertTrue(repository.undo())
        assertEquals(SetStatus.PENDING, repository.state.value.session!!.exercises.single().sets.first().status)
    }
    @Test fun `final set undo reverses progression and corrected finish reapplies it`() = runTest {
        val file = temporary.newFile("finish-undo.json").also { it.delete() }
        val r = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch", now = { 1767261600000L })
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL)); r.startPlan("plan", "gym", WorkoutMode.NORMAL)
        val ex = r.state.value.session!!.exercises.single()
        r.logSet(ex.id, ex.sets[0].id, WatchSetInput(20.0, 12.0, "one_two"))
        r.logSet(ex.id, ex.sets[1].id, WatchSetInput(20.0, 12.0, "one_two"))
        assertEquals(SessionStatus.COMPLETED, r.state.value.session!!.status)
        assertEquals(1, r.state.value.milestones.size)
        assertTrue(r.undo())
        assertEquals(SessionStatus.ACTIVE, r.state.value.session!!.status)
        assertEquals(SetStatus.PENDING, r.state.value.session!!.exercises.single().sets[1].status)
        assertTrue(r.state.value.milestones.isEmpty())
        assertTrue(r.state.value.catalog.machineProfiles.isEmpty())
        assertTrue(r.logSet(ex.id, ex.sets[1].id, WatchSetInput(20.0, 11.0, "one_two")))
        assertEquals(SessionStatus.COMPLETED, r.state.value.session!!.status)
        assertEquals(1, r.state.value.milestones.size)
    }
    @Test fun `new workout retains prior outbox and old event receipts across restart`() = runTest {
        val file = temporary.newFile("archive.json").also { it.delete() }
        val r = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch", now = { 1767261600000L })
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL))
        val firstId = r.startSpontaneous("exercise", "gym")!!
        val ex = r.state.value.session!!.exercises.single()
        r.logSet(ex.id, ex.sets.single().id, WatchSetInput(20.0, 12.0, "one_two"))
        val oldEvent = r.state.value.outbox.first()
        val count = r.state.value.outbox.size
        assertNotNull(r.startSpontaneous("exercise", "gym"))
        assertTrue(r.state.value.outbox.size > count)
        val restored = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch")
        assertTrue(restored.state.value.archivedReplicas.containsKey(firstId))
        assertEquals(count + 1, restored.state.value.outbox.size)
        assertTrue(restored.receive(oldEvent) is EventResult.Replayed)
    }

    @Test fun `a running set timer never attributes its time to a different set`() = runTest {
        val file = temporary.newFile("timer.json").also { it.delete() }
        var clock = 1767261600000L
        val r = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch", now = { clock })
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL)); r.startPlan("plan", "gym", WorkoutMode.NORMAL)
        val ex = r.state.value.session!!.exercises.single()
        assertTrue(r.startSetTimer())
        assertEquals(ex.sets[0].id, r.state.value.timer?.setId)
        clock += 15000
        assertTrue(r.logSet(ex.id, ex.sets[1].id, WatchSetInput(20.0, 10.0, "one_two")))
        assertNull(r.state.value.session!!.exercises.single().sets[1].setDurationSeconds)
    }

    @Test fun `new catalog cannot replace active offline workout or pending events`() = runTest {
        val file = temporary.newFile("catalog.json").also { it.delete() }
        val r = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch")
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL)); val id = r.startPlan("plan", "gym", WorkoutMode.NORMAL)!!
        val ex = r.state.value.session!!.exercises.single()
        r.logSet(ex.id, ex.sets[0].id, WatchSetInput(20.0, 10.0, "one_two"))
        val before = r.state.value.replica; val pending = r.state.value.outbox
        val staleSession = raw(r.state.value.session!!.copy(exercises = emptyList()))
        assertTrue(r.replaceCatalog(catalog(WorkoutProfile.PERSONAL).copy(sessions = mapOf(id to staleSession))))
        assertEquals(before, r.state.value.replica)
        assertEquals(pending, r.state.value.outbox)
        val restored = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch")
        assertEquals(before, restored.state.value.replica)
        assertEquals(pending, restored.state.value.outbox)
    }

    @Test fun `archived replicas are pruned but unsent and conflicted ones are kept`() = runTest {
        val file = temporary.newFile("prune.json").also { it.delete() }
        var tick = 1767261600000L
        val r = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch", now = { tick += 1000; tick })
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL))
        val ids = mutableListOf<String>()
        repeat(9) {
            ids += r.startPlan("plan", "gym", WorkoutMode.NORMAL)!!
            val ex = r.state.value.session!!.exercises.single()
            r.logSet(ex.id, ex.sets[0].id, WatchSetInput(20.0, 12.0, "one_two"))
            r.logSet(ex.id, ex.sets[1].id, WatchSetInput(20.0, 12.0, "one_two"))
            if (it > 0) r.acknowledge(r.state.value.outbox.filter { e -> e.sessionId != ids.first() }.map { e -> e.eventId }.toSet())
        }
        val archived = r.state.value.archivedReplicas
        assertTrue(archived.size <= 6)
        assertTrue("session with unsent events survives", archived.containsKey(ids.first()))
        assertTrue(archived.containsKey(ids[7]))
        assertFalse(archived.containsKey(ids[1]))
    }
}
