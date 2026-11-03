package de.baschek.gymapp.core.stats

import de.baschek.gymapp.core.model.PackItem

data class PackRowState(val id: String, val text: String, val checked: Boolean)

/**
 * The packing list of a gym exactly as synced (sorted by `order`, texts untouched). Checked state comes
 * from today's saved state; on a new day (or when nothing was saved) the `done_default` items are checked.
 */
fun packRows(items: List<PackItem>, savedRaw: String?, today: String): List<PackRowState> {
    val sorted = items.sortedBy { it.order }
    val checked = PackState.decodeOrNull(savedRaw, today) ?: sorted.filter { it.doneDefault == true }.map { it.id }.toSet()
    return sorted.map { PackRowState(it.id, it.text, it.id in checked) }
}
