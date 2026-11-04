package de.baschek.gymapp.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Converts between the raw `data` object of a document and typed domain
 * classes without losing fields the typed class does not know about.
 *
 * Writing back works by merging the freshly encoded typed value onto the
 * original raw object: known fields are taken from the typed value (and removed
 * when the typed value no longer has them), unknown fields are kept, also
 * inside nested objects and lists (list items are matched by `id`, else index).
 */
object DocumentCodec {
    fun <T> decode(serializer: KSerializer<T>, data: JsonObject): T =
        GymJson.decodeFromJsonElement(serializer, data)

    fun <T> encodeMerged(serializer: KSerializer<T>, value: T, raw: JsonObject?): JsonObject {
        val fresh = GymJson.encodeToJsonElement(serializer, value)
        val merged = if (raw == null) fresh else merge(serializer.descriptor, raw, fresh)
        return merged as JsonObject
    }

    internal fun merge(desc: SerialDescriptor, raw: JsonElement?, fresh: JsonElement): JsonElement {
        if (desc.kind == StructureKind.CLASS && fresh is JsonObject) {
            val rawObj = raw as? JsonObject ?: JsonObject(emptyMap())
            val out = LinkedHashMap<String, JsonElement>(rawObj)
            for (i in 0 until desc.elementsCount) {
                val name = desc.getElementName(i)
                val value = fresh[name]
                if (value == null) {
                    out.remove(name)
                } else {
                    out[name] = merge(desc.getElementDescriptor(i), rawObj[name], value)
                }
            }
            return JsonObject(out)
        }
        if (desc.kind == StructureKind.LIST && fresh is JsonArray && desc.elementsCount > 0) {
            val rawArr = (raw as? JsonArray) ?: JsonArray(emptyList())
            val elementDesc = desc.getElementDescriptor(0)
            val rawById = rawArr.mapNotNull { e -> idOf(e)?.let { it to e } }.toMap()
            return JsonArray(
                fresh.mapIndexed { index, item ->
                    val id = idOf(item)
                    val match = if (id != null) rawById[id] else rawArr.getOrNull(index)
                    if (match == null) item else merge(elementDesc, match, item)
                },
            )
        }
        return fresh
    }

    private fun idOf(e: JsonElement): String? =
        ((e as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    inline fun <reified T> decode(data: JsonObject): T =
        decode(kotlinx.serialization.serializer<T>(), data)

    inline fun <reified T> encodeMerged(value: T, raw: JsonObject?): JsonObject =
        encodeMerged(kotlinx.serialization.serializer<T>(), value, raw)
}

/** Columns the local store indexes in addition to the raw data (derived from raw JSON). */
data class IndexColumns(
    val planId: String? = null,
    val gymId: String? = null,
    val exerciseId: String? = null,
    val status: String? = null,
    val sortAt: String? = null,
) {
    companion object {
        fun extract(entity: String, data: JsonObject): IndexColumns {
            fun s(key: String): String? =
                (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return when (entity) {
                Entity.SESSION -> IndexColumns(
                    planId = s("plan_id"),
                    gymId = s("gym_id"),
                    status = s("status"),
                    sortAt = s("completed_at") ?: s("started_at"),
                )
                Entity.PLAN -> IndexColumns(
                    status = if ((data["archived"] as? JsonPrimitive)?.contentOrNull == "true") "archived" else "active",
                    sortAt = s("created_at"),
                )
                Entity.MACHINE_PROFILE -> IndexColumns(gymId = s("gym_id"), exerciseId = s("exercise_id"))
                Entity.BODY_WEIGHT -> IndexColumns(sortAt = s("date"))
                Entity.MILESTONE -> IndexColumns(
                    planId = s("plan_id"),
                    exerciseId = s("exercise_id"),
                    sortAt = s("occurred_at"),
                )
                Entity.WORKOUT_ADJUSTMENT -> IndexColumns(status = s("status"), sortAt = s("created_at"))
                else -> IndexColumns()
            }
        }
    }
}
