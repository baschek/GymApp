package de.baschek.gymapp.core.stats

import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetStatus
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * "Wie lange noch?" during a workout. Pure and tested; the app only shows the result.
 *
 * What is learned: the time between two logged sets, taken from `completed_at` of every completed set (already stored and
 * synced, so no new data and no protocol change). A gap contains everything between two sets: the rest, the machine change
 * and the set itself. For every exercise the model keeps the typical gap of its first set (machine change and setup are in
 * it) and of its further sets, as the median of the last [MAX_SESSIONS_PER_EXERCISE] sessions, preferably in the same gym.
 *
 * Estimate = sum over the exercises still to come of (first gap + further gaps), where an exercise without history uses the
 * plan formula (45 s per set + its rest, 90 s setup), times today's pace: the ratio of the time today's finished sets
 * really took to the time the model expected for exactly these sets, damped while little has been done. The part of the
 * current gap that has already passed (the rest that is running) is credited.
 */

private const val SECONDS_PER_SET = 45
private const val SETUP_SECONDS = 90

/** A gap above this is a break (phone, toilet, queue) and is cut so that it does not distort the typical time. */
internal const val MAX_GAP_SECONDS = 600

internal const val MAX_SESSIONS_PER_EXERCISE = 6

/** Today's pace is clamped to this range; the weight of the evidence grows until the model expected [FULL_TRUST_SECONDS]. */
private const val PACE_MIN = 0.6
private const val PACE_MAX = 1.8
private const val FULL_TRUST_SECONDS = 600.0

/** How much the plan-formula exercises count for the pace compared to exercises with real history. */
private const val FORMULA_PACE_WEIGHT = 0.4

/** Time from the previous logged set (the session start for the first one) to one logged set. */
data class SetGap(val exerciseId: String, val seconds: Int, val first: Boolean)

private fun parseInstant(text: String?): Instant? =
    text?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/**
 * The gaps of all completed sets of [session] in the order they were logged. [SetGap.first] marks the first logged set of an
 * exercise. Sets without a readable `completed_at` are left out (their time falls into the next gap); gaps that are not
 * positive are ignored and long ones are cut to [MAX_GAP_SECONDS].
 */
fun setGaps(session: Session): List<SetGap> {
    val logged = session.exercises.flatMapIndexed { exIndex, ex ->
        ex.sets.mapNotNull { set ->
            val at = parseInstant(set.completedAt)
            if (set.status == SetStatus.COMPLETED && at != null) Triple(ex, at, exIndex * 1000 + set.order) else null
        }
    }.sortedWith(compareBy({ it.second }, { it.third }))
    var previous = parseInstant(session.startedAt)
    val seen = HashSet<String>()
    val out = ArrayList<SetGap>()
    for ((ex, at, _) in logged) {
        val first = seen.add(ex.id)
        val from = previous
        previous = at
        if (from == null) continue
        val seconds = Duration.between(from, at).seconds
        if (seconds <= 0) continue
        out += SetGap(ex.exerciseId, min(seconds, MAX_GAP_SECONDS.toLong()).toInt(), first)
    }
    return out
}

