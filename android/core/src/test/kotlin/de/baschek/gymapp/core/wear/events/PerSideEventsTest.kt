package de.baschek.gymapp.core.wear.events

import kotlinx.serialization.json.*
import kotlin.test.*

/** Left/right sets travel through the existing event queue unchanged (amendment I); synthetic data only. */
class PerSideEventsTest {
    private val sessionId = "00000000-0000-4000-8000-000000000001"
    private val aId = "00000000-0000-4000-8000-000000000002"
    private val bId = "00000000-0000-4000-8000-000000000003"

    private fun doc(left: Int?, right: Int?, status: String = "pending", weight: Int? = null): JsonObject {
        val sides = if (left != null && right != null) ""","reps":${minOf(left, right)},"reps_left":$left,"reps_right":$right""" else ""
        val w = weight?.let { ""","weight_kg":$it""" }.orEmpty()
        return Json.parseToJsonElement(
            """{"exercises":[{"id":"ex","per_side":true,"sets":[{"id":"one","status":"$status"$w$sides,"future_set":1},{"id":"two","status":"pending"}]}]}""",
        ).jsonObject
    }
    private fun event(id: String, before: JsonObject, after: JsonObject, time: Long) =
        WorkoutEvent(id, if (id == aId) "phone" else "watch", time, WorkoutProfile.PERSONAL, sessionId, before, after)
    private fun apply(state: ReplicaState, event: WorkoutEvent) = (WorkoutEvents.apply(state, event) as EventResult.Applied).state
    private fun set(state: ReplicaState) = state.document["exercises"]!!.jsonArray[0].jsonObject["sets"]!!.jsonArray[0].jsonObject
    private fun start(base: JsonObject) = ReplicaState(WorkoutProfile.PERSONAL, sessionId, base)

    @Test
    fun aSetLoggedOnTheWatchArrivesOnThePhoneWithBothSides() {
        val base = doc(null, null)
        val logged = doc(12, 9, "completed", 40)
        val merged = apply(start(base), event(bId, base, logged, 1))
        assertEquals(12, set(merged)["reps_left"]!!.jsonPrimitive.int)
        assertEquals(9, set(merged)["reps_right"]!!.jsonPrimitive.int)
        assertEquals(9, set(merged)["reps"]!!.jsonPrimitive.int)
        assertEquals(40, set(merged)["weight_kg"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(1), set(merged)["future_set"])
        assertEquals(JsonPrimitive(true), merged.document["exercises"]!!.jsonArray[0].jsonObject["per_side"])
        assertTrue(merged.conflicts.isEmpty())
    }

    @Test
    fun correctingOneSideOnEachDeviceIsAConflictNotAMixedSet() {
        // the set is an atomic record: left 12 -> 13 on the phone and right 9 -> 8 on the watch must not be silently mixed
        val base = doc(12, 9, "completed", 40)
        val phone = event(aId, base, doc(13, 9, "completed", 40), 1)
        val watch = event(bId, base, doc(12, 8, "completed", 40), 2)
        val merged = apply(apply(start(base), phone), watch)
        assertEquals(1, merged.conflicts.size)
        assertEquals(13, set(merged)["reps_left"]!!.jsonPrimitive.int)
        assertEquals(9, set(merged)["reps_right"]!!.jsonPrimitive.int)
        // newest wins as a whole set: both values of the watch version
        val resolved = apply(merged, WorkoutEvents.createResolution(merged, ConflictChoice.NEWEST, "00000000-0000-4000-8000-000000000004", "phone", 3))
        assertEquals(12, set(resolved)["reps_left"]!!.jsonPrimitive.int)
        assertEquals(8, set(resolved)["reps_right"]!!.jsonPrimitive.int)
        assertEquals(8, set(resolved)["reps"]!!.jsonPrimitive.int)
    }

    @Test
    fun aCorrectionAfterTheOtherDeviceSawTheSetPropagatesBothValues() {
        val base = doc(12, 9, "completed", 40)
        val corrected = doc(12, 11, "completed", 42)
        val merged = apply(start(base), event(aId, base, corrected, 1))
        assertEquals(11, set(merged)["reps_right"]!!.jsonPrimitive.int)
        assertEquals(11, set(merged)["reps"]!!.jsonPrimitive.int)
        assertEquals(42, set(merged)["weight_kg"]!!.jsonPrimitive.int)
    }
}
