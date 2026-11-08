package com.therealashito.homedeck.data

import android.content.Context
import com.therealashito.homedeck.model.Tile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists dashboard tiles as a JSON array in SharedPreferences. All parsing
 * helpers live in the companion so they can be unit tested on the JVM.
 */
class TileStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("homedeck.tiles", Context.MODE_PRIVATE)

    fun load(): MutableList<Tile> {
        val raw = prefs.getString(KEY, null) ?: ""
        val tiles = fromJson(raw)
        if (raw.isEmpty() && tiles.isEmpty()) {
            val defaults = defaultTiles()
            save(defaults)
            return defaults.toMutableList()
        }
        return tiles
    }

    fun save(tiles: List<Tile>) {
        prefs.edit().putString(KEY, toJson(tiles)).apply()
    }

    companion object {
        const val KEY = "tiles"

        fun toJson(tiles: List<Tile>): String {
            val arr = JSONArray()
            for (t in tiles) {
                val o = JSONObject()
                o.put("id", t.id)
                o.put("name", t.name)
                o.put("url", t.url)
                o.put("emoji", t.emoji)
                o.put("color", t.colorIndex)
                o.put("category", t.category)
                arr.put(o)
            }
            return arr.toString()
        }

        fun fromJson(raw: String): MutableList<Tile> {
            val out = mutableListOf<Tile>()
            if (raw.isBlank()) return out
            val arr = try {
                JSONArray(raw)
            } catch (e: Exception) {
                return out
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val url = o.optString("url")
                if (name.isBlank() || url.isBlank()) continue
                out.add(
                    Tile(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = name,
                        url = url,
                        emoji = o.optString("emoji"),
                        colorIndex = o.optInt("color", 0),
                        category = o.optString("category")
                    )
                )
            }
            return out
        }

        /** Starter deck; every tile is editable/removable in the app. */
        fun defaultTiles(): List<Tile> = listOf(
            Tile(name = "Uptime Kuma", url = "http://kuma.lan", emoji = "\uD83D\uDCE1", colorIndex = 3, category = "Monitoring"),
            Tile(name = "Speedtest Tracker", url = "http://speedtest-tracker.lan", emoji = "\u23F1\uFE0F", colorIndex = 5, category = "Monitoring"),
            Tile(name = "Grafana", url = "http://grafana.lan", emoji = "\uD83D\uDCCA", colorIndex = 4, category = "Monitoring"),
            Tile(name = "Terminal", url = "http://terminal.lan", emoji = "\u2328\uFE0F", colorIndex = 1, category = "Infrastructure"),
            Tile(name = "Portainer", url = "http://portainer.lan", emoji = "\uD83D\uDE80", colorIndex = 0, category = "Infrastructure"),
            Tile(name = "Home Assistant", url = "http://homeassistant.lan", emoji = "\uD83C\uDFE0", colorIndex = 2, category = "Home")
        )
    }
}