private fun median(values: List<Int>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/** Typical seconds of one exercise: [firstSeconds] for its first set, [nextSeconds] for each further set (null = never seen). */
data class ExerciseTiming(val firstSeconds: Double?, val nextSeconds: Double?, val sessions: Int)

/** What the finished sessions say about how long exercises take. Build it once per history, then estimate cheaply. */
class TimingModel internal constructor(private val byExercise: Map<String, ExerciseTiming>, val sessionsWithTiming: Int) {
    fun timingOf(exerciseId: String): ExerciseTiming? = byExercise[exerciseId]

    companion object {
        val EMPTY = TimingModel(emptyMap(), 0)
    }
}

private class Sample(val gymId: String?, val first: Int?, val next: List<Int>)

/**
 * Learns the typical timing per exercise from completed [history] sessions (other statuses are ignored). Per exercise the
 * newest [MAX_SESSIONS_PER_EXERCISE] sessions count, only those in [gymId] when there is at least one (machines and queues
 * differ between gyms).
 */
fun buildTimingModel(history: List<Session>, gymId: String?): TimingModel {
    val samples = HashMap<String, MutableList<Sample>>()
    var used = 0
    for (session in history.filter { it.status == SessionStatus.COMPLETED }.sortedByDescending { it.effectiveTime }) {
        val gaps = setGaps(session)
        if (gaps.isEmpty()) continue
        used++
        gaps.groupBy { it.exerciseId }.forEach { (exerciseId, list) ->
            samples.getOrPut(exerciseId) { ArrayList() } +=
                Sample(session.gymId, list.firstOrNull { it.first }?.seconds, list.filter { !it.first }.map { it.seconds })
        }
    }
    val byExercise = samples.mapValues { (_, all) ->
        val chosen = (if (gymId != null) all.filter { it.gymId == gymId }.ifEmpty { all } else all).take(MAX_SESSIONS_PER_EXERCISE)
        ExerciseTiming(median(chosen.mapNotNull { it.first }), median(chosen.flatMap { it.next }), chosen.size)
    }
    return TimingModel(byExercise, used)
}

/** The plan formula for one exercise (no history): seconds for the first set (with setup) and for each further set. */
private fun formulaTiming(ex: SessionExercise): Pair<Double, Double> {
    val next = (SECONDS_PER_SET + ex.restSeconds).toDouble()
    return (SETUP_SECONDS + next) to next
}

private class Costs(val first: Double, val next: Double, val fromHistory: Boolean)

private fun costsOf(model: TimingModel, ex: SessionExercise): Costs {
    val (formulaFirst, formulaNext) = formulaTiming(ex)
    val t = model.timingOf(ex.exerciseId)
    return Costs(t?.firstSeconds ?: formulaFirst, t?.nextSeconds ?: formulaNext, t != null && (t.firstSeconds != null || t.nextSeconds != null))
}

private fun pendingCount(ex: SessionExercise) = ex.sets.count { it.status == SetStatus.PENDING }
private fun doneCount(ex: SessionExercise) = ex.sets.count { it.status == SetStatus.COMPLETED }

/** Seconds for [pending] open sets of an exercise of which [done] sets are already logged (the first set carries the setup). */
private fun costOf(c: Costs, done: Int, pending: Int): Double =
    if (pending <= 0) 0.0 else if (done == 0) c.first + (pending - 1) * c.next else pending * c.next

/**
 * Result of [estimateTimeLeft]. [pace] is today's tempo against the model (1.0 = as usual, 1.2 = 20 % slower);
 * [exercisesWithHistory] of the [exercisesRemaining] exercises still to do have real timing data.
 */
data class TimeLeft(
    val seconds: Int,
    val pace: Double,
    val pendingSets: Int,
    val exercisesRemaining: Int,
    val exercisesWithHistory: Int,
    val sessionsWithTiming: Int,
) {
    /** Whole minutes for display, at least 1 while something is left. */
    val minutes: Int get() = if (seconds <= 0) 0 else maxOf(1, (seconds / 60.0).roundToInt())

    /** "ca. 23 min", "ca. 1 h 5 min" or "gleich fertig". */
    fun text(): String = formatTimeLeft(seconds)

    /** One line on what the number is based on. */
    fun basis(): String = when {
        pendingSets == 0 -> "Alle Sätze sind erledigt."
        exercisesWithHistory == 0 -> "Schätzung nach Plan: Es gibt noch keine Zeiten aus früheren Trainings."
        else -> "Gelernt aus früheren Trainings ($exercisesWithHistory von $exercisesRemaining Übungen)."
    }

    /** "Heute etwa 12 % langsamer als sonst", "... schneller ...", or null when today is about normal. */
    fun paceNote(): String? {
        val percent = ((pace - 1.0) * 100).roundToInt()
        return when {
            abs(percent) < 5 -> null
            percent > 0 -> "Heute etwa $percent % langsamer als sonst."
            else -> "Heute etwa ${-percent} % schneller als sonst."
        }
    }
}

/** "ca. 23 min" / "ca. 1 h 5 min" / "gleich fertig" for zero. */
fun formatTimeLeft(seconds: Int): String {
    if (seconds <= 0) return "gleich fertig"
    val minutes = maxOf(1, (seconds / 60.0).roundToInt())
    return "ca. " + if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/** Clock time (HH:mm) at which the workout would be done if [seconds] are left at [now]. */
fun finishClock(now: Instant, seconds: Int, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm").format(now.plusSeconds(seconds.toLong()).atZone(zone))

/** Today's pace against the model, from the sets of [session] logged so far (1.0 when nothing can be compared). */
internal fun todaysPace(model: TimingModel, session: Session): Double {
    val byId = session.exercises.associateBy { it.exerciseId }
    var expected = 0.0
    var actual = 0.0
    for (gap in setGaps(session)) {
        val ex = byId[gap.exerciseId] ?: continue
        val c = costsOf(model, ex)
        val weight = if (c.fromHistory) 1.0 else FORMULA_PACE_WEIGHT
        expected += weight * (if (gap.first) c.first else c.next)
        actual += weight * gap.seconds
    }
    if (expected <= 0.0) return 1.0
    val trust = min(1.0, expected / FULL_TRUST_SECONDS)
    return 1.0 + trust * ((actual / expected).coerceIn(PACE_MIN, PACE_MAX) - 1.0)
}

/**
 * Estimated time until all open sets of the running [session] are done, at [now].
 * [upNextExerciseId] is the exercise whose set comes next (the rest that is running leads to it); null = the first with an open
 * set. Skipped sets do not count. See the file comment for how the number is made.
 */
fun estimateTimeLeft(model: TimingModel, session: Session, now: Instant, upNextExerciseId: String? = null): TimeLeft {
    val open = session.exercises.filter { pendingCount(it) > 0 }
    val pending = open.sumOf(::pendingCount)
    if (open.isEmpty()) return TimeLeft(0, 1.0, 0, 0, 0, model.sessionsWithTiming)

    val pace = todaysPace(model, session)
    val costs = open.associateWith { costsOf(model, it) }
    val total = open.sumOf { costOf(costs.getValue(it), doneCount(it), pendingCount(it)) } * pace

    // the gap that is running now (rest, walk to the machine) has partly passed already: credit it, at most the next set
    val next = open.firstOrNull { it.id == upNextExerciseId } ?: open.first()
    val nextCost = costs.getValue(next).let { if (doneCount(next) == 0) it.first else it.next } * pace
    val lastLogged = session.exercises.flatMap { ex -> ex.sets.mapNotNull { if (it.status == SetStatus.COMPLETED) parseInstant(it.completedAt) else null } }.maxOrNull()
    val since = lastLogged ?: parseInstant(session.startedAt)
    val elapsed = since?.let { Duration.between(it, now).seconds.coerceAtLeast(0).toDouble() } ?: 0.0
    val remaining = (total - min(elapsed, nextCost)).coerceAtLeast(0.0)

    return TimeLeft(
        seconds = ceil(remaining).toInt(), pace = pace, pendingSets = pending, exercisesRemaining = open.size,
        exercisesWithHistory = open.count { costs.getValue(it).fromHistory }, sessionsWithTiming = model.sessionsWithTiming,
    )
}
