package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.DocumentCodec
import de.baschek.gymapp.core.model.Entity
import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.IndexColumns
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.Session
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DocumentCodecTest {
    private val raw = GymJson.parseToJsonElement(
        """
        {"name":"Synthetic plan","future_field":{"a":1},"notes":"old",
         "exercises":[{"id":"e1","exercise_id":"x","order":1,"mode_sets":{"normal":3,"fast":2,"ultra":1,"extra":9},
           "target":{"metric":"reps","min":8,"max":12,"rir_min":1,"rir_max":2,"tempo":"3-1-1"},"rest_seconds":90,"vendor_hint":"keep"}]}
        """.trimIndent(),
    ).jsonObject

    @Test
    fun unknownFieldsSurviveEditAtAllLevels() {
        val plan = DocumentCodec.decode<Plan>(raw)
        val edited = plan.copy(exercises = plan.exercises.map { it.copy(currentWeightKg = 27.5) })
        val out = DocumentCodec.encodeMerged(edited, raw)
        assertEquals("""{"a":1}""", out["future_field"].toString())
        val e = out["exercises"]!!.jsonArray[0].jsonObject
        assertEquals("keep", e["vendor_hint"]!!.jsonPrimitive.content)
        assertEquals("9", e["mode_sets"]!!.jsonObject["extra"]!!.jsonPrimitive.content)
        assertEquals("3-1-1", e["target"]!!.jsonObject["tempo"]!!.jsonPrimitive.content)
        assertEquals("27.5", e["current_weight_kg"]!!.jsonPrimitive.content)
    }

    @Test
    fun clearedKnownFieldIsRemoved() {
        val plan = DocumentCodec.decode<Plan>(raw).copy(notes = null)
        val out = DocumentCodec.encodeMerged(plan, raw)
        assertFalse("notes" in out)
    }

    @Test
    fun listItemsAreMatchedByIdEvenWhenReordered() {
        val two = GymJson.parseToJsonElement(
            """{"exercises":[{"id":"a","exercise_id":"x","keep_a":1},{"id":"b","exercise_id":"y","keep_b":2}]}""",
        ).jsonObject
        val plan = DocumentCodec.decode<Plan>(two)
        val out = DocumentCodec.encodeMerged(plan.copy(exercises = plan.exercises.reversed()), two)
        val items = out["exercises"]!!.jsonArray.map { it.jsonObject }
        assertEquals("b", items[0]["id"]!!.jsonPrimitive.content)
        assertEquals("2", items[0]["keep_b"]!!.jsonPrimitive.content)
        assertNull(items[0]["keep_a"])
    }

    @Test
    fun requiredFieldsWithDefaultValuesAreStillWritten() {
        val out = DocumentCodec.encodeMerged(Session(planName = "Synthetic", startedAt = "2026-01-01T10:00:00Z"), null)
        assertEquals("normal", out["mode"]!!.jsonPrimitive.content)
        assertEquals("active", out["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownEnumLikeValuesAreKept() {
        val s = GymJson.parseToJsonElement(
            """{"plan_name":"x","started_at":"2026-01-01T10:00:00Z","exercises":[{"id":"1","exercise_id":"e","name":"n","measurement_type":"new_kind","sets":[]}]}""",
        ).jsonObject
        val out = DocumentCodec.encodeMerged(DocumentCodec.decode<Session>(s), s)
        assertEquals("new_kind", out["exercises"]!!.jsonArray[0].jsonObject["measurement_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun indexColumnsComeFromRawData() {
        val s = GymJson.parseToJsonElement(
            """{"plan_id":"p","gym_id":"g","status":"completed","started_at":"2026-01-01T10:00:00Z","completed_at":"2026-01-01T11:00:00Z"}""",
        ) as JsonObject
        val idx = IndexColumns.extract(Entity.SESSION, s)
        assertEquals("p", idx.planId)
        assertEquals("completed", idx.status)
        assertEquals("2026-01-01T11:00:00Z", idx.sortAt)
    }
}
