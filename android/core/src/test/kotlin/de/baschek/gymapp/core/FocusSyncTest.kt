package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.DocumentCodec
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.training.withSyncedFocus
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Amendment F: the focused exercise travels in the session document as `focus_exercise_id`. */
class FocusSyncTest {
    private fun exercise(id: String) = SessionExercise(id = id, exerciseId = "x-$id", name = "Übung $id")

    private val session = Session(
        gymId = "gym", planName = "Plan", status = SessionStatus.ACTIVE, startedAt = "2026-11-04T09:00:00Z",
        exercises = listOf(exercise("a"), exercise("b")),
    )

    @Test fun focusOnAnExerciseOfTheSessionIsStored() {
        assertEquals("b", withSyncedFocus(session, "b").focusExerciseId)
    }

    @Test fun unchangedFocusReturnsTheSameInstance() {
        assertSame(session, withSyncedFocus(session, null))
        val focused = withSyncedFocus(session, "a")
        assertSame(focused, withSyncedFocus(focused, "a"))
    }

    @Test fun anUnknownOrClearedFocusIsNone() {
        val focused = withSyncedFocus(session, "a")
        assertNull(withSyncedFocus(focused, null).focusExerciseId)
        assertNull(withSyncedFocus(focused, "gone").focusExerciseId)
    }

    @Test fun theFieldIsWrittenWhenSetRemovedWhenClearedAndOldDocumentsStillLoad() {
        val raw = GymJson.parseToJsonElement("""{"gym_id":"gym","plan_name":"P","status":"active","started_at":"t","server_extra":1,"exercises":[]}""").jsonObject
        val old = DocumentCodec.decode<Session>(raw)
        assertNull(old.focusExerciseId)
        val withFocus = DocumentCodec.encodeMerged(old.copy(focusExerciseId = "a"), raw)
        assertTrue("\"focus_exercise_id\":\"a\"" in withFocus.toString() && "server_extra" in withFocus.toString())
        val cleared = DocumentCodec.encodeMerged(old.copy(focusExerciseId = null), withFocus)
        assertFalse("focus_exercise_id" in cleared.toString())
    }
}
