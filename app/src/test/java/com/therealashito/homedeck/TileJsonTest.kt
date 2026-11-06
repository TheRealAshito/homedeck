package com.therealashito.homedeck

import com.therealashito.homedeck.data.TileStore
import com.therealashito.homedeck.model.Tile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileJsonTest {

    @Test
    fun roundTripPreservesTiles() {
        val tiles = listOf(
            Tile(id = "a", name = "Uptime Kuma", url = "http://kuma.lan", emoji = "\uD83D\uDCE1", colorIndex = 3),
            Tile(id = "b", name = "Terminal", url = "http://terminal.lan", emoji = "", colorIndex = 0)
        )
        val parsed = TileStore.fromJson(TileStore.toJson(tiles))
        assertEquals(tiles, parsed)
    }

    @Test
    fun malformedJsonYieldsEmptyList() {
        assertTrue(TileStore.fromJson("not json").isEmpty())
        assertTrue(TileStore.fromJson("").isEmpty())
        assertTrue(TileStore.fromJson("   ").isEmpty())
    }

    @Test
    fun entriesWithoutNameOrUrlAreSkipped() {
        val json = JSONArray()
            .put(JSONObject().put("id", "1").put("name", "Good").put("url", "http://ok.lan"))
            .put(JSONObject().put("id", "2").put("name", "No URL"))
            .put(JSONObject().put("id", "3").put("url", "http://noname.lan"))
            .toString()
        val parsed = TileStore.fromJson(json)
        assertEquals(1, parsed.size)
        assertEquals("Good", parsed[0].name)
    }

    @Test
    fun missingIdGetsGenerated() {
        val json = JSONArray().put(JSONObject().put("name", "X").put("url", "http://x.lan")).toString()
        val parsed = TileStore.fromJson(json)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].id.isNotBlank())
    }

    @Test
    fun defaultTilesCoverNamedServices() {
        val defaults = TileStore.defaultTiles()
        assertTrue(defaults.any { it.name == "Uptime Kuma" })
        assertTrue(defaults.any { it.url == "http://kuma.lan" })
        assertTrue(defaults.any { it.name == "Speedtest Tracker" })
        assertTrue(defaults.all { it.name.isNotBlank() && it.url.startsWith("http") })
    }
}
