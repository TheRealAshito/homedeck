package com.therealashito.homedeck

import com.therealashito.homedeck.model.Tile
import com.therealashito.homedeck.ui.Row
import com.therealashito.homedeck.ui.Rows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowsTest {

    private val free = Tile(id = "a", name = "A", url = "http://a", category = "")
    private val work1 = Tile(id = "b", name = "B", url = "http://b", category = "Work")
    private val work2 = Tile(id = "c", name = "C", url = "http://c", category = "Work")
    private val home = Tile(id = "d", name = "D", url = "http://d", category = "Home")

    @Test
    fun uncategorizedTilesComeFirstWithoutHeader() {
        val rows = Rows.build(listOf(work1, free, home), emptySet())
        assertTrue(rows[0] is Row.Item)
        assertEquals("a", (rows[0] as Row.Item).tile.id)
    }

    @Test
    fun categoriesGroupInFirstAppearanceOrder() {
        val rows = Rows.build(listOf(free, work1, work2, home), emptySet())
        assertEquals(6, rows.size)
        assertEquals("Work", (rows[1] as Row.Header).category)
        assertEquals("b", (rows[2] as Row.Item).tile.id)
        assertEquals("c", (rows[3] as Row.Item).tile.id)
        assertEquals("Home", (rows[4] as Row.Header).category)
    }

    @Test
    fun collapsedHidesItemsButKeepsHeader() {
        val rows = Rows.build(listOf(free, work1, work2, home), setOf("Work"))
        assertEquals(4, rows.size)
        val header = rows[1] as Row.Header
        assertEquals("Work", header.category)
        assertEquals(false, header.expanded)
        assertEquals(2, header.count)
    }

    @Test
    fun flatListWithoutCategoriesHasNoHeaders() {
        val rows = Rows.build(listOf(free, free.copy(id = "z")), emptySet())
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is Row.Item })
    }

    @Test
    fun withinCategoryOrderFollowsFlatList() {
        val rows = Rows.build(listOf(work2, work1), emptySet())
        assertEquals("c", (rows[1] as Row.Item).tile.id)
        assertEquals("b", (rows[2] as Row.Item).tile.id)
    }
}
