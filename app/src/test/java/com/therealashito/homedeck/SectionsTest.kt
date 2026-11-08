package com.therealashito.homedeck

import com.therealashito.homedeck.model.Tile
import com.therealashito.homedeck.ui.Row
import com.therealashito.homedeck.ui.Sections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionsTest {

    private fun tile(id: String, category: String) =
        Tile(id = id, name = id, url = "http://$id", category = category)

    private val hA = Row.Header("A", true, 2)
    private val a1 = Row.Item(tile("a1", "A"))
    private val a2 = Row.Item(tile("a2", "A"))
    private val hB = Row.Header("B", true, 2)
    private val b1 = Row.Item(tile("b1", "B"))
    private val b2 = Row.Item(tile("b2", "B"))
    private val free = Row.Item(tile("f", ""))

    private fun replay(rows: List<Row>, steps: List<Pair<Int, Int>>): List<Row> {
        val sim = rows.toMutableList()
        for ((p, q) in steps) {
            val row = sim.removeAt(p)
            sim.add(q, row)
        }
        return sim
    }

    @Test
    fun draggingDownSwapsWholeFolderPastNext() {
        val rows = listOf(hA, a1, a2, hB, b1, b2)
        val steps = Sections.dragSteps(rows, 0, 4) // grab A, hover over b1
        assertEquals(9, steps.size) // k=3 rows x m=3 rows of adjacent swaps
        assertEquals(listOf(hB, b1, b2, hA, a1, a2), replay(rows, steps))
    }

    @Test
    fun draggingUpSwapsWholeFolderPastPrevious() {
        val rows = listOf(hA, a1, a2, hB, b1, b2)
        val steps = Sections.dragSteps(rows, 3, 1) // grab B, hover over a1
        assertEquals(listOf(hB, b1, b2, hA, a1, a2), replay(rows, steps))
    }

    @Test
    fun hoveringInsideOwnBlockDoesNothing() {
        val rows = listOf(hA, a1, a2, hB, b1)
        assertTrue(Sections.dragSteps(rows, 0, 2).isEmpty())
    }

    @Test
    fun collapsedFolderMovesAsOneRow() {
        val rows = listOf(hA, hB, b1) // A collapsed (header only)
        val steps = Sections.dragSteps(rows, 0, 1)
        assertEquals(listOf(hB, b1, hA), replay(rows, steps))
    }

    @Test
    fun draggingPastUncategorizedTileTreatsItAsOneRow() {
        val rows = listOf(free, hA, a1)
        val steps = Sections.dragSteps(rows, 1, 0)
        assertEquals(listOf(hA, a1, free), replay(rows, steps))
    }

    @Test
    fun itemDragIsNotSectionsBusiness() {
        // Section math only handles header drags; tile drags return no steps.
        assertTrue(Sections.dragSteps(listOf(hA, a1, a2), 1, 2).isEmpty())
    }
}
