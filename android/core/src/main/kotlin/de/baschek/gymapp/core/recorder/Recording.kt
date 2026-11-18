package de.baschek.gymapp.core.recorder

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class NavEvent { TAP, SCREEN, AUTO, ACTION }

/** Pure event policy shared by both apps. Times are sampled at the hook, before any queued IO. */
class Recording(
    private val device: String,
    private val start: Long,
    private val shotIntervalMs: Long = 2_000,
    private val maxShots: Int = 300,
) {
    companion object {
        /** A session never grows beyond this; the recorder then rolls over to a new one. */
        const val LIMIT_MS = 3 * 60 * 60 * 1000L
    }

    private var currentScreen = "unknown"
    private var lastShot = -shotIntervalMs
    private var seenScreen = false
    private var shots = 0
    private var events = 0

    /** The last screen seen, or null while none was recorded; used to seed the next session after a rollover. */
    val screen: String? get() = currentScreen.takeIf { seenScreen }
    val eventCount get() = events

    fun expired(now: Long) = now - start >= LIMIT_MS

    fun remainingMs(now: Long) = (start + LIMIT_MS - now).coerceAtLeast(1)

    /** [allowShot] is false while images are unwanted (watch in ambient mode); it consumes neither interval nor budget. */
    fun event(type: NavEvent, screen: String?, element: String, wall: Long, now: Long, allowShot: Boolean = true): Entry? {
        if (expired(now)) return null
        val name = screen?.let { it.lowercase().takeIf(RecorderIds.screens::contains) ?: "unknown" } ?: currentScreen
        val mono = now - start
        var capture = false
        val id = when (type) {
            NavEvent.SCREEN -> {
                if (seenScreen && currentScreen == name) return null
                currentScreen = name
                seenScreen = true
                capture = allowShot && shots < maxShots && mono - lastShot >= shotIntervalMs
                if (capture) { lastShot = mono; shots++ }
                ""
            }
            NavEvent.TAP -> element.takeIf(RecorderIds.elements::contains) ?: "control"
            NavEvent.ACTION -> element.takeIf(RecorderIds.actions::contains) ?: "action"
            NavEvent.AUTO -> element.takeIf(RecorderIds.causes::contains) ?: "auto:other"
        }
        events++
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
