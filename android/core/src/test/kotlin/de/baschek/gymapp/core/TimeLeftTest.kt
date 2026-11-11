package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.stats.TimingModel
import de.baschek.gymapp.core.stats.buildTimingModel
import de.baschek.gymapp.core.stats.estimateTimeLeft
import de.baschek.gymapp.core.stats.finishClock
import de.baschek.gymapp.core.stats.formatTimeLeft
import de.baschek.gymapp.core.stats.setGaps
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Time-left estimate and the timing model it learns (synthetic data only). */
class TimeLeftTest {
    private val day0 = Instant.parse("2026-10-01T10:00:00Z")

    /** An exercise with the gaps (seconds since the previous logged set) of its completed sets, then [pending] open sets. */
    private data class Ex(val exerciseId: String, val gaps: List<Int>, val pending: Int = 0, val rest: Int = 60)

    private var next = 0

    /** Builds a session that starts at [start]; the sets of each exercise are logged one after the other. Returns it with the time of the last logged set. */
    private fun build(start: Instant, vararg exercises: Ex, gym: String = "g", status: String = SessionStatus.COMPLETED): Pair<Session, Instant> {
        var t = start
        val list = exercises.map { e ->
            val done = e.gaps.mapIndexed { i, gap ->
                t = t.plusSeconds(gap.toLong())
                SetLog(id = "set-${next++}", order = i + 1, status = SetStatus.COMPLETED, completedAt = t.toString())
            }
            val open = (1..e.pending).map { SetLog(id = "set-${next++}", order = done.size + it, status = SetStatus.PENDING) }
            SessionExercise(id = "se-${e.exerciseId}", exerciseId = e.exerciseId, name = e.exerciseId, restSeconds = e.rest, sets = done + open)
        }
        val session = Session(
            gymId = gym, planName = "P", status = status, startedAt = start.toString(),
            completedAt = if (status == SessionStatus.COMPLETED) t.toString() else null, exercises = list,
        )
        return session to t
    }

    private fun history(vararg sessions: Session) = buildTimingModel(sessions.toList(), "g")

    @Test
    fun gapsAreTimeBetweenLoggedSetsAndMarkTheFirstSetOfAnExercise() {
        val (s, _) = build(day0, Ex("a", listOf(120, 80, 70)), Ex("b", listOf(150, 90)))
        assertEquals(listOf(120, 80, 70, 150, 90), setGaps(s).map { it.seconds })
        assertEquals(listOf(true, false, false, true, false), setGaps(s).map { it.first })
    }

    @Test
    fun longBreaksAreCutAndSetsWithoutTimestampAreIgnored() {
        val (s, _) = build(day0, Ex("a", listOf(100, 5000, 90)))
        assertEquals(listOf(100, 600, 90), setGaps(s).map { it.seconds })
        val noStamp = s.copy(exercises = s.exercises.map { ex -> ex.copy(sets = ex.sets.map { it.copy(completedAt = null) }) })
        assertTrue(setGaps(noStamp).isEmpty())
        // the missing stamp of the middle set folds its time into the next gap
        val partial = s.copy(exercises = s.exercises.map { ex -> ex.copy(sets = ex.sets.mapIndexed { i, set -> if (i == 1) set.copy(completedAt = null) else set }) })
        assertEquals(listOf(100, 600), setGaps(partial).map { it.seconds }.take(2))
    }

    @Test
    fun modelTakesTheMedianOfTheLastSessionsAndPrefersTheSameGym() {
        val a = build(day0, Ex("x", listOf(100, 60, 60))).first
        val b = build(day0.plusSeconds(86400), Ex("x", listOf(140, 80, 80))).first
        val c = build(day0.plusSeconds(2 * 86400), Ex("x", listOf(120, 70, 70))).first
        val other = build(day0.plusSeconds(3 * 86400), Ex("x", listOf(400, 300, 300)), gym = "elsewhere").first
        val t = buildTimingModel(listOf(a, b, c, other), "g").timingOf("x")!!
        assertEquals(120.0, t.firstSeconds)
        assertEquals(70.0, t.nextSeconds)
        assertEquals(3, t.sessions)
        // no session in that gym: all sessions count
        assertEquals(300.0, buildTimingModel(listOf(other), "g").timingOf("x")!!.nextSeconds)
        assertNull(buildTimingModel(listOf(a), "g").timingOf("unknown"))
    }

