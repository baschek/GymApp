package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.GymJson
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.progression.ExerciseInfo
import de.baschek.gymapp.core.progression.GymExerciseKey
import de.baschek.gymapp.core.progression.MANUAL_WEIGHT_SOURCE
import de.baschek.gymapp.core.progression.WorkingWeight
import de.baschek.gymapp.core.progression.estimateStartWeight
import de.baschek.gymapp.core.progression.recalculateWorkingWeights
import de.baschek.gymapp.core.progression.reassignSessionGym
import de.baschek.gymapp.core.progression.WeightSuggestion
import de.baschek.gymapp.core.progression.estimatedOneRepMax
import de.baschek.gymapp.core.progression.guidedStartingEstimate
import de.baschek.gymapp.core.progression.historicalWorkingWeight
import de.baschek.gymapp.core.progression.nearestAvailableWeight
import de.baschek.gymapp.core.progression.progressionEvidence
import de.baschek.gymapp.core.progression.provenWorkingWeight
import de.baschek.gymapp.core.progression.recalculatePlanProgression
import de.baschek.gymapp.core.progression.suggestNextWeight
import de.baschek.gymapp.core.progression.roundDownToCandidate
import de.baschek.gymapp.core.progression.splitTotal
import de.baschek.gymapp.core.progression.weightCandidates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the language-neutral cases in progression_golden.json
 * ({name, fn, input, expected}); the same file can be run by other implementations.
 * Expected objects are matched as subsets; a key `x_contains` checks that field `x` contains the text.
 */
