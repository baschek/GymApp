package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.AdjustmentAction
import de.baschek.gymapp.core.model.AdjustmentOp
import de.baschek.gymapp.core.model.AdjustmentStatus
import de.baschek.gymapp.core.model.Entity
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.IndexColumns
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.model.WorkoutAdjustment
import de.baschek.gymapp.core.model.DocumentCodec
import de.baschek.gymapp.core.progression.prefillSet
import de.baschek.gymapp.core.sync.WorkoutPoll
import de.baschek.gymapp.core.training.AdjustmentCode
import de.baschek.gymapp.core.training.ASSISTANT_SUGGESTION
import de.baschek.gymapp.core.training.applyAdjustment
import de.baschek.gymapp.core.training.clearPlannedWeights
import de.baschek.gymapp.core.training.describeAdjustment
import de.baschek.gymapp.core.training.revertAdjustment
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutAdjustmentTest {
    private var counter = 0
    private val newId: () -> String = { "new-${++counter}" }

    private fun set(id: String, status: String, weight: Double? = null, type: String = SetType.WORKING) =
        SetLog(id = id, order = 0, setType = type, status = status, weightKg = weight, reps = if (status == SetStatus.COMPLETED) 10 else null)

    private fun exercise(id: String, vararg sets: SetLog, restSeconds: Int = 90) = SessionExercise(
        id = id, exerciseId = "cat-$id", name = "Übung $id", target = Target("reps", 8.0, 12.0, 1.0, 2.0), restSeconds = restSeconds,
        plannedSets = sets.size, sets = sets.mapIndexed { i, s -> s.copy(order = i + 1) },
    )

    /** a: finished, b: one set logged and two open, c: untouched. */
    private fun session() = Session(
        gymId = "gym", planName = "Plan", status = SessionStatus.ACTIVE, startedAt = "2026-11-04T09:00:00Z",
        exercises = listOf(
            exercise("a", set("a1", SetStatus.COMPLETED, 40.0), set("a2", SetStatus.COMPLETED, 40.0)),
            exercise("b", set("b1", SetStatus.COMPLETED, 50.0), set("b2", SetStatus.PENDING), set("b3", SetStatus.PENDING)),
            exercise("c", set("c1", SetStatus.PENDING), set("c2", SetStatus.PENDING), set("c3", SetStatus.PENDING)),
        ).mapIndexed { i, e -> e.copy(order = i + 1) },
    )

    private fun adjust(vararg ops: AdjustmentOp, reason: String = "Test") =
        WorkoutAdjustment(sessionId = "s", status = AdjustmentStatus.PENDING, reason = reason, createdAt = "2026-11-04T09:30:00Z", ops = ops.toList())

    private fun op(action: String, id: String? = null, block: AdjustmentOp.() -> AdjustmentOp = { this }) =
        AdjustmentOp(action = action, sessionExerciseId = id, exerciseName = id?.let { "Übung $it" }).block()

    @Test fun setWeightPlansTheOpenSetsAndNeverTouchesLoggedOnes() {
        val before = session()
        val out = applyAdjustment(before, adjust(op(AdjustmentAction.SET_WEIGHT, "b") { copy(weightKg = 55.0) }), newId)
        assertEquals(AdjustmentStatus.APPLIED, out.status)
        val b = out.session.exercises.first { it.id == "b" }
        assertEquals(listOf(50.0, 55.0, 55.0), b.sets.map { it.weightKg })
        assertEquals(before.exercises.first { it.id == "b" }.sets.first(), b.sets.first()) // logged set untouched
        assertEquals(55.0, b.suggestedWeightKg)
        assertEquals(ASSISTANT_SUGGESTION, b.suggestionReason)
        assertEquals(55.0, prefillSet(b, emptyList()).weightKg) // the planned weight wins over the last logged one
        // the next log clears the plan, so the usual "last logged weight" rule applies again
        val afterLog = clearPlannedWeights(b.copy(sets = b.sets.map { if (it.id == "b2") it.copy(status = SetStatus.COMPLETED, weightKg = 52.5) else it }))
        assertEquals(listOf(50.0, 52.5, null), afterLog.sets.map { it.weightKg })
        assertEquals(52.5, prefillSet(afterLog, emptyList()).weightKg)
    }

    @Test fun finishedExercisesAndUnknownOnesAreSkippedWithACode() {
        val out = applyAdjustment(
            session(),
            adjust(
                op(AdjustmentAction.SET_WEIGHT, "a") { copy(weightKg = 60.0) },
                op(AdjustmentAction.SET_REST, "zzz") { copy(restSeconds = 60) },
                op(AdjustmentAction.SET_REST, "c") { copy(restSeconds = 120) },
            ),
            newId,
        )
        assertEquals(listOf("skipped", "skipped", "applied"), out.results.map { it.status })
        assertEquals(listOf(AdjustmentCode.FINISHED, AdjustmentCode.NO_EXERCISE, null), out.results.map { it.reason })
        assertEquals(AdjustmentStatus.APPLIED, out.status)
        assertEquals(120, out.session.exercises.first { it.id == "c" }.restSeconds)
        assertEquals(40.0, out.session.exercises.first { it.id == "a" }.sets.first().weightKg)
    }

    @Test fun nothingAppliedIsRejected() {
        val out = applyAdjustment(session(), adjust(op(AdjustmentAction.SET_WEIGHT, "a") { copy(weightKg = 60.0) }), newId)
        assertEquals(AdjustmentStatus.REJECTED, out.status)
        assertEquals(AdjustmentCode.NOTHING_APPLIED, out.statusReason)
        assertEquals(session(), out.session)
    }

    @Test fun aFinishedSessionIsNeverAdjusted() {
        val done = session().copy(status = SessionStatus.COMPLETED)
        val out = applyAdjustment(done, adjust(op(AdjustmentAction.SET_REST, "c") { copy(restSeconds = 60) }), newId)
        assertEquals(AdjustmentStatus.REJECTED, out.status)
        assertEquals(AdjustmentCode.SESSION_FINISHED, out.statusReason)
        assertEquals(done, out.session)
    }

    @Test fun setSetsAddsOpenSetsAndOnlyRemovesOpenOnes() {
        val more = applyAdjustment(session(), adjust(op(AdjustmentAction.SET_SETS, "b") { copy(sets = 5) }), newId).session.exercises.first { it.id == "b" }
        assertEquals(5, more.sets.size)
        assertEquals(5, more.plannedSets)
        assertEquals(listOf(1, 2, 3, 4, 5), more.sets.map { it.order })
        assertEquals(SetStatus.COMPLETED, more.sets.first().status)

        val fewer = applyAdjustment(session(), adjust(op(AdjustmentAction.SET_SETS, "b") { copy(sets = 2) }), newId).session.exercises.first { it.id == "b" }
        assertEquals(listOf("b1", "b2"), fewer.sets.map { it.id })

        val floor = applyAdjustment(session(), adjust(op(AdjustmentAction.SET_SETS, "b") { copy(sets = 1) }), newId).session.exercises.first { it.id == "b" }
        assertEquals(listOf("b1"), floor.sets.map { it.id }) // down to the logged set, never below it

        val tooLow = applyAdjustment(session().copy(exercises = session().exercises.map { if (it.id == "b") it.copy(sets = it.sets.map { s -> if (s.id == "b2") s.copy(status = SetStatus.COMPLETED) else s }) else it }), adjust(op(AdjustmentAction.SET_SETS, "b") { copy(sets = 1) }), newId)
        assertEquals(listOf("b1", "b2"), tooLow.session.exercises.first { it.id == "b" }.sets.map { it.id }.take(2))
        assertEquals(2, tooLow.session.exercises.first { it.id == "b" }.sets.count { it.status == SetStatus.COMPLETED })
    }

    @Test fun skipExerciseOnlyForUntouchedOnes() {
        val out = applyAdjustment(session(), adjust(op(AdjustmentAction.SKIP_EXERCISE, "c"), op(AdjustmentAction.SKIP_EXERCISE, "b")), newId)
        assertEquals(listOf("applied", "skipped"), out.results.map { it.status })
        assertEquals(AdjustmentCode.ALREADY_LOGGED, out.results[1].reason)
        assertTrue(de.baschek.gymapp.core.training.isSkipped(out.session.exercises.first { it.id == "c" }))
    }

    @Test fun addExerciseGoesAfterFinishedOnesAndMoveFollowsTheManualRules() {
        val added = applyAdjustment(
            session(),
            adjust(AdjustmentOp(action = AdjustmentAction.ADD_EXERCISE, exerciseId = "curls", exerciseName = "Curls", sets = 2, weightKg = 12.5, position = 1, restSeconds = 60)),
            newId,
        ).session
        assertEquals(listOf("a", "new-1", "b", "c").size, added.exercises.size)
        assertEquals("a", added.exercises[0].id) // the finished one stays first
        val curls = added.exercises[1]
        assertEquals("Curls", curls.name)
        assertEquals(listOf(12.5, 12.5), curls.sets.map { it.weightKg })
        assertEquals(listOf(1, 2, 3, 4), added.exercises.map { it.order })

        val moved = applyAdjustment(session(), adjust(op(AdjustmentAction.MOVE_EXERCISE, "c") { copy(position = 2) }), newId)
        assertEquals(listOf("a", "c", "b"), moved.session.exercises.map { it.id })
        val finishedMove = applyAdjustment(session(), adjust(op(AdjustmentAction.MOVE_EXERCISE, "a") { copy(position = 3) }), newId)
        assertEquals(AdjustmentCode.CANNOT_MOVE, finishedMove.results[0].reason)
    }

    @Test fun notesAppendAndAHintWithoutTargetCountsAsApplied() {
        val out = applyAdjustment(
            session().copy(exercises = session().exercises.map { if (it.id == "c") it.copy(notes = "Sitz 4") else it }),
            adjust(op(AdjustmentAction.NOTE, "c") { copy(text = "Langsam") }, op(AdjustmentAction.NOTE) { copy(text = "Heute kurz") }),
            newId,
        )
        assertEquals("Sitz 4\nLangsam", out.session.exercises.first { it.id == "c" }.notes)
        assertEquals(listOf("applied", "applied"), out.results.map { it.status })
    }

    @Test fun setRepsNeedsARepsTargetAndSaneValues() {
        val ok = applyAdjustment(session(), adjust(op(AdjustmentAction.SET_REPS, "c") { copy(repsMin = 5, repsMax = 8) }), newId)
        assertEquals(5.0, ok.session.exercises.first { it.id == "c" }.target.min)
        val bad = applyAdjustment(session(), adjust(op(AdjustmentAction.SET_REPS, "c") { copy(repsMin = 9, repsMax = 8) }), newId)
        assertEquals(AdjustmentCode.INVALID, bad.results[0].reason)
    }

    @Test fun undoRestoresTheExercisesButKeepsWhatWasLoggedMeanwhile() {
        val before = session()
        val adjusted = applyAdjustment(
            before,
            adjust(
                op(AdjustmentAction.SET_WEIGHT, "b") { copy(weightKg = 55.0) },
                op(AdjustmentAction.SET_SETS, "c") { copy(sets = 5) },
                AdjustmentOp(action = AdjustmentAction.ADD_EXERCISE, exerciseId = "curls", exerciseName = "Curls", sets = 2),
                op(AdjustmentAction.SET_REST, "c") { copy(restSeconds = 30) },
            ),
            newId,
        ).session
        // meanwhile b2 is logged (the plan was cleared by the log)
        val later = adjusted.copy(
            exercises = adjusted.exercises.map { ex ->
                if (ex.id == "b") ex.copy(sets = ex.sets.map { if (it.id == "b2") it.copy(status = SetStatus.COMPLETED, weightKg = 52.5, reps = 9) else it }) else ex
            },
        )
        val reverted = revertAdjustment(later, before.exercises)
        assertEquals(listOf("a", "b", "c"), reverted.exercises.map { it.id }) // the added exercise is gone
        val b = reverted.exercises.first { it.id == "b" }
        assertEquals(52.5, b.sets.first { it.id == "b2" }.weightKg) // logged since: kept
        assertNull(b.sets.first { it.id == "b3" }.weightKg) // plan taken back
        assertNull(b.suggestedWeightKg)
        val c = reverted.exercises.first { it.id == "c" }
        assertEquals(3, c.sets.size)
        assertEquals(90, c.restSeconds)
        assertEquals(session().exercises.first { it.id == "a" }, reverted.exercises.first { it.id == "a" }.copy(order = 1))
    }

    @Test fun undoKeepsAnAddedExerciseThatWasStarted() {
        val before = session()
        val adjusted = applyAdjustment(before, adjust(AdjustmentOp(action = AdjustmentAction.ADD_EXERCISE, exerciseId = "curls", exerciseName = "Curls", sets = 2)), newId).session
        val added = adjusted.exercises.last()
        val started = adjusted.copy(exercises = adjusted.exercises.map { if (it.id == added.id) it.copy(sets = it.sets.mapIndexed { i, s -> if (i == 0) s.copy(status = SetStatus.COMPLETED, weightKg = 10.0) else s }) else it })
        assertEquals(4, revertAdjustment(started, before.exercises).exercises.size)
    }

    @Test fun theBannerTextNamesTheAppliedChangesAndTheReason() {
        val adjustment = adjust(
            op(AdjustmentAction.SET_WEIGHT, "b") { copy(weightKg = 52.5) },
            op(AdjustmentAction.SKIP_EXERCISE, "c"),
            reason = "Letzter Satz war leicht.",
        ).copy(opResults = listOf(de.baschek.gymapp.core.model.AdjustmentResult(0, "applied"), de.baschek.gymapp.core.model.AdjustmentResult(1, "skipped", "already_logged")))
        assertEquals("Claude hat angepasst: Übung b: 52,5 kg (Letzter Satz war leicht.)", describeAdjustment(adjustment))
    }

    @Test fun theAdjustmentDocumentKeepsUnknownFieldsAndIsIndexedByStatus() {
        val raw = buildJsonObject {
            put("session_id", "s"); put("status", "pending"); put("reason", "Test"); put("created_at", "2026-11-04T09:30:00Z")
            put("future_field", "kept")
        }
        val typed = DocumentCodec.decode(WorkoutAdjustment.serializer(), raw)
        val written = DocumentCodec.encodeMerged(WorkoutAdjustment.serializer(), typed.copy(status = AdjustmentStatus.APPLIED), raw)
        assertEquals("kept", written["future_field"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        val idx = IndexColumns.extract(Entity.WORKOUT_ADJUSTMENT, written)
        assertEquals("applied", idx.status)
        assertEquals("2026-11-04T09:30:00Z", idx.sortAt)
        assertTrue(Entity.WORKOUT_ADJUSTMENT in Entity.ALL)
        assertNotNull(GymJson)
    }

    @Test fun workoutPollingBacksOff() {
        assertEquals(45_000L, WorkoutPoll.delayMillis(0))
        assertEquals(90_000L, WorkoutPoll.delayMillis(1))
        assertEquals(180_000L, WorkoutPoll.delayMillis(2))
        assertEquals(300_000L, WorkoutPoll.delayMillis(3))
        assertEquals(300_000L, WorkoutPoll.delayMillis(50))
    }
}
