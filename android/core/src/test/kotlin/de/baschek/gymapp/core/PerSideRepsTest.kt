package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.DocumentCodec
import de.baschek.gymapp.core.model.Exercise
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.progression.estimatedOneRepMax
import de.baschek.gymapp.core.progression.prefillSet
import de.baschek.gymapp.core.progression.totalVolume
import de.baschek.gymapp.core.training.NotificationReps
import de.baschek.gymapp.core.training.PrefillLabels
import de.baschek.gymapp.core.training.RepSide
import de.baschek.gymapp.core.training.SetCorrection
import de.baschek.gymapp.core.training.SkipReason
import de.baschek.gymapp.core.training.correctSet
import de.baschek.gymapp.core.training.countedReps
import de.baschek.gymapp.core.training.describeResult
import de.baschek.gymapp.core.training.isPerSide
import de.baschek.gymapp.core.training.reopenSet
import de.baschek.gymapp.core.training.skipSet
import de.baschek.gymapp.core.training.stepReps
import de.baschek.gymapp.core.training.templateFor
import de.baschek.gymapp.core.training.totalReps
import de.baschek.gymapp.core.training.withReps
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Left/right sets (contract amendment I): two rep values, one weight, `reps` = minimum. Synthetic data only. */
class PerSideRepsTest {
    private val target = Target("reps", 8.0, 12.0, 1.0, 2.0)

    private fun ex(perSide: Boolean?, vararg sets: SetLog, metric: String = "reps") = SessionExercise(
        id = "e1", exerciseId = "rotation", name = "Rotation", order = 1, perSide = perSide,
        target = target.copy(metric = metric), sets = sets.toList(),
    )

    private fun pending(id: String, order: Int, type: String = SetType.WORKING) = SetLog(id = id, order = order, setType = type)

    private fun session(e: SessionExercise) = Session(
        gymId = "g", planName = "Plan", status = SessionStatus.ACTIVE, startedAt = "2026-10-01T10:00:00Z", exercises = listOf(e),
    )

    private fun done(id: String, left: Int, right: Int, weight: Double = 40.0) =
        pending(id, 1).withReps(ex(true), left.toDouble(), right.toDouble()).copy(status = SetStatus.COMPLETED, weightKg = weight, effort = "one_two")

    // ---- writing a set -----------------------------------------------------------------------

    @Test
    fun perSideSetStoresBothSidesAndTheMinimumAsReps() {
        val set = pending("a", 1).withReps(ex(true), 12.0, 9.0)
        assertEquals(12, set.repsLeft)
        assertEquals(9, set.repsRight)
        assertEquals(9, set.reps)
        assertEquals(9, set.countedReps)
        assertEquals(21, set.totalReps)
        // the other way round: the weaker side decides, whichever it is
        assertEquals(7, pending("b", 1).withReps(ex(true), 7.0, 11.0).reps)
    }

    @Test
    fun ordinaryExerciseKeepsPlainRepsAndClearsSides() {
        val set = pending("a", 1).copy(repsLeft = 5, repsRight = 6).withReps(ex(null), 10.0, 3.0)
        assertEquals(10, set.reps)
        assertNull(set.repsLeft)
        assertNull(set.repsRight)
        assertFalse(ex(null).isPerSide)
    }

    @Test
    fun aMissingSideTakesTheOtherOne() {
        val set = pending("a", 1).withReps(ex(true), 10.0, null)
        assertEquals(10, set.repsLeft)
        assertEquals(10, set.repsRight)
    }

    @Test
    fun onlyRepsExercisesAndWorkingSetsCanBePerSide() {
        val seconds = ex(true, metric = "seconds")
        assertFalse(seconds.isPerSide)
        assertEquals(pending("a", 1), pending("a", 1).withReps(seconds, 30.0, 30.0))
        val warm = pending("w", 1, SetType.WARMUP).withReps(ex(true), 12.0, 99.0)
        assertEquals(12, warm.reps)
        assertNull(warm.repsLeft)
        assertNull(warm.repsRight)
    }

    // ---- correction keeps both values -------------------------------------------------------

    @Test
    fun correctionKeepsBothValuesAndOneWeight() {
        val s = session(ex(true, done("a", 10, 8)))
        val out = correctSet(s, "e1", "a", SetCorrection(weightKg = 45.0, value = 11.0, effort = "limit", valueRight = 8.0))
        val set = out.exercises.single().sets.single()
        assertEquals(11, set.repsLeft)
        assertEquals(8, set.repsRight)
        assertEquals(8, set.reps)
        assertEquals(45.0, set.weightKg)
        assertEquals("limit", set.effort)
        // correcting the stronger side does not move the counted reps; the weaker side does
        val weaker = correctSet(out, "e1", "a", SetCorrection(45.0, 11.0, "limit", valueRight = 12.0))
        assertEquals(11, weaker.exercises.single().sets.single().reps)
    }