class ProgressionGoldenTest {
    @Test
    fun goldenCases() {
        val text = javaClass.getResourceAsStream("/progression_golden.json")!!.readBytes().decodeToString()
        val cases = GymJson.parseToJsonElement(text).jsonArray
        assertTrue(cases.size >= 20, "golden file unexpectedly small")
        val failures = mutableListOf<String>()
        for (c in cases) {
            val o = c.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            try {
                val actual = run(o["fn"]!!.jsonPrimitive.content, o["input"]!!.jsonObject)
                check(name, o["expected"]!!, actual)
            } catch (e: Throwable) {
                failures += "$name: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private inline fun <reified T> JsonObject.typed(key: String): T = GymJson.decodeFromJsonElement(this[key]!!)
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.weights(key: String): List<Double>? =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.double }

    private fun run(fn: String, i: JsonObject): JsonElement = when (fn) {
        "nearestAvailableWeight" -> JsonPrimitive(nearestAvailableWeight(i.num("value")!!, i.weights("weights")))
        "estimatedOneRepMax" ->
            estimatedOneRepMax(i.typed<SetLog>("set"), i.num("base_load_kg") ?: 0.0).let { it?.let(::JsonPrimitive) ?: JsonNull }
        "suggestNextWeight" -> suggestion(
            suggestNextWeight(
                i.typed<SetLog>("set"), i.typed<Target>("target"), i["measurement_type"]!!.jsonPrimitive.content,
                i.weights("available_weights_kg"), i.num("body_weight_kg"),
            ),
        )
        "provenWorkingWeight" ->
            provenWorkingWeight(i.typed<SessionExercise>("exercise"), i.num("current_weight_kg"))
                .let { it?.let(::JsonPrimitive) ?: JsonNull }
        "progressionEvidence" ->
            JsonPrimitive(progressionEvidence(i.typed<SessionExercise>("exercise"), i.num("current_weight_kg")))
        "guidedStartingEstimate" -> suggestion(
            guidedStartingEstimate(
                info(i["target"]!!.jsonObject),
                i.typed<List<Session>>("sessions"),
                i["catalog"]!!.jsonArray.map { info(it.jsonObject) },
                i.weights("available_weights_kg"),
            ),
        )
        "historicalWorkingWeight" -> suggestion(
            historicalWorkingWeight(
                i["exercise_id"]!!.jsonPrimitive.content, i.typed<List<Session>>("sessions"),
                i["gym_id"]!!.jsonPrimitive.content,
            ),
        )
        "recalculatePlanProgression" -> {
            val sessions = i["sessions"]!!.jsonArray.map {
                it.jsonObject["id"]!!.jsonPrimitive.content to GymJson.decodeFromJsonElement<Session>(it.jsonObject["data"]!!)
            }
            var n = 0
            val r = recalculatePlanProgression(
                i["plan_id"]!!.jsonPrimitive.content, i.typed<Plan>("plan"), sessions,
                i["seen_session_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            ) { "m-${++n}" }
            buildJsonObject {
                put("plan", GymJson.encodeToJsonElement(Plan.serializer(), r.plan))
                put("milestones", JsonArray(r.milestones.map { GymJson.encodeToJsonElement(de.baschek.gymapp.core.model.Milestone.serializer(), it.milestone) }))
            }
        }
        "recalculateWorkingWeights" -> weightList(
            recalculateWorkingWeights(
                sessionPairs(i["sessions"]!!),
                (i["only"] as? JsonArray)?.map { GymExerciseKey(it.jsonObject["gym_id"]!!.jsonPrimitive.content, it.jsonObject["exercise_id"]!!.jsonPrimitive.content) }?.toSet(),
                (i["baselines"] as? JsonArray).orEmpty().associate {
                    val b = it.jsonObject
                    GymExerciseKey(b["gym_id"]!!.jsonPrimitive.content, b["exercise_id"]!!.jsonPrimitive.content) to
                        WorkingWeight(b["weight_kg"]!!.jsonPrimitive.double, MANUAL_WEIGHT_SOURCE, b["updated_at"]!!.jsonPrimitive.content)
                },
            ),
        )
        "reassignSessionGym" -> weightList(
            reassignSessionGym(sessionPairs(i["sessions"]!!), i["session_id"]!!.jsonPrimitive.content, i["new_gym_id"]!!.jsonPrimitive.content),
        )
        "estimateStartWeight" -> estimateStartWeight(
            i["exercise_id"]!!.jsonPrimitive.content, i["target_gym_id"]!!.jsonPrimitive.content,
            sessionPairs(i["sessions"]!!), i["exercises"]!!.jsonArray.map { info(it.jsonObject) }, i.weights("target_levels"),
        ).let { s ->
            if (s == null) JsonNull else buildJsonObject {
                put("weight_kg", s.weightKg)
                put("reason", s.reason)
                put("source_gym_id", s.sourceGymId)
            }
        }
        "weightCandidates" -> JsonArray(weightCandidates(i.weights("levels")!!, i.weights("addons")!!).map(::JsonPrimitive))
        "roundDownToCandidate" -> JsonPrimitive(roundDownToCandidate(i.num("value")!!, i.weights("levels")!!, i.weights("addons")!!))
        "splitTotal" -> splitTotal(i.num("total")!!, i.weights("levels")!!, i.weights("addons")!!).let { s ->
            if (s == null) JsonNull else buildJsonObject {
                put("level_kg", s.levelKg)
                put("addons_kg", JsonArray(s.addonsKg.map(::JsonPrimitive)))
            }
        }
        else -> fail("unknown fn $fn")
    }

    private fun sessionPairs(e: JsonElement): List<Pair<String, Session>> = e.jsonArray.map {
        it.jsonObject["id"]!!.jsonPrimitive.content to GymJson.decodeFromJsonElement<Session>(it.jsonObject["data"]!!)
    }

    private fun weightList(m: Map<GymExerciseKey, WorkingWeight?>): JsonElement = JsonArray(
        m.entries.sortedWith(compareBy({ it.key.gymId }, { it.key.exerciseId })).map { (k, w) ->
            buildJsonObject {
                put("gym_id", k.gymId)
                put("exercise_id", k.exerciseId)
                put("weight_kg", w?.weightKg)
                put("source_session_id", w?.sourceSessionId)
            }
        },
    )

    private fun info(o: JsonObject): ExerciseInfo = ExerciseInfo(
        id = o["id"]!!.jsonPrimitive.content,
        name = o["name"]?.jsonPrimitive?.content ?: "",
        measurementType = o["measurement_type"]?.jsonPrimitive?.content ?: "load_reps",
        loadDirection = o["load_direction"]?.jsonPrimitive?.content ?: "higher",
        loadBasis = o["load_basis"]?.jsonPrimitive?.content ?: "stack",
        movementFamily = o["movement_family"]?.jsonPrimitive?.contentOrNull,
        equipment = o["equipment"]?.jsonPrimitive?.contentOrNull,
    )

    private fun suggestion(s: WeightSuggestion?): JsonElement =
        if (s == null) JsonNull else buildJsonObject {
            put("weight_kg", s.weightKg)
            put("reason", s.reason)
            put("detail", s.detail)
            put("evidence", s.evidence)
        }

    private fun check(path: String, expected: JsonElement, actual: JsonElement?) {
        val act = actual ?: JsonNull
        when (expected) {
            is JsonNull -> if (act !is JsonNull) fail("$path: expected null, got $act")
            is JsonObject -> {
                val obj = act as? JsonObject ?: fail("$path: expected object, got $act")
                for ((k, v) in expected) {
                    if (k.endsWith("_contains")) {
                        val field = obj[k.removeSuffix("_contains")]?.jsonPrimitive?.contentOrNull
                        if (field == null || !field.contains(v.jsonPrimitive.content)) fail("$path.$k: '$field' lacks '${v.jsonPrimitive.content}'")
                    } else check("$path.$k", v, obj[k])
                }
            }
            is JsonArray -> {
                val arr = act as? JsonArray ?: fail("$path: expected array, got $act")
                if (arr.size != expected.size) fail("$path: expected ${expected.size} items, got ${arr.size}")
                expected.forEachIndexed { idx, e -> check("$path[$idx]", e, arr[idx]) }
            }
            is JsonPrimitive -> {
                val a = act as? JsonPrimitive ?: fail("$path: expected $expected, got $act")
                val e = expected.doubleOrNull
                if (e != null && !expected.isString) {
                    val ad = a.doubleOrNull ?: fail("$path: expected number $e, got $a")
                    if (abs(ad - e) > 1e-9) fail("$path: expected $e, got $ad")
                } else if (a.contentOrNull != expected.contentOrNull) fail("$path: expected '$expected', got '$a'")
            }
        }
    }
}
