package de.baschek.gymapp.core.wear.events

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import de.baschek.gymapp.core.model.GymJson

/** A device stores its state and outgoing event in one atomic transaction before sending. */
@Serializable enum class WorkoutProfile { PERSONAL, TEST }
@Serializable data class WorkoutTimer(val phase: String?, val startedAtMillis: Long, val endsAtMillis: Long? = null, val label: String = "", val pendingRestSeconds: Int? = null, val pendingSetupSeconds: Int? = null, val sessionId: String? = null, val exerciseId: String? = null, val setId: String? = null)
@Serializable data class OperationStamp(val occurredAtMillis: Long, val deviceId: String, val eventId: String)
@Serializable data class WorkoutEvent(
    val eventId: String, val deviceId: String, val occurredAtMillis: Long,
    val profile: WorkoutProfile, val sessionId: String, val before: JsonObject, val after: JsonObject,
    val timer: WorkoutTimer? = null, val focusExerciseId: String? = null,
    val resolutions: List<ConflictResolution> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val predecessorEventId: String? = null,
)
@Serializable data class ConflictResolution(
    val conflictId: String, val path: List<String>, val candidateA: JsonElement?,
    val candidateB: JsonElement?, val chosen: JsonElement?,
)
private fun conflictKey(path: List<String>, a: OperationStamp?, b: OperationStamp): String {
    val ids = listOf(a?.eventId ?: "initial", b.eventId).sorted()
    val material = JsonArray((ids + path).map { JsonPrimitive(it) }).toString()
    return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
@Serializable data class PathVersion(val path: List<String>, val stamp: OperationStamp)
@Serializable data class EventReceipt(val eventId: String, val fingerprint: String)
/** Null values mean a missing property or a deleted exercise/set, not JSON null. */
@Serializable data class WorkoutConflict(
    val path: List<String>, val local: JsonElement?, val remote: JsonElement?,
    val localStamp: OperationStamp?, val remoteStamp: OperationStamp,
    val conflictId: String = conflictKey(path, localStamp, remoteStamp),
)
@Serializable data class ReplicaState(
    val profile: WorkoutProfile, val sessionId: String, val document: JsonObject,
    val timer: WorkoutTimer? = null, val focusExerciseId: String? = null,
    val lastOperation: OperationStamp? = null, val receipts: List<EventReceipt> = emptyList(),
    val conflicts: List<WorkoutConflict> = emptyList(), val versions: List<PathVersion> = emptyList(),
)
@Serializable enum class ConflictChoice { MINE, NEWEST }
sealed class EventResult {
    data class Applied(val state: ReplicaState) : EventResult()
    data class Replayed(val state: ReplicaState) : EventResult()
    data class Rejected(val reason: String) : EventResult()
}

/**
 * Transport-neutral three-way reducer. Receipt retention and outgoing delivery are the caller's
 * responsibility: never discard an unacknowledged receipt. TEST events must not cross devices.
 * Clock timestamps choose NEWEST deterministically, but do not establish causality.
 */
object WorkoutEvents {
    const val MAX_DOCUMENT_BYTES = 512 * 1024
    const val MAX_RECEIPTS = 4096
    const val MAX_CONFLICTS = 1024
    private const val MAX_ID = 128
    private const val MAX_COLLECTION = 1000

    fun validate(state: ReplicaState): String? {
        if (!uuid(state.sessionId) || !documentValid(state.document)) return "invalid_state"
        if (!timerValid(state.timer) || !optionalId(state.focusExerciseId) || !stampValid(state.lastOperation)) return "invalid_state"
        if (state.receipts.size > MAX_RECEIPTS || state.receipts.any { !uuid(it.eventId) || !it.fingerprint.matches(Regex("[0-9a-f]{64}")) } || state.receipts.map { it.eventId }.toSet().size != state.receipts.size) return "invalid_receipts"
        if (state.conflicts.size > MAX_CONFLICTS || state.conflicts.any { it.path.isEmpty() || it.path.size > 8 || it.path.any { p -> !id(p) } || !stampValid(it.localStamp) || !stampValid(it.remoteStamp) || !hash(it.conflictId) }) return "invalid_conflicts"
        if (state.versions.size > MAX_CONFLICTS * 4 || state.versions.any { it.path.isEmpty() || it.path.size > 8 || it.path.any { p -> !id(p) } || !stampValid(it.stamp) }) return "invalid_versions"
        return null
    }

    fun apply(state: ReplicaState, event: WorkoutEvent): EventResult {
        validate(state)?.let { return EventResult.Rejected(it) }
        if (!uuid(event.eventId) || !uuid(event.sessionId) || !id(event.deviceId) || event.occurredAtMillis < 0 ||
            !documentValid(event.before) || !documentValid(event.after) || !timerValid(event.timer) || !optionalId(event.focusExerciseId) ||
            (event.predecessorEventId != null && (!uuid(event.predecessorEventId) || event.predecessorEventId == event.eventId))) return EventResult.Rejected("invalid_event")
        if (event.resolutions.size > MAX_CONFLICTS || (event.resolutions.isNotEmpty() && event.before != event.after) ||
            event.resolutions.any { !hash(it.conflictId) || it.path.isEmpty() || it.path.size > 8 || it.path.any { p -> !id(p) } || (it.chosen != it.candidateA && it.chosen != it.candidateB) } ||
            event.resolutions.map { it.conflictId }.toSet().size != event.resolutions.size ||
            GymJson.encodeToString(event).toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES * 4) return EventResult.Rejected("invalid_resolution")
        if (event.sessionId != state.sessionId) return EventResult.Rejected("different_session")
        if (event.profile != state.profile) return EventResult.Rejected("different_profile")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(GymJson.encodeToString(event).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        state.receipts.firstOrNull { it.eventId == event.eventId }?.let {
            return if (it.fingerprint == fingerprint) EventResult.Replayed(state) else EventResult.Rejected("event_id_reused")
        }
        if (event.predecessorEventId != null && state.receipts.none { it.eventId == event.predecessorEventId }) return EventResult.Rejected("missing_predecessor")
        if (state.receipts.size >= MAX_RECEIPTS) return EventResult.Rejected("receipt_capacity")
        val stamp = OperationStamp(event.occurredAtMillis, event.deviceId, event.eventId)
        if (event.resolutions.isNotEmpty()) return applyResolution(state, event, stamp, fingerprint)
        val added = mutableListOf<WorkoutConflict>()
        val changed = mutableListOf<List<String>>()
        val merged = merge(event.before, state.document, event.after, emptyList(), state.versions, stamp, added, changed) as? JsonObject
            ?: return EventResult.Rejected("invalid_merge")
        val conflicts = state.conflicts.map { c ->
            val current = getAt(merged, c.path)
            if (current != c.local) c.copy(local = current, localStamp = stamp) else c
        }.toMutableList()
        conflicts.addAll(added)
        if (conflicts.size > MAX_CONFLICTS) return EventResult.Rejected("conflict_capacity")
        val leads = compare(stamp, state.lastOperation) > 0
        val versions = state.versions.filterNot { old -> changed.any { path -> old.path == path || old.path.size > path.size && old.path.take(path.size) == path } } + changed.map { PathVersion(it, stamp) }
        val next = state.copy(document = merged, timer = if (leads) event.timer else state.timer,
            focusExerciseId = if (leads) event.focusExerciseId else state.focusExerciseId,
            lastOperation = if (leads) stamp else state.lastOperation,
            receipts = state.receipts + EventReceipt(event.eventId, fingerprint), conflicts = conflicts, versions = versions)
        validate(next)?.let { return EventResult.Rejected(it) }
        return EventResult.Applied(next)
    }

    /** Build an event for atomic persistence alongside the resulting state and outbox. */
    fun createResolution(state: ReplicaState, choice: ConflictChoice, eventId: String, deviceId: String, occurredAtMillis: Long): WorkoutEvent {
        require(validate(state) == null && state.conflicts.isNotEmpty())
        require(uuid(eventId) && id(deviceId) && occurredAtMillis >= 0)
        val resolutions = state.conflicts.map { c ->
            val chosen = if (choice == ConflictChoice.NEWEST && compare(c.remoteStamp, c.localStamp) > 0) c.remote else c.local
            ConflictResolution(c.conflictId, c.path, c.local, c.remote, chosen)
        }.distinctBy { it.conflictId }
        return WorkoutEvent(eventId, deviceId, occurredAtMillis, state.profile, state.sessionId,
            state.document, state.document, state.timer, state.focusExerciseId, resolutions)
    }

    private fun applyResolution(state: ReplicaState, event: WorkoutEvent, stamp: OperationStamp, fingerprint: String): EventResult {
        // Delivery may overtake either source event. Do not acknowledge until the conflict exists.
        if (event.resolutions.any { r -> state.conflicts.none { it.conflictId == r.conflictId && it.path == r.path } }) return EventResult.Rejected("resolution_missing_conflict")
        var document: JsonElement = state.document
        var versions = state.versions
        val conflicts = state.conflicts.toMutableList()
        for (resolution in event.resolutions) {
            val old = conflicts.first { it.conflictId == resolution.conflictId && it.path == resolution.path }
            conflicts.removeAll { it.conflictId == resolution.conflictId && it.path == resolution.path }
            val current = getAt(document, resolution.path)
            if (current == resolution.candidateA || current == resolution.candidateB) {
                document = putAt(document, resolution.path, resolution.chosen)
                // A missing parent cannot safely receive a value; retain the dispute.
                if (getAt(document, resolution.path) != resolution.chosen) return EventResult.Rejected("resolution_missing_parent")
                versions = versions.filterNot { it.path == resolution.path || it.path.size > resolution.path.size && it.path.take(resolution.path.size) == resolution.path } + PathVersion(resolution.path, stamp)
            } else {
                val localStamp = versions.filter { resolution.path.take(it.path.size) == it.path }.maxByOrNull { it.path.size }?.stamp ?: old.localStamp
                conflicts.add(WorkoutConflict(resolution.path, current, resolution.chosen, localStamp, stamp))
            }
        }
        if (conflicts.size > MAX_CONFLICTS) return EventResult.Rejected("conflict_capacity")
        val leads = compare(stamp, state.lastOperation) > 0
        val next = state.copy(document = document as? JsonObject ?: return EventResult.Rejected("invalid_resolution"),
            versions = versions, conflicts = conflicts, receipts = state.receipts + EventReceipt(event.eventId, fingerprint),
            lastOperation = if (leads) stamp else state.lastOperation)
        validate(next)?.let { return EventResult.Rejected(it) }
        return EventResult.Applied(next)
    }

    private fun merge(base: JsonElement?, local: JsonElement?, remote: JsonElement?, path: List<String>, versions: List<PathVersion>, remoteStamp: OperationStamp, conflicts: MutableList<WorkoutConflict>, changed: MutableList<List<String>>): JsonElement? {
        val localStamp = versions.filter { path.take(it.path.size) == it.path }.maxByOrNull { it.path.size }?.stamp
        if (remote == base) return local
        if (local == remote) return local
        // A set is an atomic record: different edits to it are disputed, even on distinct fields.
        if (path.size == 4 && path[0] == "exercises" && path[2] == "sets") {
            if (local == base) { changed.add(path); return remote }
            if (sameCompletion(local, remote)) return local
            conflicts.add(WorkoutConflict(path, local, remote, localStamp, remoteStamp)); return local
        }
        if (base is JsonObject && local is JsonObject && remote is JsonObject) {
            val keys = (base.keys + local.keys + remote.keys).toSortedSet()
            return JsonObject(keys.mapNotNull { key -> merge(base[key], local[key], remote[key], path + key, versions, remoteStamp, conflicts, changed)?.let { key to it } }.toMap())
        }
        if (base is JsonArray && local is JsonArray && remote is JsonArray && (path == listOf("exercises") || (path.size == 3 && path[0] == "exercises" && path[2] == "sets"))) {
            val b = keyed(base); val l = keyed(local); val r = keyed(remote)
            if (b != null && l != null && r != null) {
                // Preserve local order, then append unseen remote additions in deterministic ID order.
                val ids = (l.keys + r.keys.sorted() + b.keys.sorted()).distinct()
                return JsonArray(ids.mapNotNull { key -> merge(b[key], l[key], r[key], path + key, versions, remoteStamp, conflicts, changed) })
            }
        }
        if (local == base) { if (path.isNotEmpty()) changed.add(path); return remote }
        conflicts.add(WorkoutConflict(path, local, remote, localStamp, remoteStamp)); return local
    }
    private fun sameCompletion(a: JsonElement?, b: JsonElement?): Boolean {
        if (a !is JsonObject || b !is JsonObject || a["status"] != JsonPrimitive("completed") || b["status"] != JsonPrimitive("completed")) return false
        return JsonObject(a - "completed_at") == JsonObject(b - "completed_at")
    }
    private fun keyed(a: JsonArray): LinkedHashMap<String, JsonElement>? {
        val result = linkedMapOf<String, JsonElement>()
        for (v in a) { val key = (v as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: return null
            if (!id(key) || result.put(key, v) != null) return null }
        return result
    }
    private fun getAt(root: JsonElement?, path: List<String>): JsonElement? {
        var current = root
        for (part in path) current = when (val v = current) { is JsonObject -> v[part]; is JsonArray -> keyed(v)?.get(part); else -> null }
        return current
    }
    private fun putAt(root: JsonElement, path: List<String>, value: JsonElement?): JsonElement {
        if (path.isEmpty()) return value ?: JsonNull
        val key = path.first(); val tail = path.drop(1)
        return when (root) {
            is JsonObject -> { val map = root.toMutableMap(); if (tail.isEmpty()) { if (value == null) map.remove(key) else map[key] = value } else map[key]?.let { map[key] = putAt(it, tail, value) }; JsonObject(map) }
            is JsonArray -> { val values = root.toMutableList(); val i = values.indexOfFirst { (it as? JsonObject)?.get("id") == JsonPrimitive(key) }
                if (tail.isEmpty()) { if (i >= 0 && value == null) values.removeAt(i) else if (i >= 0 && value != null) values[i] = value else if (value != null) values.add(value) }
                else if (i >= 0) values[i] = putAt(values[i], tail, value)
                JsonArray(values) }
            else -> root
        }
    }
    private fun documentValid(document: JsonObject): Boolean {
        if (document.toString().toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) return false
        val exercises = document["exercises"] as? JsonArray ?: return false
        if (exercises.size > MAX_COLLECTION || keyed(exercises) == null) return false
        return exercises.all { ex -> val sets = (ex as JsonObject)["sets"] as? JsonArray; sets != null && sets.size <= MAX_COLLECTION && keyed(sets) != null }
    }
    private fun compare(a: OperationStamp, b: OperationStamp?): Int = if (b == null) 1 else compareValuesBy(a, b, { it.occurredAtMillis }, { it.deviceId }, { it.eventId })
    private fun stampValid(s: OperationStamp?) = s == null || (s.occurredAtMillis >= 0 && id(s.deviceId) && uuid(s.eventId))
    private fun timerValid(t: WorkoutTimer?) = t == null || ((t.phase == null || id(t.phase)) && t.label.length <= 256 && t.startedAtMillis >= 0 && (t.endsAtMillis == null || t.endsAtMillis >= 0) && (t.pendingRestSeconds == null || t.pendingRestSeconds >= 0) && (t.pendingSetupSeconds == null || t.pendingSetupSeconds >= 0) && (t.sessionId == null || uuid(t.sessionId)) && optionalId(t.exerciseId) && optionalId(t.setId))
    private fun hash(s: String) = s.matches(Regex("[0-9a-f]{64}"))
    private fun optionalId(s: String?) = s == null || id(s)
    private fun id(s: String) = s.isNotBlank() && s.length <= MAX_ID && s.none { it.isISOControl() }
    private fun uuid(s: String) = runCatching { UUID.fromString(s).toString() == s.lowercase() }.getOrDefault(false)
}
