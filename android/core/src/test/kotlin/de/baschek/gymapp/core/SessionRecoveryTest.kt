package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.training.SetCorrection
import de.baschek.gymapp.core.training.SetRowKind
import de.baschek.gymapp.core.training.SkipReason
import de.baschek.gymapp.core.training.applyUndo
import de.baschek.gymapp.core.training.canRemoveExercise
import de.baschek.gymapp.core.training.captureUndo
import de.baschek.gymapp.core.training.correctSet
import de.baschek.gymapp.core.training.currentExerciseIndex
import de.baschek.gymapp.core.training.deNum
import de.baschek.gymapp.core.training.effortLabel
import de.baschek.gymapp.core.training.hasSkippedSets
import de.baschek.gymapp.core.training.moveExercise
import de.baschek.gymapp.core.training.progressionEvidenceDe
import de.baschek.gymapp.core.training.removeExercise
import de.baschek.gymapp.core.training.reopenSet
import de.baschek.gymapp.core.training.resumeSkippedSets
import de.baschek.gymapp.core.training.setRows
import de.baschek.gymapp.core.training.skipRemainingSets
import de.baschek.gymapp.core.training.skipSet
import de.baschek.gymapp.core.training.upNextExerciseIndex
import de.baschek.gymapp.core.training.viewExerciseIndex
import de.baschek.gymapp.core.training.weightHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Corrections, resuming, free jumping, undo and the weight hint with its origin (synthetic data only). */
class SessionRecoveryTest {
    private val target = Target(Metric.REPS, 8.0, 12.0, 1.0, 2.0)

    private fun done(id: String, w: Double = 140.0, reps: Int = 12, effort: String? = "one_two", type: String = SetType.WORKING) =
        SetLog(id = id, setType = type, status = SetStatus.COMPLETED, weightKg = w, reps = reps, effort = effort, completedAt = "2026-11-02T09:10:00Z", setDurationSeconds = 40, restBeforeSeconds = 90)

    private fun open(id: String) = SetLog(id = id, status = SetStatus.PENDING)

    private fun ex(id: String, vararg sets: SetLog, plan: String? = "p-$id") =
        SessionExercise(id = id, exerciseId = "x-$id", name = "Ex $id", target = target, sourcePlanExerciseId = plan, sets = sets.toList())

    private fun session(vararg e: SessionExercise) = Session(
        gymId = "g", planName = "P", status = SessionStatus.ACTIVE, startedAt = "2026-11-02T09:00:00Z",
        exercises = e.mapIndexed { i, x -> x.copy(order = i + 1) },
    )

    private fun running() = session(
        ex("e1", done("a1"), done("a2", 142.5), open("a3")),
        ex("e2", open("b1"), open("b2")),
        ex("e3", done("c1"), done("c2")),
    )

    // ---- jumping ---------------------------------------------------------------------

    @Test
    fun focusWinsOverTheFirstOpenExercise() {
        val s = running()
        assertEquals(0, currentExerciseIndex(s.exercises))
        assertEquals(0, viewExerciseIndex(s.exercises, null))
        assertEquals(1, viewExerciseIndex(s.exercises, "e2"))
        assertEquals(2, viewExerciseIndex(s.exercises, "e3")) // finished exercises can be looked at
        assertEquals(0, viewExerciseIndex(s.exercises, "gone")) // removed exercise: fall back
    }

    @Test
    fun upNextFollowsTheFocusOnlyWhileItHasOpenSets() {
        val s = running()
        assertEquals(1, upNextExerciseIndex(s.exercises, "e2"))
        assertEquals(0, upNextExerciseIndex(s.exercises, "e3"))
        assertEquals(0, upNextExerciseIndex(s.exercises, null))
    }

    @Test
    fun finishedExercisesAreFixedButOpenOnesMoveFreely() {
        val s = running()
        assertEquals(s, moveExercise(s, "e3", -1))
        assertEquals(listOf("e2", "e1", "e3"), moveExercise(s, "e2", -1).exercises.map { it.id })
        assertEquals(s, moveExercise(s, "e1", -1)) // out of range
    }

