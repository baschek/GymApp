package de.baschek.gymapp.core.stats

/**
 * Checked packing-list items of a gym. The state is only valid on the day it was saved: on the
 * next training day all boxes are unchecked again. Stored as "yyyy-MM-dd|id\nid".
 */
object PackState {
    fun encode(day: String, checked: Set<String>): String = day + "|" + checked.sorted().joinToString("\n")

    fun decode(raw: String?, today: String): Set<String> {
        if (raw == null) return emptySet()
        val day = raw.substringBefore('|')
        if (day != today) return emptySet()
        return raw.substringAfter('|', "").lines().filter { it.isNotBlank() }.toSet()
    }

    fun toggle(current: Set<String>, id: String): Set<String> = if (id in current) current - id else current + id
}