    @Test
    fun correctionOfAnOrdinarySetStillChangesPlainReps() {
        val set = pending("a", 1).copy(status = SetStatus.COMPLETED, weightKg = 20.0, reps = 10, effort = "limit")
        val out = correctSet(session(ex(null, set)), "e1", "a", SetCorrection(20.0, 12.0, "limit"))
        assertEquals(12, out.exercises.single().sets.single().reps)
        assertNull(out.exercises.single().sets.single().repsLeft)
    }

    @Test
    fun reopenAndSkipClearBothSides() {
        val s = session(ex(true, done("a", 10, 8), pending("b", 2)))
        val reopened = reopenSet(s, "e1", "a").exercises.single().sets.first()
        assertEquals(SetStatus.PENDING, reopened.status)
        assertNull(reopened.reps); assertNull(reopened.repsLeft); assertNull(reopened.repsRight)
        // a skipped pending set never carries values either
        val skipped = skipSet(s, "e1", "b", SkipReason.TIME).exercises.single().sets[1]
        assertNull(skipped.repsLeft); assertNull(skipped.repsRight)
    }

    @Test
    fun historyStepChangesOnlyTheChosenSide() {
        val e = ex(true)
        val set = done("a", 10, 8)
        val left = set.stepReps(e, 1, RepSide.LEFT)
        assertEquals(11, left.repsLeft); assertEquals(8, left.repsRight); assertEquals(8, left.reps)
        val right = left.stepReps(e, 1, RepSide.RIGHT)
        assertEquals(11, right.repsLeft); assertEquals(9, right.repsRight); assertEquals(9, right.reps)
        val down = right.stepReps(e, -1, RepSide.LEFT)
        assertEquals(10, down.repsLeft); assertEquals(9, down.repsRight); assertEquals(9, down.reps)
        // ordinary set: plain reps, never below 0
        val plain = pending("p", 1).copy(reps = 0).stepReps(ex(null), -1, null)
        assertEquals(0, plain.reps)
    }

    // ---- reading: progression, volume, display -----------------------------------------------

    @Test
    fun progressionAndOneRepMaxUseTheMinimum() {
        val set = done("a", 12, 8)
        val plain = set.copy(repsLeft = null, repsRight = null, reps = 8)
        assertEquals(estimatedOneRepMax(plain), estimatedOneRepMax(set))
        // a foreign writer that only set the sides (no `reps`) is still read as the minimum
        assertEquals(8, set.copy(reps = null).countedReps)
        // old data without sides keeps working
        assertEquals(10, pending("o", 1).copy(reps = 10).countedReps)
    }

    @Test
    fun volumeCountsBothSides() {
        val e = ex(true, done("a", 10, 8, weight = 40.0))
        assertEquals(40.0 * 18, totalVolume(e), 1e-9)
    }

    @Test
    fun resultTextShowsBothValuesLeftFirst() {
        assertEquals("40 kg × L 10 / R 8", describeResult(ex(true), done("a", 10, 8)))
        // data from before the feature in a per-side exercise falls back to the plain number
        val legacy = pending("o", 1).copy(status = SetStatus.COMPLETED, weightKg = 40.0, reps = 9)
        assertEquals("40 kg × 9", describeResult(ex(true), legacy))
        assertEquals("40 kg × 9", describeResult(ex(null), legacy))
    }

    @Test
    fun prefillStartsEachSideFromItsOwnLastValue() {
        val e = ex(true, done("a", 10, 8), pending("b", 2))
        val pre = prefillSet(e, emptyList())
        assertEquals(10.0, pre.value)
        assertEquals(8.0, pre.valueRight)
        // first set: top of the target on both sides
        val first = prefillSet(ex(true, pending("a", 1)), emptyList())
        assertEquals(12.0, first.value); assertEquals(12.0, first.valueRight)
        assertNull(prefillSet(ex(null, pending("a", 1)), emptyList()).valueRight)
    }

    @Test
    fun notificationTextShowsBothSides() {
        val labels = PrefillLabels("kg", "Wdh", "s", "m", "Am Limit", "1–2 übrig", "3–4 übrig", "5+ übrig")
        assertEquals("40 kg × L 10 / R 8 · 1–2 übrig", NotificationReps.summary(40.0, 10.0, "reps", "one_two", labels, 8.0))
        assertEquals("40 kg × 10 · 1–2 übrig", NotificationReps.summary(40.0, 10.0, "reps", "one_two", labels))
    }

