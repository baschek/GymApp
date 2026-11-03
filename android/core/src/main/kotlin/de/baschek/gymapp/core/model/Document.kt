package de.baschek.gymapp.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Entity names of the sync contract. */
object Entity {
    const val GYM = "gym"
    const val EXERCISE = "exercise"
    const val PLAN = "plan"
    const val MACHINE_PROFILE = "machine_profile"
    const val SESSION = "session"
    const val BODY_WEIGHT = "body_weight"
    const val MILESTONE = "milestone"
    const val GOAL = "goal"

    val ALL = listOf(GYM, EXERCISE, PLAN, MACHINE_PROFILE, SESSION, BODY_WEIGHT, MILESTONE, GOAL)
}

object Origin {
    const val APP = "app"
    const val SERVER = "server"
    const val IMPORT = "import"
}

/**
 * Wire-level document. [data] stays a raw [JsonObject] so that unknown fields
 * survive a round trip; typed access goes through [DocumentCodec].
 */
@Serializable
data class Document(
    val id: String,
    val entity: String,
    val rev: Long,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
    val origin: String = Origin.SERVER,
    val data: JsonObject = JsonObject(emptyMap()),
) {
    val key: DocKey get() = DocKey(entity, id)
}

data class DocKey(val entity: String, val id: String)

@Serializable
data class SnapshotResponse(val rev: Long, val documents: List<Document> = emptyList())

@Serializable
data class ChangesResponse(
    val changes: List<Document> = emptyList(),
    @SerialName("next_since") val nextSince: Long,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class StatusResponse(
    val rev: Long,
    @SerialName("server_time") val serverTime: String? = null,
    @SerialName("device_last_seen") val deviceLastSeen: String? = null,
)

@Serializable
data class PairRequest(
    val code: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("public_key") val publicKey: String,
)

/** The token is a credential: never include it in toString/logs. */
@Serializable
data class PairResponse(val token: String, @SerialName("device_id") val deviceId: String) {
    override fun toString(): String = "PairResponse(deviceId=$deviceId, token=<redacted>)"
}

@Serializable
data class PushOp(
    @SerialName("op_id") val opId: String,
    val entity: String,
    val id: String,
    val op: String,
    @SerialName("base_rev") val baseRev: Long? = null,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    val data: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        const val UPSERT = "upsert"
        const val DELETE = "delete"
    }
}

@Serializable
data class PushRequest(val ops: List<PushOp>)

@Serializable
data class PushOpResult(
    @SerialName("op_id") val opId: String,
    val status: String,
    val document: Document? = null,
    val reason: String? = null,
) {
    companion object {
        const val APPLIED = "applied"
        const val CONFLICT = "conflict"
        const val REJECTED = "rejected"
    }
}

@Serializable
data class PushResponse(val results: List<PushOpResult> = emptyList(), val rev: Long = 0)

@Serializable
data class TelemetryCount(val day: String, val screen: String, val element: String, val count: Int)

@Serializable
data class TelemetryRequest(
    @SerialName("batch_id") val batchId: String,
    val counts: List<TelemetryCount>,
)