    // ---- correcting ------------------------------------------------------------------

    @Test
    fun correctingALoggedSetChangesValuesButKeepsTimings() {
        val s = correctSet(running(), "e1", "a1", SetCorrection(weightKg = 137.5, value = 10.0, effort = "three_four"))
        val set = s.exercises[0].sets[0]
        assertEquals(137.5, set.weightKg)
        assertEquals(10, set.reps)
        assertEquals("three_four", set.effort)
        assertEquals(SetStatus.COMPLETED, set.status)
        assertEquals(40, set.setDurationSeconds)
        assertEquals("2026-11-02T09:10:00Z", set.completedAt)
    }

    @Test
    fun correctionUsesTheMetricOfTheTarget() {
        val timed = session(ex("t", done("t1", 20.0, 0)).copy(target = Target(Metric.SECONDS, 30.0, 45.0, 1.0, 2.0)))
        val s = correctSet(timed, "t", "t1", SetCorrection(20.0, 40.0, "one_two"))
        assertEquals(40, s.exercises[0].sets[0].seconds)
        assertEquals(0, s.exercises[0].sets[0].reps)
    }

    @Test
    fun openSetsAreNotChangedByACorrection() {
        val s = running()
        assertEquals(s, correctSet(s, "e1", "a3", SetCorrection(200.0, 5.0, "limit")))
    }

    @Test
    fun warmupKeepsItsEmptyEffort() {
        val s = session(ex("e1", done("w1", 60.0, 12, null, SetType.WARMUP)))
        val c = correctSet(s, "e1", "w1", SetCorrection(70.0, 12.0, "limit"))
        assertNull(c.exercises[0].sets[0].effort)
        assertEquals(70.0, c.exercises[0].sets[0].weightKg)
    }

    // ---- resuming --------------------------------------------------------------------

    @Test
    fun reopeningALoggedSetClearsItsResult() {
        val set = reopenSet(running(), "e1", "a1").exercises[0].sets[0]
        assertEquals(SetStatus.PENDING, set.status)
        assertNull(set.weightKg)
        assertNull(set.reps)
        assertNull(set.effort)
        assertNull(set.completedAt)
        assertNull(set.restBeforeSeconds)
    }

    @Test
    fun skippedSetsComeBack() {
        val skipped = skipSet(skipSet(running(), "e2", "b1", SkipReason.TIME), "e2", "b2", SkipReason.PAIN)
        assertTrue(hasSkippedSets(skipped.exercises[1]))
        val resumed = resumeSkippedSets(skipped, "e2")
        assertTrue(resumed.exercises[1].sets.all { it.status == SetStatus.PENDING })
        assertFalse(hasSkippedSets(resumed.exercises[1]))
        // logged sets of a partly done exercise are kept
        val partial = skipSet(running(), "e1", "a3", SkipReason.OTHER)
        val back = resumeSkippedSets(partial, "e1")
        assertEquals(listOf(SetStatus.COMPLETED, SetStatus.COMPLETED, SetStatus.PENDING), back.exercises[0].sets.map { it.status })
        assertEquals(140.0, back.exercises[0].sets[0].weightKg)
    }

    @Test
    fun theRestOfAnExerciseCanBeSkippedAtOnce() {
        val s = skipRemainingSets(running(), "e2", SkipReason.EQUIPMENT)
        assertTrue(s.exercises[1].sets.all { it.status == SetStatus.SKIPPED_EQUIPMENT })
        val partial = skipRemainingSets(running(), "e1", SkipReason.TIME)
        assertEquals(listOf(SetStatus.COMPLETED, SetStatus.COMPLETED, SetStatus.SKIPPED_TIME), partial.exercises[0].sets.map { it.status })
    }

    // ---- removing and undo -----------------------------------------------------------

    @Test
    fun onlyAddedUntouchedExercisesCanBeRemoved() {
        val added = ex("n1", open("n1a"), plan = null)
        val s = running().copy(exercises = running().exercises + added.copy(order = 4))
        assertTrue(canRemoveExercise(added))
        assertFalse(canRemoveExercise(s.exercises[1])) // plan exercise: skip instead
        assertFalse(canRemoveExercise(added.copy(sets = listOf(done("z")))))
        val removed = removeExercise(s, "n1")
        assertEquals(listOf("e1", "e2", "e3"), removed.exercises.map { it.id })
        assertEquals(s, removeExercise(s, "e2"))
    }