    @Test
    fun templateCopiesTheFlag() {
        assertTrue(templateFor("x", "X", "load_reps", "higher", "stack", perSide = true).perSide == true)
        assertNull(templateFor("x", "X", "load_reps", "higher", "stack", perSide = false).perSide)
        assertNull(templateFor("x", "X", "load_reps", "higher", "stack").perSide)
    }

    // ---- JSON: old data, new data, unknown fields -------------------------------------------

    private val legacySession = """{"status":"completed","gym_id":"g","plan_name":"P","started_at":"2026-01-01T10:00:00Z","completed_at":"2026-01-01T11:00:00Z",
        "future_session":1,"exercises":[{"id":"e1","exercise_id":"x","name":"X","order":1,"future_ex":"keep","target":{"metric":"reps","min":8,"max":12,"rir_min":1,"rir_max":2},
        "sets":[{"id":"s1","order":1,"set_type":"working","status":"completed","weight_kg":40,"reps":10,"effort":"one_two","future_set":true}]}]}"""

    @Test
    fun oldSessionsWithoutTheFieldsDecodeAndRoundTripUnchanged() {
        val raw = GymJson.parseToJsonElement(legacySession).jsonObject
        val session = DocumentCodec.decode<Session>(raw)
        val set = session.exercises.single().sets.single()
        assertNull(session.exercises.single().perSide)
        assertNull(set.repsLeft); assertNull(set.repsRight)
        assertEquals(10, set.countedReps)
        // saving without changes adds none of the new keys (no `per_side`, `reps_left`, `reps_right`) and loses no unknown key
        val out = DocumentCodec.encodeMerged(session, raw)
        val exercise = out["exercises"]!!.jsonArray.single().jsonObject
        val rawSet = exercise["sets"]!!.jsonArray.single().jsonObject
        assertFalse("per_side" in exercise)
        assertFalse("reps_left" in rawSet || "reps_right" in rawSet)
        assertEquals("keep", exercise["future_ex"]!!.jsonPrimitive.content)
        assertEquals("true", rawSet["future_set"]!!.jsonPrimitive.content)
        assertEquals(10, rawSet["reps"]!!.jsonPrimitive.int)
    }

    @Test
    fun perSideFieldsAreWrittenAndUnknownFieldsSurvive() {
        val raw = GymJson.parseToJsonElement(legacySession).jsonObject
        val session = DocumentCodec.decode<Session>(raw)
        val edited = session.copy(exercises = session.exercises.map { e ->
            e.copy(perSide = true, sets = e.sets.map { it.withReps(e.copy(perSide = true), 12.0, 9.0) })
        })
        val out = DocumentCodec.encodeMerged(edited, raw)
        val exercise = out["exercises"]!!.jsonArray.single().jsonObject
        assertEquals("true", exercise["per_side"]!!.jsonPrimitive.content)
        assertEquals("keep", exercise["future_ex"]!!.jsonPrimitive.content)
        val set = exercise["sets"]!!.jsonArray.single().jsonObject
        assertEquals(12, set["reps_left"]!!.jsonPrimitive.int)
        assertEquals(9, set["reps_right"]!!.jsonPrimitive.int)
        assertEquals(9, set["reps"]!!.jsonPrimitive.int)
        assertEquals("true", set["future_set"]!!.jsonPrimitive.content)
        assertEquals("1", out["future_session"]!!.jsonPrimitive.content)
        // switching it off again removes the keys
        val back = DocumentCodec.encodeMerged(DocumentCodec.decode<Session>(out).let { s ->
            s.copy(exercises = s.exercises.map { e -> e.copy(perSide = null, sets = e.sets.map { it.copy(repsLeft = null, repsRight = null) }) })
        }, out)
        assertFalse("per_side" in back["exercises"]!!.jsonArray.single().jsonObject)
        assertFalse("reps_left" in back["exercises"]!!.jsonArray.single().jsonObject["sets"]!!.jsonArray.single().jsonObject)
    }

    @Test
    fun exerciseFlagIsOptionalAndKeepsUnknownFields() {
        val old: JsonObject = GymJson.parseToJsonElement("""{"name":"Old","measurement_type":"load_reps","vendor":"x"}""").jsonObject
        assertNull(DocumentCodec.decode<Exercise>(old).perSide)
        val flagged = DocumentCodec.encodeMerged(DocumentCodec.decode<Exercise>(old).copy(perSide = true), old)
        assertEquals("true", flagged["per_side"]!!.jsonPrimitive.content)
        assertEquals("x", flagged["vendor"]!!.jsonPrimitive.content)
        assertEquals(true, DocumentCodec.decode<Exercise>(flagged).perSide)
    }
}
