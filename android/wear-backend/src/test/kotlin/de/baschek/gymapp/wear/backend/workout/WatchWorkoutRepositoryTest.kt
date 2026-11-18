package de.baschek.gymapp.wear.backend.workout

import de.baschek.gymapp.core.model.*
import de.baschek.gymapp.core.training.ActionOutcome
import de.baschek.gymapp.core.training.RejectReason
import de.baschek.gymapp.core.wear.events.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        assertTrue(repository.logSet(exercise.id, exercise.sets[0].id, WatchSetInput(20.0, 12.0, "one_two")).changed)
        assertTrue(repository.skipRest().changed)
        val restored = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch", now = { 1767261700000L })
        assertEquals(id, restored.state.value.sessionId)
        assertEquals(SetStatus.COMPLETED, restored.state.value.session!!.exercises.single().sets.first().status)
        assertTrue(restored.logSet(exercise.id, exercise.sets[1].id, WatchSetInput(20.0, 12.0, "one_two")).changed)
        assertEquals(SessionStatus.COMPLETED, restored.state.value.session!!.status)
        assertTrue(restored.state.value.pendingEvents >= 4)
        assertEquals(1, restored.state.value.milestones.size)
    }
    @Test fun `offline handoff publishes only the completed workout`() = runTest {
        val sender = WatchWorkoutRepository.at(temporary.root.resolve("sender"), WorkoutProfile.PERSONAL, "phone")
        sender.replaceCatalog(catalog(WorkoutProfile.PERSONAL), 2)
        sender.startPlan("plan", "gym", WorkoutMode.NORMAL)
        val exercise = sender.state.value.session!!.exercises.single()
        for (set in exercise.sets) sender.logSet(exercise.id, set.id, WatchSetInput(20.0, 12.0, "one_two"))
        val backlog = sender.state.value.outbox
        assertTrue(backlog.size >= 3)
        val receiver = WatchWorkoutRepository.at(temporary.root.resolve("receiver"), WorkoutProfile.PERSONAL, "watch")
        val statuses = mutableListOf<String?>()
        val collector = launch(Dispatchers.Unconfined) { receiver.state.collect { statuses.add(it.session?.status) } }
        try {
            val result = receiver.receiveBatch(backlog.reversed())
            assertEquals(backlog.map { it.eventId }, result.acknowledgements)
            assertEquals(backlog.size, result.appliedCount)
            assertEquals(listOf(null, SessionStatus.COMPLETED), statuses)
            assertEquals(0, receiver.receiveBatch(backlog).appliedCount)
            assertEquals(backlog.map { it.eventId }, receiver.state.value.replica!!.receipts.map { it.eventId })
        } finally { collector.cancel() }
    }

    @Test fun `rejected predecessor remains acknowledged when the successor arrives after restart`() = runTest {
        val sender = WatchWorkoutRepository.at(temporary.root.resolve("sender"), WorkoutProfile.PERSONAL, "phone")
        sender.replaceCatalog(catalog(WorkoutProfile.PERSONAL), 2)
        sender.startPlan("plan", "gym", WorkoutMode.NORMAL)
        val exercise = sender.state.value.session!!.exercises.single()
        sender.logSet(exercise.id, exercise.sets.first().id, WatchSetInput(20.0, 12.0, "one_two"))
        val (head, tail) = sender.state.value.outbox
        val file = temporary.root.resolve("receiver")
        val receiver = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch")
        assertEquals(listOf(head.eventId), receiver.receiveBatch(listOf(head.copy(deviceId = ""))).acknowledgements)
        assertNull(receiver.state.value.session)

        val restarted = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch")
        assertEquals(listOf(tail.eventId), restarted.receiveBatch(listOf(tail)).acknowledgements)
        assertEquals(SetStatus.COMPLETED, restarted.state.value.session!!.exercises.single().sets.first().status)
    }

    @Test fun `test profile stays local and remote events are rejected`() = runTest {
        val file = temporary.newFile("test.json").also { it.delete() }
        val repository = WatchWorkoutRepository.at(file, WorkoutProfile.TEST, "watch")
        assertTrue(repository.replaceCatalog(catalog(WorkoutProfile.TEST)))
        assertNotNull(repository.startSpontaneous("exercise", "gym"))
        val session = repository.state.value.session!!; val exercise = session.exercises.single()
        assertTrue(repository.logSet(exercise.id, exercise.sets.single().id, WatchSetInput(10.0, 10.0, "one_two")).changed)
        assertEquals(0, repository.state.value.pendingEvents)
        assertFalse(repository.replaceCatalog(catalog(WorkoutProfile.PERSONAL)))
    }
    @Test fun `undo reopens a logged set while workout remains active`() = runTest {
        val file = temporary.newFile("undo.json").also { it.delete() }
        val repository = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch")
        repository.replaceCatalog(catalog(WorkoutProfile.PERSONAL)); repository.startPlan("plan", "gym", WorkoutMode.NORMAL)
        val exercise = repository.state.value.session!!.exercises.single()
        repository.logSet(exercise.id, exercise.sets[0].id, WatchSetInput(20.0, 10.0, "one_two"))
        assertTrue(repository.undo().changed)
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
        assertTrue(r.undo().changed)
        assertEquals(SessionStatus.ACTIVE, r.state.value.session!!.status)
        assertEquals(SetStatus.PENDING, r.state.value.session!!.exercises.single().sets[1].status)
        assertTrue(r.state.value.milestones.isEmpty())
        assertTrue(r.state.value.catalog.machineProfiles.isEmpty())
        assertTrue(r.logSet(ex.id, ex.sets[1].id, WatchSetInput(20.0, 11.0, "one_two")).changed)
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
        assertTrue(r.startSetTimer().changed)
        assertEquals(ex.sets[0].id, r.state.value.timer?.setId)
        clock += 15000
        assertTrue(r.logSet(ex.id, ex.sets[1].id, WatchSetInput(20.0, 10.0, "one_two")).changed)
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

    @Test fun `authoritative catalog deletes completed history after pending work is acknowledged`() = runTest {
        val file = temporary.newFile("deleted-history.json").also { it.delete() }
        val r = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch", now = { 1767261600000L })
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL), 2, emptySet())
        val id = r.startSpontaneous("exercise", "gym")!!
        val ex = r.state.value.session!!.exercises.single()
        r.logSet(ex.id, ex.sets.single().id, WatchSetInput(20.0, 12.0, "one_two"))
        val event = r.state.value.outbox.first()
        val finished = r.state.value.catalog.sessions.getValue(id)
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL).copy(sessions = mapOf(id to finished)), 2, setOf(id))
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL), 2, emptySet())
        assertTrue("pending local data remains", id in r.state.value.catalog.sessions)
        r.acknowledge(r.state.value.outbox.map { it.eventId }.toSet())
        assertFalse(id in r.state.value.catalog.sessions)
        assertNull(r.state.value.replica)
        assertTrue(r.receive(event) is EventResult.Replayed)
        assertFalse(id in r.state.value.catalog.sessions)
        val restored = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch")
        assertFalse(id in restored.state.value.catalog.sessions)
        val unseen = event.copy(eventId = "00000000-0000-4000-8000-000000000099")
        assertEquals("deleted_session", (restored.receive(unseen) as EventResult.Rejected).reason)
    }

    @Test fun `active workout survives catalog omission and acknowledgement`() = runTest {
        val file = temporary.newFile("active-deletion.json").also { it.delete() }
        val r = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch")
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL), 2, emptySet())
        val id = r.startPlan("plan", "gym", WorkoutMode.NORMAL)!!
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL), 2, setOf(id))
        r.replaceCatalog(catalog(WorkoutProfile.PERSONAL), 2, emptySet())
        r.acknowledge(r.state.value.outbox.map { it.eventId }.toSet())
        assertEquals(id, r.state.value.sessionId)
        assertEquals(SessionStatus.ACTIVE, r.state.value.session!!.status)
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

    @Test fun `no-op actions are silent, rejections name the reason, next exercise is the first open one`() = runTest {
        val file = temporary.newFile("outcomes.json").also { it.delete() }
        val names = listOf("A", "B", "C", "D")
        val cat = WatchCatalog(profile = WorkoutProfile.PERSONAL,
            gyms = mapOf("gym" to raw(Gym(name = "Synthetic"))),
            exercises = names.associate { "x$it" to raw(Exercise(name = it)) },
            plans = mapOf("plan" to raw(Plan(name = "Plan", exercises = names.mapIndexed { i, n ->
                PlanExercise(id = "p$n", exerciseId = "x$n", order = i, modeSets = ModeSets(1, 1, 1), target = Target(Metric.REPS, 8.0, 12.0, 1.0, 2.0))
            }))))
        val r = WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch", now = { 1767261600000L })
        r.replaceCatalog(cat); r.startPlan("plan", "gym", WorkoutMode.NORMAL)
        val list = r.state.value.session!!.exercises
        assertEquals(ActionOutcome.Unchanged, r.setFocus(list[0].id))
        assertEquals(ActionOutcome.Unchanged, r.moveExercise(list[0].id, -1))
        assertEquals(ActionOutcome.Changed, r.setFocus(list[2].id))
        val set = list[2].sets.single().id
        assertEquals(ActionOutcome.Changed, r.logSet(list[2].id, set, WatchSetInput(20.0, 10.0, "three_four", effortSource = EffortSource.USER)))
        val logged = r.state.value.session!!.exercises[2].sets.single()
        assertEquals(EffortSource.USER, logged.effortSource)
        assertEquals(ActionOutcome.Rejected(RejectReason.SET_ALREADY_DONE), r.logSet(list[2].id, set, WatchSetInput(20.0, 10.0, "one_two")))
        // after the jump to C the label and the action both go to A (the first open exercise)
        assertEquals("A", r.nextExerciseName())
        assertEquals(ActionOutcome.Changed, r.skipRest())
        assertEquals(ActionOutcome.Unchanged, r.skipRest())
        assertEquals(ActionOutcome.Rejected(RejectReason.REST_ALREADY_OVER), r.changeRest(15))
        assertEquals(ActionOutcome.Changed, r.nextExercise())
        assertEquals(list[0].id, r.state.value.focusExerciseId)
        assertEquals(ActionOutcome.Rejected(RejectReason.EXERCISE_DONE), r.setFocus(list[2].id))
        val preview = r.nextSetPreview()!!
        assertEquals(list[0].id, preview.exerciseId)
        assertEquals("Ziel 8–12", preview.targetText)
        r.setHeartAutoConsent(true)
        assertEquals(true, WatchWorkoutRepository.at(file, WorkoutProfile.PERSONAL, "watch").state.value.heartAutoConsent)
    }
    private fun holdCatalog(planTarget: Target) = WatchCatalog(profile = WorkoutProfile.PERSONAL,
        gyms = mapOf("gym" to raw(Gym(name = "Synthetic"))),
        exercises = mapOf("hold" to raw(Exercise(name = "Synthetic Hold", measurementType = MeasurementType.LOAD_DURATION, loadBasis = LoadBasis.PER_HAND))),
        plans = mapOf("plan" to raw(Plan(name = "Plan", exercises = listOf(PlanExercise(id = "p1", exerciseId = "hold", modeSets = ModeSets(2, 2, 2), target = planTarget))))))

    @Test fun `a hold from a plan with a reps target is measured in seconds and the stopwatch time is saved`() = runTest {
        var clock = 1767261600000L
        val r = WatchWorkoutRepository.at(temporary.newFile("hold.json").also { it.delete() }, WorkoutProfile.PERSONAL, "watch", now = { clock })
        r.replaceCatalog(holdCatalog(Target(Metric.REPS, 8.0, 12.0, 1.0, 2.0))); assertNotNull(r.startPlan("plan", "gym", WorkoutMode.NORMAL))
        val ex = r.state.value.session!!.exercises.single()
        assertEquals(Metric.SECONDS, ex.target.metric)
        assertTrue(r.startSetTimer().changed)
        clock += 62000
        assertTrue(r.logSet(ex.id, ex.sets[0].id, WatchSetInput(16.0, 62.0, "one_two")).changed)
        val set = r.state.value.session!!.exercises.single().sets[0]
        assertEquals(62, set.seconds); assertNull(set.reps); assertEquals(62, set.setDurationSeconds)
    }

    @Test fun `a seconds target of the plan is kept and an exercise added during the workout gets seconds`() = runTest {
        val r = WatchWorkoutRepository.at(temporary.newFile("hold2.json").also { it.delete() }, WorkoutProfile.PERSONAL, "watch")
        r.replaceCatalog(holdCatalog(Target(Metric.SECONDS, 60.0, 90.0, 1.0, 2.0))); r.startPlan("plan", "gym", WorkoutMode.NORMAL)
        assertEquals(60.0, r.state.value.session!!.exercises.single().target.min, 0.0)
        assertTrue(r.addExercise("hold").changed)
        val added = r.state.value.session!!.exercises.last()
        assertEquals(Metric.SECONDS, added.target.metric)
    }
}
