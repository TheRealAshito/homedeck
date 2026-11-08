package com.therealashito.homedeck.ui

import java.util.Collections

/**
 * Pure drag math for folder sections (unit tested): computes the
 * adjacent-swap steps that move a whole folder block (header + its visible
 * tiles) past the folder under the finger. The adapter just replays the
 * steps with notifyItemMoved, so folders always move as one unit.
 */
object Sections {

    /** Rows in the block starting at [start]: a header with its contiguous
     *  item rows, or a single uncategorized item. */
    fun blockSize(rows: List<Row>, start: Int): Int {
        val first = rows.getOrNull(start) ?: return 0
        return when (first) {
            is Row.Header -> {
                var size = 1
                var i = start + 1
                while (i < rows.size) {
                    val row = rows[i]
                    if (row is Row.Item && row.tile.category == first.category) {
                        size++
                        i++
                    } else {
                        break
                    }
                }
                size
            }
            is Row.Item -> 1
        }
    }

    /**
     * Adjacent-swap steps (pairs of current positions) to drag the folder
     * block at [from] past the folder block containing [to]. Hovering inside
     * the dragged folder's own block yields no steps.
     */
    fun dragSteps(rows: List<Row>, from: Int, to: Int): List<Pair<Int, Int>> {
        val steps = mutableListOf<Pair<Int, Int>>()
        val header = rows.getOrNull(from) as? Row.Header ?: return steps
        val target = rows.getOrNull(to) ?: return steps
        val targetCategory = when (target) {
            is Row.Header -> target.category
            is Row.Item -> target.tile.category
        }
        if (targetCategory == header.category) return steps

        val k = blockSize(rows, from)
        val targetStart = rows.indexOfFirst {
            when (it) {
                is Row.Header -> it.category == targetCategory
                is Row.Item -> it.tile.category == targetCategory
            }
        }
        if (targetStart < 0) return steps
        val m = blockSize(rows, targetStart)

        // Simulate positions; each entry is the row's original index.
        val sim = rows.indices.toMutableList()
        val blockIds = (from until from + k).toList()

        if (targetStart > from) {
            for (id in blockIds.reversed()) {
                repeat(m) {
                    val pos = sim.indexOf(id)
                    if (pos + 1 >= sim.size) return@repeat
                    steps += pos to pos + 1
                    Collections.swap(sim, pos, pos + 1)
                }
            }
        } else {
            for (id in blockIds) {
                repeat(m) {
                    val pos = sim.indexOf(id)
                    if (pos - 1 < 0) return@repeat
                    steps += pos to pos - 1
                    Collections.swap(sim, pos, pos - 1)
                }
            }
        }
        return steps
    }
}
