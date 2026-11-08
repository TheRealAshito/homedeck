package com.therealashito.homedeck.ui

import com.therealashito.homedeck.model.Tile

/** One dashboard row: either a category folder header or a service tile. */
sealed class Row {
    abstract val key: String

    data class Header(
        val category: String,
        val expanded: Boolean,
        val count: Int
    ) : Row() {
        override val key: String get() = "hdr:$category"
    }

    data class Item(val tile: Tile) : Row() {
        override val key: String get() = "tile:${tile.id}"
    }
}

/**
 * Pure dashboard row builder (unit tested): uncategorized tiles come first
 * as plain tiles, then one section per category in first-appearance order,
 * each with a folder header and, when expanded, its tiles in flat-list order.
 * No categories => no headers at all (the classic flat grid).
 */
object Rows {

    fun build(tiles: List<Tile>, collapsed: Set<String>): List<Row> {
        val rows = mutableListOf<Row>()
        rows += tiles.filter { it.category.isBlank() }.map { Row.Item(it) }
        val categories = tiles.map { it.category }.filter { it.isNotBlank() }.distinct()
        for (category in categories) {
            val items = tiles.filter { it.category == category }
            val expanded = category !in collapsed
            rows += Row.Header(category, expanded, items.size)
            if (expanded) {
                rows += items.map { Row.Item(it) }
            }
        }
        return rows
    }
}
