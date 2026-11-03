package de.baschek.gymapp.ui

import de.baschek.gymapp.telemetry.Telemetry

/**
 * Wraps a click handler so the interaction is counted: `onClick = tracked("heute", "start") { ... }`.
 * Screen and element ids are stable strings; no content is ever recorded.
 */
fun tracked(screen: String, element: String, block: () -> Unit = {}): () -> Unit = {
    Telemetry.track(screen, element)
    block()
}

fun <T> tracked1(screen: String, element: String, block: (T) -> Unit): (T) -> Unit = { value ->
    Telemetry.track(screen, element)
    block(value)
}
