package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.DayWorkout
import de.baschek.gymapp.core.model.DayWorkoutStatus
import de.baschek.gymapp.core.model.Document
import de.baschek.gymapp.core.model.DocumentCodec
import de.baschek.gymapp.core.model.Entity
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.IndexColumns
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.ModeSets
import de.baschek.gymapp.core.model.PackItem
import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SOURCE_DAY_WORKOUT
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.stats.packRows
import de.baschek.gymapp.core.training.asPlan
import de.baschek.gymapp.core.training.dayPackKey
import de.baschek.gymapp.core.training.describeDayExercise
import de.baschek.gymapp.core.training.extraPackItems
import de.baschek.gymapp.core.training.isExpired
import de.baschek.gymapp.core.training.isOfferedOn
import de.baschek.gymapp.core.training.markDismissed
import de.baschek.gymapp.core.training.markExpired
import de.baschek.gymapp.core.training.markStarted
import de.baschek.gymapp.core.training.pickOfferedDayWorkout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayWorkoutTest {
    private fun exercise(id: String, normal: Int = 3, min: Double = 8.0, max: Double = 12.0, metric: String = Metric.REPS) =
        PlanExercise(
            id = "pe-$id", exerciseId = id, order = 1, modeSets = ModeSets(normal, 2, 1),
            target = Target(metric = metric, min = min, max = max),
        )

    private fun workout(
        date: String = "2026-11-05", status: String = DayWorkoutStatus.OFFERED, created: String = "2026-11-04T10:00:00Z",
        exercises: List<PlanExercise> = listOf(exercise("ex-1")),
    ) = DayWorkout(
        date = date, gymId = "gym-a", title = "Push im Studio", exercises = exercises, status = status, createdAt = created,
        packlist = listOf(PackItem("p2", "Handtuch", 2), PackItem("p1", "Zugbänder", 1)),
    )

    @Test
    fun offeredOnlyOnItsOwnDayAndOnlyWhileOffered() {
        val w = workout()
        assertTrue(w.isOfferedOn("2026-11-05"))
        assertFalse(w.isOfferedOn("2026-11-04"), "not yet")
        assertFalse(w.isOfferedOn("2026-11-06"), "day is over")
        for (status in listOf(DayWorkoutStatus.STARTED, DayWorkoutStatus.DISMISSED, DayWorkoutStatus.EXPIRED, DayWorkoutStatus.WITHDRAWN)) {
            assertFalse(workout(status = status).isOfferedOn("2026-11-05"), status)
        }
        assertFalse(workout(exercises = listOf(exercise("ex-1", normal = 0))).isOfferedOn("2026-11-05"), "nothing to do in normal mode")
    }

    @Test
    fun expiredMeansOfferedOfAnEarlierDay() {
        assertTrue(workout(date = "2026-11-04").isExpired("2026-11-05"))
        assertFalse(workout(date = "2026-11-05").isExpired("2026-11-05"))
        assertFalse(workout(date = "2026-11-04", status = DayWorkoutStatus.STARTED).isExpired("2026-11-05"), "a started one stays started")
        assertFalse(workout(date = "2026-11-04", status = DayWorkoutStatus.DISMISSED).isExpired("2026-11-05"))
    }

    @Test
    fun pickTakesTheNewestOfferedForToday() {
        val all = listOf(
            "old" to workout(created = "2026-11-03T08:00:00Z"),
            "new" to workout(created = "2026-11-04T12:00:00Z"),
            "gone" to workout(status = DayWorkoutStatus.DISMISSED, created = "2026-11-04T13:00:00Z"),
            "tomorrow" to workout(date = "2026-11-06"),
        )
        assertEquals("new", pickOfferedDayWorkout(all, "2026-11-05")?.first)
        assertNull(pickOfferedDayWorkout(all, "2026-11-07"))
        assertNull(pickOfferedDayWorkout(emptyList<Pair<String, DayWorkout>>(), "2026-11-05"))
    }

    @Test
    fun statusTransitionsRecordTimeAndSession() {
        val started = workout().markStarted("s-1", "2026-11-05T09:00:00Z")
        assertEquals(DayWorkoutStatus.STARTED, started.status)
        assertEquals("s-1", started.sessionId)
        assertEquals("2026-11-05T09:00:00Z", started.resolvedAt)
        assertEquals(DayWorkoutStatus.DISMISSED, workout().markDismissed("t").status)
        assertEquals(DayWorkoutStatus.EXPIRED, workout().markExpired("t").status)
    }

    @Test
    fun theWorkoutBecomesAPlanOnlyForBuildingASession() {
        val plan = workout().asPlan()
        assertEquals("Push im Studio", plan.name)
        assertEquals(listOf("ex-1"), plan.exercises.map { it.exerciseId })
    }

    @Test
    fun extraPackItemsAreSortedAndKeepTheirOwnLocalState() {
        val w = workout()
        assertEquals(listOf("Zugbänder", "Handtuch"), w.extraPackItems().map { it.text })
        assertEquals("day:doc-1", dayPackKey("doc-1"))
        val rows = packRows(w.extraPackItems(), null, "2026-11-05")
        assertEquals(listOf(false, false), rows.map { it.checked })
    }

    @Test
    fun exerciseLine() {
        assertEquals("Bankdrücken · 3 × 8–12", describeDayExercise(exercise("a"), "Bankdrücken"))
        assertEquals("Rudern · 4 × 10", describeDayExercise(exercise("a", 4, 10.0, 10.0), "Rudern"))
        assertEquals("Plank · 3 × 30–45 s", describeDayExercise(exercise("a", 3, 30.0, 45.0, Metric.SECONDS), "Plank"))
        assertEquals("ex-9 · 3 × 8–12", describeDayExercise(exercise("ex-9"), null), "unknown exercise falls back to its id")
        assertEquals("Dips", describeDayExercise(exercise("a", 0, 0.0, 0.0), "Dips"))
    }

    @Test
    fun documentRoundTripKeepsUnknownFieldsAndIndexesTheDay() {
        val raw = Json.parseToJsonElement(
            """{"date":"2026-11-05","gym_id":"gym-a","title":"Push","status":"offered","created_at":"2026-11-04T10:00:00Z",
               "future":{"x":1},
               "exercises":[{"id":"pe-1","exercise_id":"ex-1","order":1,"mode_sets":{"normal":3,"fast":2,"ultra":1},
               "target":{"metric":"reps","min":8,"max":12},"rest_seconds":90,"hint_from_future":"keep"}]}""",
        ).jsonObject
        val decoded = DocumentCodec.decode<DayWorkout>(raw)
        val written = DocumentCodec.encodeMerged(decoded.markDismissed("2026-11-05T07:00:00Z"), raw)
        assertEquals("dismissed", written["status"]!!.jsonPrimitive.content)
        assertTrue("future" in written)
        assertEquals("keep", written["exercises"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["hint_from_future"]!!.jsonPrimitive.content })
        val idx = IndexColumns.extract(Entity.DAY_WORKOUT, written)
        assertEquals("dismissed", idx.status)
        assertEquals("2026-11-05", idx.sortAt)
        assertEquals("gym-a", idx.gymId)
        assertTrue(Entity.DAY_WORKOUT in Entity.ALL)
        val doc = Document("day-2026-11-05", Entity.DAY_WORKOUT, 4, "2026-11-04T10:00:00Z", data = raw)
        assertEquals(Entity.DAY_WORKOUT, GymJson.decodeFromString(Document.serializer(), GymJson.encodeToString(Document.serializer(), doc)).entity)
    }

    @Test
    fun aSessionFromADayWorkoutIsMarkedAndKeepsUnknownFields() {
        val raw = Json.parseToJsonElement(
            """{"gym_id":"g","plan_name":"Push","mode":"normal","status":"active","started_at":"2026-11-05T09:00:00Z",
               "exercises":[],"extra":1}""",
        ).jsonObject
        val session = DocumentCodec.decode<Session>(raw).copy(source = SOURCE_DAY_WORKOUT, dayWorkoutId = "day-2026-11-05")
        val written: JsonObject = DocumentCodec.encodeMerged(session, raw)
        assertEquals("day_workout", written["source"]!!.jsonPrimitive.content)
        assertEquals("day-2026-11-05", written["day_workout_id"]!!.jsonPrimitive.content)
        assertTrue("extra" in written)
        assertFalse("plan_id" in written, "no plan id for a day workout")
    }
}
