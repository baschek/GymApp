package de.baschek.gymapp.core.wear.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Raw contract documents, keyed by their stable document IDs. Test catalogs stay local. */
@Serializable
data class WatchCatalog(
    val schemaVersion: Int = 1,
    val profile: WorkoutProfile,
    val gyms: Map<String, JsonObject> = emptyMap(),
    val plans: Map<String, JsonObject> = emptyMap(),
    val exercises: Map<String, JsonObject> = emptyMap(),
    val machineProfiles: Map<String, JsonObject> = emptyMap(),
    val sessions: Map<String, JsonObject> = emptyMap(),
    val dayWorkouts: Map<String, JsonObject> = emptyMap(),
    val accent: String? = null,
)