    @Test
    fun undoRestoresALoggedSetAndKeepsOtherExercises() {
        val before = running()
        val entry = assertNotNull(captureUndo(before, "e2", "Satz 1 geloggt"))
        val logged = correctSet(before.copy(exercises = before.exercises.map { if (it.id == "e2") it.copy(sets = listOf(done("b1"), open("b2"))) else it }), "e2", "b1", SetCorrection(100.0, 10.0, "one_two"))
        val moved = moveExercise(logged, "e1", 1)
        val undone = applyUndo(moved, entry)
        assertEquals(listOf(SetStatus.PENDING, SetStatus.PENDING), undone.exercises.first { it.id == "e2" }.sets.map { it.status })
        assertEquals(moved.exercises.map { it.id }, undone.exercises.map { it.id }) // order untouched
    }

    @Test
    fun undoPutsARemovedExerciseBack() {
        val added = ex("n1", open("n1a"), plan = null)
        val s = running().copy(exercises = running().exercises.let { it.take(1) + added + it.drop(1) }).let { it.copy(exercises = it.exercises.mapIndexed { i, e -> e.copy(order = i + 1) }) }
        val entry = assertNotNull(captureUndo(s, "n1", "Übung entfernt"))
        val removed = removeExercise(s, "n1")
        assertEquals(3, removed.exercises.size)
        val back = applyUndo(removed, entry)
        assertEquals(listOf("e1", "n1", "e2", "e3"), back.exercises.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), back.exercises.map { it.order })
    }

    @Test
    fun undoOfAnUnskippedExerciseTurnsItBack() {
        val s = running()
        val entry = assertNotNull(captureUndo(s, "e2", "Übung übersprungen"))
        val skipped = skipRemainingSets(s, "e2", SkipReason.OTHER)
        assertTrue(skipped.exercises[1].sets.none { it.status == SetStatus.PENDING })
        assertTrue(applyUndo(skipped, entry).exercises[1].sets.all { it.status == SetStatus.PENDING })
        assertNull(captureUndo(s, "nope", "x"))
    }

    // ---- set list --------------------------------------------------------------------

    @Test
    fun setListShowsDoneCurrentAndOpenRows() {
        val rows = setRows(running().exercises[0].copy(sets = listOf(done("a1"), open("a2"), open("a3"))))
        assertEquals(listOf(SetRowKind.DONE, SetRowKind.CURRENT, SetRowKind.OPEN), rows.map { it.kind })
        assertEquals("140 kg × 12", rows[0].primary)
        assertEquals("1–2 übrig", rows[0].secondary)
        assertEquals("Ziel 8–12", rows[1].primary)
        assertEquals("1", rows[0].label)
    }

    @Test
    fun setListLabelsWarmupsAndSkippedSets() {
        val e = ex("e", done("w", 60.0, 12, null, SetType.WARMUP), done("s1"), SetLog(id = "s2", status = SetStatus.SKIPPED_TIME), SetLog(id = "s3", status = SetStatus.PAIN), open("s4"))
        val rows = setRows(e)
        assertEquals(listOf("W", "1", "2", "3", "4"), rows.map { it.label })
        assertEquals(SetRowKind.SKIPPED, rows[2].kind)
        assertEquals("Zeit", rows[2].secondary)
        assertEquals("Schmerz", rows[3].secondary)
        assertEquals(SetRowKind.CURRENT, rows[4].kind)
        assertNull(rows[0].secondary)
    }

    @Test
    fun resultsAreFormattedForTheirMetric() {
        val timed = ex("t").copy(target = Target(Metric.SECONDS, 30.0, 45.0, 1.0, 2.0))
        val row = setRows(timed.copy(sets = listOf(SetLog(id = "s", status = SetStatus.COMPLETED, weightKg = 20.0, seconds = 40, effort = "limit"))))[0]
        assertEquals("20 kg × 40 s", row.primary)
        assertEquals("Am Limit", row.secondary)
        assertEquals("Ziel 30–45 s", setRows(timed.copy(sets = listOf(open("o"))))[0].primary)
        val bodyweight = ex("b").copy(sets = listOf(SetLog(id = "s", status = SetStatus.COMPLETED, reps = 15)))
        assertEquals("15 Wdh.", setRows(bodyweight)[0].primary)
    }

    @Test
    fun numbersAndEffortsAreGerman() {
        assertEquals("142,5", deNum(142.5))
        assertEquals("60", deNum(60.0))
        assertEquals("5+ übrig", effortLabel("five_plus"))
        assertEquals("3–4 übrig", effortLabel("three_four"))
        assertNull(effortLabel("mystery"))
    }

    // ---- weight hint -----------------------------------------------------------------

    private val levels = listOf(130.0, 135.0, 140.0, 142.5, 145.0, 150.0)

    @Test
    fun hintWithOriginAfterAStrongSet() {
        val e = ex("e1", done("a1", 140.0, 12, "three_four"), open("a2"))
        val hint = assertNotNull(weightHint(e, listOf(130.0, 135.0, 140.0, 145.0, 150.0), workingWeightKg = 140.0))
        assertEquals(145.0, hint.weightKg)
        assertEquals(1, hint.trend)
        assertEquals("▲", hint.arrow)
        assertEquals("145 kg übernehmen", hint.chipText)
        assertEquals("Letzter Satz 12 × bei 3–4 übrig → nächste Stufe", hint.reason)
        assertTrue(hint.detail.contains("145 kg"))
        assertTrue(hint.evidence.startsWith("0 von 2"))
    }

    @Test
    fun aMiddlingSetKeepsTheWeight() {
        val e = ex("e1", done("a1", 140.0, 12, "one_two"), open("a2"))
        val hint = assertNotNull(weightHint(e, levels, workingWeightKg = 140.0))
        assertEquals(140.0, hint.weightKg)
        assertEquals(0, hint.trend)
        assertEquals("", hint.arrow)
        assertEquals("Letzter Satz 12 × bei 1–2 übrig → Gewicht halten", hint.reason)
    }

    @Test
    fun hintCanKeepOrLowerTheWeight() {
        val hard = ex("e1", done("a1", 140.0, 8, "limit"), open("a2"))
        val h = assertNotNull(weightHint(hard, levels, 140.0))
        assertTrue(h.weightKg <= 140.0)
        assertTrue(h.reason.startsWith("Letzter Satz 8 × am Limit → "))
        if (h.trend == 0) assertEquals("", h.arrow)
    }

    @Test
    fun noHintWithoutAWorkingSetOrEffort() {
        assertNull(weightHint(ex("e1", open("a1")), levels, null))
        assertNull(weightHint(ex("e1", done("a1", 140.0, 12, null)), levels, null))
        assertNull(weightHint(ex("e1", done("w", 60.0, 12, "one_two", SetType.WARMUP)), levels, null))
    }

    @Test
    fun evidenceCountsQualifyingSetsTowardsAPermanentWeight() {
        val one = ex("e1", done("a1", 145.0, 10, "one_two"), open("a2"))
        assertEquals(
            "1 von 2 Sätzen für dauerhaft 145 kg (bisher 140 kg). Ein zweiter passender Satz macht es beim Beenden dauerhaft.",
            progressionEvidenceDe(one, 140.0),
        )
        val two = ex("e1", done("a1", 145.0, 10, "one_two"), done("a2", 145.0, 9, "one_two"))
        assertTrue(progressionEvidenceDe(two, 140.0).startsWith("145 kg ist durch zwei passende Arbeitssätze belegt"))
        assertTrue(progressionEvidenceDe(ex("e1", done("a1", 140.0, 10, "one_two")), 140.0).startsWith("0 von 2 passenden Sätzen über 140 kg"))
        assertTrue(progressionEvidenceDe(one, null).startsWith("1 von 2 passenden Arbeitssätzen für ein dauerhaftes Startgewicht"))
    }
}
