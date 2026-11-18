package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.AdjustmentAction
import de.baschek.gymapp.core.model.AdjustmentOp
import de.baschek.gymapp.core.model.AdjustmentStatus
import de.baschek.gymapp.core.model.LoadBasis
import de.baschek.gymapp.core.model.LoadDirection
import de.baschek.gymapp.core.model.MeasurementType
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.model.WorkoutAdjustment
import de.baschek.gymapp.core.training.DefaultTimedTarget
import de.baschek.gymapp.core.training.StopwatchState
import de.baschek.gymapp.core.training.applyAdjustment
import de.baschek.gymapp.core.training.defaultTargetFor
import de.baschek.gymapp.core.training.describeResult
import de.baschek.gymapp.core.training.isTimed
import de.baschek.gymapp.core.training.stepTargetMax
import de.baschek.gymapp.core.training.stepTargetMin
import de.baschek.gymapp.core.training.stopwatchState
import de.baschek.gymapp.core.training.targetFor
import de.baschek.gymapp.core.training.targetLine
import de.baschek.gymapp.core.training.templateFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The stopwatch of timed exercises (hold, plank): the seconds metric follows the measurement type. Synthetic data only. */
class TimedSetsTest {
    private val reps = Target(Metric.REPS, 8.0, 12.0, 1.0, 2.0)
    private val hold = Target(Metric.SECONDS, 60.0, 90.0, 1.0, 2.0)

    @Test fun anExerciseAddedDuringTheWorkoutIsMeasuredInSecondsWhenItIsTimed() {
        val template = templateFor("hold", "Synthetic Hold", MeasurementType.LOAD_DURATION, LoadDirection.HIGHER, LoadBasis.PER_HAND)
        assertEquals(Metric.SECONDS, template.target.metric)
        assertTrue(template.isTimed)
        assertEquals(Metric.SECONDS, templateFor("plank", "Synthetic Plank", MeasurementType.DURATION_ONLY, LoadDirection.NONE, LoadBasis.NONE).target.metric)
        assertEquals(reps, templateFor("press", "Synthetic Press", MeasurementType.LOAD_REPS, LoadDirection.HIGHER, LoadBasis.STACK).target)
    }

    @Test fun anAssistantAddedHoldGetsTheStopwatch() {
        val session = Session(gymId = "g", planName = "Plan", status = SessionStatus.ACTIVE, startedAt = "2026-11-04T09:00:00Z")
        var n = 0
        val adjustment = WorkoutAdjustment(
            sessionId = "s", status = AdjustmentStatus.PENDING, reason = "Test", createdAt = "2026-11-04T09:30:00Z",
            ops = listOf(AdjustmentOp(action = AdjustmentAction.ADD_EXERCISE, exerciseId = "hold", exerciseName = "Synthetic Hold", measurementType = MeasurementType.LOAD_DURATION, sets = 2)),
        )
        val added = applyAdjustment(session, adjustment) { "id-${++n}" }.session.exercises.single()
        assertTrue(added.isTimed)
    }

    @Test fun aTimedExerciseNeverKeepsARepsTargetFromThePlan() {
        // the plan editor used to create every plan exercise with 8-12 reps
        assertEquals(DefaultTimedTarget, targetFor(MeasurementType.LOAD_DURATION, reps))
        assertEquals(DefaultTimedTarget, targetFor(MeasurementType.DURATION_ONLY, reps))
        assertEquals(Metric.SECONDS, DefaultTimedTarget.metric)
    }

    @Test fun aSecondsTargetOfThePlanIsKept() {
        assertSame(hold, targetFor(MeasurementType.LOAD_DURATION, hold))
        assertEquals("Ziel 60–90 s", targetLine(hold))
    }

    @Test fun otherMeasurementTypesKeepTheirTarget() {
        assertSame(reps, targetFor(MeasurementType.LOAD_REPS, reps))
        assertSame(reps, targetFor(MeasurementType.BODYWEIGHT_REPS, reps))
        // distance + time is measured by its distance: no stopwatch from the type alone
        val meters = Target(Metric.METERS, 20.0, 40.0, 1.0, 2.0)
        assertSame(meters, targetFor(MeasurementType.LOAD_DISTANCE_DURATION, meters))
        assertEquals(reps, defaultTargetFor(MeasurementType.LOAD_DISTANCE_DURATION))
    }

    @Test fun theStopwatchStateDoesNotDependOnAnythingButExerciseSetAndRunningTimer() {
        assertEquals(StopwatchState.IDLE, stopwatchState(timedExercise = true, hasOpenSet = true, runningHere = false, runningElsewhere = false))
        assertEquals(StopwatchState.RUNNING, stopwatchState(true, true, runningHere = true, runningElsewhere = false))
        assertEquals(StopwatchState.BLOCKED, stopwatchState(true, true, runningHere = false, runningElsewhere = true))
        assertEquals(StopwatchState.NONE, stopwatchState(false, true, false, false))
        assertEquals(StopwatchState.NONE, stopwatchState(true, false, false, false))
    }

    @Test fun theLoggedTimeIsShownAsSeconds() {
        val ex = SessionExercise(id = "e", exerciseId = "hold", name = "Synthetic Hold", measurementType = MeasurementType.LOAD_DURATION, target = hold)
        val set = SetLog(id = "s", order = 1, setType = SetType.WORKING, status = SetStatus.COMPLETED, weightKg = 20.0, seconds = 72, effort = "one_two")
        assertTrue(describeResult(ex, set).contains("72 s"))
    }

    @Test fun theTargetStepsFollowTheMetric() {
        assertEquals(65.0, stepTargetMin(hold, 1).min)
        assertEquals(95.0, stepTargetMax(hold, 1).max)
        // 60 reps was the editor's limit, a hold of 90 seconds needs more
        assertEquals(600.0, stepTargetMax(hold.copy(max = 600.0), 1).max)
        // upper end follows the lower end up
        assertEquals(Target(Metric.SECONDS, 95.0, 95.0, 1.0, 2.0), stepTargetMin(hold.copy(min = 90.0, max = 90.0), 1))
        // lower end follows the upper end down
        assertEquals(Target(Metric.SECONDS, 55.0, 55.0, 1.0, 2.0), stepTargetMax(hold.copy(min = 60.0, max = 60.0), -1))
        // reps keep their step of 1 and their limit of 60
        assertEquals(9.0, stepTargetMin(reps, 1).min)
        assertEquals(60.0, stepTargetMax(reps.copy(max = 60.0), 1).max)
        assertEquals(1.0, stepTargetMin(reps.copy(min = 1.0), -1).min)
        assertFalse(stepTargetMax(reps, -1).min > stepTargetMax(reps, -1).max)
    }
}
