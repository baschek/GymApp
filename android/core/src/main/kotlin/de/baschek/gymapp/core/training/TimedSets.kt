package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.MeasurementType
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.progression.metricStep

/*
 * Exercises that are measured in time (Farmer's Hold = load_duration, plank = duration_only) get a stopwatch on phone and
 * watch. The stopwatch belongs to exercises whose target metric is "seconds"; this file makes sure that the metric
 * follows the measurement type wherever an exercise or a target is created, so the stopwatch cannot disappear
 * because a target was created with the default "reps" metric (plan editor, exercise added during the workout,
 * assistant adjustment, watch).
 *
 * load_distance_duration is deliberately not timed here: its target metric is the distance (meters), and the
 * single-value set entry cannot carry distance and time at once. It can still be stopped when its target is "seconds".
 */

/** Measurement types whose set value is a duration. */
val TIMED_MEASUREMENT_TYPES: Set<String> = setOf(MeasurementType.LOAD_DURATION, MeasurementType.DURATION_ONLY)

/** Target for a timed exercise whose plan has no seconds target yet: 30-60 s, like the rep default 8-12 with 1-2 in reserve. */
val DefaultTimedTarget: Target = Target(Metric.SECONDS, 30.0, 60.0, 1.0, 2.0)

/** Default target for a new exercise of [measurementType]: seconds for timed exercises, 8-12 reps otherwise. */
fun defaultTargetFor(measurementType: String): Target =
    if (measurementType in TIMED_MEASUREMENT_TYPES) DefaultTimedTarget else Target(Metric.REPS, 8.0, 12.0, 1.0, 2.0)

/**
 * The target an exercise of [measurementType] should use. A seconds target stays as it is; a timed exercise with another
 * metric (a rep range makes no sense for a hold) gets [DefaultTimedTarget] instead, and every other measurement type
 * keeps its target.
 */
fun targetFor(measurementType: String, target: Target): Target =
    if (measurementType in TIMED_MEASUREMENT_TYPES && target.metric != Metric.SECONDS) DefaultTimedTarget else target

/** True when the exercise is measured in seconds and therefore shows the stopwatch. */
val SessionExercise.isTimed: Boolean get() = target.metric == Metric.SECONDS

/** Upper end of the target range in the plan editor: 60 reps, 10 minutes, 1000 meters. */
fun targetLimit(metric: String): Double = when (metric) {
    Metric.SECONDS -> 600.0
    Metric.METERS -> 1000.0
    else -> 60.0
}

/** Moves the lower end of the target by one step of its metric (1 rep, 5 s, 10 m); the upper end follows when needed. */
fun stepTargetMin(target: Target, direction: Int): Target {
    val step = metricStep(target.metric)
    val min = (target.min + direction * step).coerceIn(step, targetLimit(target.metric))
    return target.copy(min = min, max = maxOf(target.max, min))
}

/** Moves the upper end of the target by one step of its metric; the lower end follows when needed. */
fun stepTargetMax(target: Target, direction: Int): Target {
    val step = metricStep(target.metric)
    val max = (target.max + direction * step).coerceIn(step, targetLimit(target.metric))
    return target.copy(max = max, min = minOf(target.min, max).coerceAtLeast(step).coerceAtMost(max))
}

/** What the stopwatch area of the set screen shows. */
enum class StopwatchState {
    /** Not a timed exercise, or no open set: the normal "Satz geschafft" button. */
    NONE,
    /** Timed set, nothing running: big "Zeit starten" button (the seconds wheel stays for a manual value). */
    IDLE,
    /** The stopwatch of exactly this set runs: big time, "Stopp und Satz speichern", cancel. */
    RUNNING,
    /** A stopwatch of another set runs: this one waits until the other is finished or cancelled. */
    BLOCKED,
}

/**
 * The stopwatch state of the set screen. It depends only on the exercise (seconds metric), the open set and which set
 * the running stopwatch belongs to, never on the rest, the warm-up flag or the scroll position, so the start button
 * cannot disappear for a timed set.
 */
fun stopwatchState(timedExercise: Boolean, hasOpenSet: Boolean, runningHere: Boolean, runningElsewhere: Boolean): StopwatchState = when {
    !timedExercise || !hasOpenSet -> StopwatchState.NONE
    runningHere -> StopwatchState.RUNNING
    runningElsewhere -> StopwatchState.BLOCKED
    else -> StopwatchState.IDLE
}
