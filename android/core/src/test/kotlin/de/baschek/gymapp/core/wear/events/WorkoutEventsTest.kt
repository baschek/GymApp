package de.baschek.gymapp.core.wear.events

import de.baschek.gymapp.core.model.GymJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkoutEventsTest {
    private val sessionId = "00000000-0000-4000-8000-000000000001"
    private val aId = "00000000-0000-4000-8000-000000000002"
    private val bId = "00000000-0000-4000-8000-000000000003"
    private fun doc(first: String = "pending", second: String = "pending", reps: Int = 10, completedAt: String = "a"): JsonObject =
        Json.parseToJsonElement("""{"future":{"keep":true},"exercises":[{"id":"exercise","future_ex":"keep","sets":[{"id":"one","status":"$first","reps":$reps,"completed_at":"$completedAt","future_set":42},{"id":"two","status":"$second"}]}]}""").jsonObject
    private fun event(id: String, before: JsonObject, after: JsonObject, time: Long = 1, profile: WorkoutProfile = WorkoutProfile.PERSONAL) =
        WorkoutEvent(id, if (id == aId) "phone" else "watch", time, profile, sessionId, before, after)
    private fun apply(state: ReplicaState, event: WorkoutEvent) = (WorkoutEvents.apply(state, event) as EventResult.Applied).state
    private fun set(state: ReplicaState, index: Int) = state.document["exercises"]!!.jsonArray[0].jsonObject["sets"]!!.jsonArray[index].jsonObject
    private fun start() = ReplicaState(WorkoutProfile.PERSONAL, sessionId, doc())

    @Test fun duplicateConfirmationAndReplay() {
        val base = doc(); val phone = event(aId, base, doc(first = "completed", completedAt = "one"))
        val watch = event(bId, base, doc(first = "completed", completedAt = "two"), 2)
        val merged = apply(apply(start(), phone), watch)
        assertTrue(merged.conflicts.isEmpty()); assertEquals(2, merged.document["exercises"]!!.jsonArray[0].jsonObject["sets"]!!.jsonArray.size)
        assertIs<EventResult.Replayed>(WorkoutEvents.apply(merged, watch))
        assertEquals("event_id_reused", (WorkoutEvents.apply(merged, watch.copy(occurredAtMillis = 3)) as EventResult.Rejected).reason)
    }
    @Test fun offlineDifferentSetsCommuteAndPreserveUnknowns() {
        val base = doc(); val phone = event(aId, base, doc(first = "completed")); val watch = event(bId, base, doc(second = "completed"), 2)
        val merged = apply(apply(start(), phone), watch)
        assertEquals("completed", set(merged, 0)["status"]!!.jsonPrimitive.content)
        assertEquals("completed", set(merged, 1)["status"]!!.jsonPrimitive.content)
        assertTrue(merged.conflicts.isEmpty()); assertEquals(JsonPrimitive(42), set(merged, 0)["future_set"])
        assertEquals(JsonPrimitive(true), merged.document["future"]!!.jsonObject["keep"])
        val reversed = apply(apply(start(), watch), phone)
        assertEquals(merged.document, reversed.document)
    }
    @Test fun conflictingSetPersistsAndResolvesWithoutLosingOtherSets() {
        val base = doc(); val phone = event(aId, base, doc(first = "completed", reps = 10))
        val watch = event(bId, base, doc(first = "completed", second = "completed", reps = 12), 2)
        val merged = apply(apply(start(), phone), watch)
        assertEquals(1, merged.conflicts.size); assertEquals(10, set(merged, 0)["reps"]!!.jsonPrimitive.int)
        val restored = GymJson.decodeFromString<ReplicaState>(GymJson.encodeToString(merged))
        val mine = apply(restored, WorkoutEvents.createResolution(restored, ConflictChoice.MINE, "00000000-0000-4000-8000-000000000004", "phone", 3))
        val newest = apply(restored, WorkoutEvents.createResolution(restored, ConflictChoice.NEWEST, "00000000-0000-4000-8000-000000000005", "phone", 3))
        assertEquals(10, set(mine, 0)["reps"]!!.jsonPrimitive.int)
        assertEquals(12, set(newest, 0)["reps"]!!.jsonPrimitive.int)
        assertEquals("completed", set(newest, 1)["status"]!!.jsonPrimitive.content)
        assertTrue(newest.conflicts.isEmpty())
    }
    @Test fun resolutionPropagatesAndReplayIsIdempotent() {
        val base = doc(); val phone = event(aId, base, doc(first = "completed", reps = 10))
        val watch = event(bId, base, doc(first = "completed", reps = 12), 2)
        val phoneState = apply(apply(start(), phone), watch)
        val watchState = apply(apply(start(), watch), phone)
        assertEquals(phoneState.conflicts.single().conflictId, watchState.conflicts.single().conflictId)
        val resolution = WorkoutEvents.createResolution(phoneState, ConflictChoice.MINE, "00000000-0000-4000-8000-000000000004", "phone", 3)
        val phoneResolved = apply(phoneState, resolution); val watchResolved = apply(watchState, resolution)
        assertEquals(phoneResolved.document, watchResolved.document)
        assertTrue(phoneResolved.conflicts.isEmpty()); assertTrue(watchResolved.conflicts.isEmpty())
        assertIs<EventResult.Replayed>(WorkoutEvents.apply(watchResolved, resolution))
    }
    @Test fun resolutionDoesNotOverwriteLaterEdit() {
        val base = doc(); val phone = event(aId, base, doc(first = "completed", reps = 10))
        val watch = event(bId, base, doc(first = "completed", reps = 12), 2)
        val phoneState = apply(apply(start(), phone), watch)
        val watchState = apply(apply(start(), watch), phone)
        val resolution = WorkoutEvents.createResolution(phoneState, ConflictChoice.MINE, "00000000-0000-4000-8000-000000000004", "phone", 3)
        val newer = event("00000000-0000-4000-8000-000000000005", watchState.document, doc(first = "completed", reps = 15), 4)
        val edited = apply(watchState, newer)
        val received = apply(edited, resolution)
        assertEquals(15, set(received, 0)["reps"]!!.jsonPrimitive.int)
        assertEquals(1, received.conflicts.size)
    }
    @Test fun rejectsWrongProfileAndMalformedIdentity() {
        val wrong = event(aId, doc(), doc(first = "completed"), profile = WorkoutProfile.TEST)
        assertEquals("different_profile", (WorkoutEvents.apply(start(), wrong) as EventResult.Rejected).reason)
        assertEquals("invalid_event", (WorkoutEvents.apply(start(), wrong.copy(eventId = "bad")) as EventResult.Rejected).reason)
    }
}
