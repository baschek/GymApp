package de.baschek.gymapp.core.recorder

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class NavEvent { TAP, SCREEN, AUTO, ACTION }

/** Pure event policy shared by both apps. Times are sampled at the hook, before any queued IO. */
class Recording(private val device: String, private val start: Long) {
    companion object {
        const val LIMIT_MS = 3 * 60 * 60 * 1000L
    }

    private var currentScreen = "unknown"
    private var lastShot = -2_000L
    private var seenScreen = false

    fun expired(now: Long) = now - start >= LIMIT_MS

    fun event(type: NavEvent, screen: String?, element: String, wall: Long, now: Long): Entry? {
        if (expired(now)) return null
        val name = screen?.let { it.lowercase().takeIf(RecorderIds.screens::contains) ?: "unknown" } ?: currentScreen
        val mono = now - start
        var capture = false
        val id = when (type) {
            NavEvent.SCREEN -> {
                if (seenScreen && currentScreen == name) return null
                currentScreen = name
                seenScreen = true
                capture = mono - lastShot >= 2_000
                if (capture) lastShot = mono
                ""
            }
            NavEvent.TAP -> element.takeIf(RecorderIds.elements::contains) ?: "control"
            NavEvent.ACTION -> element.takeIf(RecorderIds.actions::contains) ?: "action"
            NavEvent.AUTO -> element.takeIf(RecorderIds.causes::contains) ?: "auto:other"
        }
        return Entry(wall, mono, device, type, name, id, capture)
    }

    class Entry internal constructor(
        val wall: Long, val mono: Long, val device: String, val type: NavEvent,
        val screen: String, val element: String, val capture: Boolean,
    ) {
        fun line(hasShot: Boolean = false) = buildJsonObject {
            put("t", wall)
            put("mono", mono)
            put("dev", device)
            put("type", type.name.lowercase())
            put("screen", screen)
            put("element", element)
            if (capture && hasShot) put("shot", shotName)
        }.toString() + "\n"

        val shotName get() = "screen-$mono.webp"
    }
}