    @Test
    fun onlyTheNewestSixSessionsPerExerciseCountSoTheEstimateFollowsChanges() {
        val old = (0 until 6).map { build(day0.plusSeconds(it * 86400L), Ex("x", listOf(100, 60, 60))).first }
        val recent = (6 until 12).map { build(day0.plusSeconds(it * 86400L), Ex("x", listOf(200, 120, 120))).first }
        assertEquals(120.0, buildTimingModel(old + recent, "g").timingOf("x")!!.nextSeconds)
        assertEquals(60.0, buildTimingModel(old, "g").timingOf("x")!!.nextSeconds)
    }

    @Test
    fun activeAndUnstampedSessionsTeachNothing() {
        val (active, _) = build(day0, Ex("x", listOf(100, 60)), status = SessionStatus.ACTIVE)
        val imported = build(day0, Ex("x", listOf(100, 60))).first.let { s ->
            s.copy(exercises = s.exercises.map { ex -> ex.copy(sets = ex.sets.map { it.copy(completedAt = null) }) })
        }
        val model = buildTimingModel(listOf(active, imported), "g")
        assertEquals(0, model.sessionsWithTiming)
        assertNull(model.timingOf("x"))
    }

    @Test
    fun withoutHistoryTheEstimateIsThePlanFormula() {
        // 3 open sets, rest 60: 90 s setup + 3 x (45 s set + 60 s rest)
        val (s, _) = build(day0, Ex("x", emptyList(), pending = 3, rest = 60), status = SessionStatus.ACTIVE)
        val left = estimateTimeLeft(TimingModel.EMPTY, s, day0)
        assertEquals(405, left.seconds)
        assertEquals(1.0, left.pace)
        assertEquals(0, left.exercisesWithHistory)
        assertEquals(1, left.exercisesRemaining)
        assertEquals(3, left.pendingSets)
        assertTrue(left.basis().startsWith("Schätzung nach Plan"))
        assertNull(left.paceNote())
    }

    @Test
    fun historyReplacesTheFormulaPerExercise() {
        val model = history(build(day0, Ex("x", listOf(120, 80, 80)), Ex("y", listOf(100, 50))).first)
        // x: three open sets, never started -> first 120 + 2 x 80; y: formula (no history), 2 sets rest 90 -> 90 + 2 x 135
        val (s, _) = build(day0.plusSeconds(86400), Ex("x", emptyList(), pending = 3), Ex("z", emptyList(), pending = 2, rest = 90), status = SessionStatus.ACTIVE)
        val left = estimateTimeLeft(model, s, day0.plusSeconds(86400))
        assertEquals(120 + 160 + 90 + 270, left.seconds)
        assertEquals(1, left.exercisesWithHistory)
        assertEquals(2, left.exercisesRemaining)
        assertEquals("Gelernt aus früheren Trainings (1 von 2 Übungen).", left.basis())
    }

    @Test
    fun aStartedExerciseNoLongerPaysItsSetup() {
        val model = history(build(day0, Ex("x", listOf(120, 80, 80))).first)
        val (s, last) = build(day0.plusSeconds(86400), Ex("x", listOf(120), pending = 2), status = SessionStatus.ACTIVE)
        // pace 1.0 (the one logged set took what it should); two further sets of 80 s, nothing of the running gap passed yet
        assertEquals(160, estimateTimeLeft(model, s, last).seconds)
    }

