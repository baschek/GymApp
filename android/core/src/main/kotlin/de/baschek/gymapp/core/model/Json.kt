package de.baschek.gymapp.core.model

import kotlinx.serialization.json.Json

/** Shared JSON configuration for all contract documents. */
val GymJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}