    @Test
    fun aSlowDayRaisesTheEstimateAndAFastDayLowersIt() {
        val model = history(build(day0, Ex("x", listOf(300, 200, 200)), Ex("y", listOf(100, 60, 60))).first)
        fun left(vararg gaps: Int): Pair<Double, Int> {
            val (s, last) = build(day0.plusSeconds(86400), Ex("x", gaps.toList()), Ex("y", emptyList(), pending = 3), status = SessionStatus.ACTIVE)
            val e = estimateTimeLeft(model, s, last)
            return e.pace to e.seconds
        }
        val (normalPace, normal) = left(300, 200, 200)
        val (slowPace, slow) = left(450, 300, 300) // expected 700 s, took 1050 s: 50 % slower
        val (fastPace, fast) = left(150, 100, 100) // 50 % faster, clamped to 0.6
        assertEquals(1.0, normalPace)
        assertEquals(100 + 120, normal)
        assertEquals(1.5, slowPace)
        assertEquals(330, slow)
        assertEquals(0.6, fastPace)
        assertEquals(132, fast)
        assertTrue(slow > normal && normal > fast)
    }

    @Test
    fun thePaceNeedsEnoughEvidenceBeforeItMovesTheEstimate() {
        val model = history(build(day0, Ex("x", listOf(300, 200, 200)), Ex("y", listOf(100, 60, 60))).first)
        // one set done, 100 s late: expected 300 s of 600 s -> trust 0.5 -> pace 1 + 0.5 x (400/300 - 1)
        val (s, last) = build(day0.plusSeconds(86400), Ex("x", listOf(400), pending = 2), status = SessionStatus.ACTIVE)
        val pace = estimateTimeLeft(model, s, last).pace
        assertEquals(1.0 + 0.5 * (400.0 / 300.0 - 1.0), pace, 1e-9)
        assertEquals("Heute etwa 17 % langsamer als sonst.", estimateTimeLeft(model, s, last).paceNote())
    }

    @Test
    fun theRunningRestIsCreditedButNeverMoreThanTheNextSet() {
        val model = history(build(day0, Ex("x", listOf(120, 80, 80))).first)
        val (s, last) = build(day0.plusSeconds(86400), Ex("x", listOf(120), pending = 2), status = SessionStatus.ACTIVE)
        assertEquals(160 - 30, estimateTimeLeft(model, s, last.plusSeconds(30)).seconds)
        // a long break: the next set is credited only once, the rest stays
        assertEquals(80, estimateTimeLeft(model, s, last.plusSeconds(3000)).seconds)
    }

    @Test
    fun skippedSetsAreNotLeftAndAFinishedWorkoutHasNothingLeft() {
        val (s, last) = build(day0, Ex("x", listOf(100, 60)), status = SessionStatus.ACTIVE)
        val skipped = s.copy(exercises = s.exercises.map { it.copy(sets = it.sets + SetLog(id = "sk", order = 3, status = SetStatus.SKIPPED_TIME)) })
        val left = estimateTimeLeft(TimingModel.EMPTY, skipped, last)
        assertEquals(0, left.seconds)
        assertEquals(0, left.pendingSets)
        assertEquals("gleich fertig", left.text())
        assertEquals("Alle Sätze sind erledigt.", left.basis())
    }

    @Test
    fun theUpNextExerciseSetsWhichRunningRestIsCredited() {
        val model = history(build(day0, Ex("x", listOf(120, 80)), Ex("y", listOf(200, 100))).first)
        val (s, last) = build(day0.plusSeconds(86400), Ex("x", emptyList(), pending = 2), Ex("y", emptyList(), pending = 2), status = SessionStatus.ACTIVE)
        val total = 120 + 80 + 200 + 100
        assertEquals(total - 120, estimateTimeLeft(model, s, last.plusSeconds(500)).seconds) // default: first open exercise (120 s)
        assertEquals(total - 200, estimateTimeLeft(model, s, last.plusSeconds(500), upNextExerciseId = "se-y").seconds)
    }

    @Test
    fun displayTexts() {
        assertEquals("gleich fertig", formatTimeLeft(0))
        assertEquals("ca. 1 min", formatTimeLeft(20))
        assertEquals("ca. 23 min", formatTimeLeft(23 * 60 + 10))
        assertEquals("ca. 1 h 5 min", formatTimeLeft(65 * 60))
        assertEquals("18:42", finishClock(Instant.parse("2026-10-01T18:00:00Z"), 42 * 60, ZoneOffset.UTC))
        assertNotNull(estimateTimeLeft(TimingModel.EMPTY, build(day0, Ex("x", emptyList(), pending = 1), status = SessionStatus.ACTIVE).first, day0).text())
    }
}
