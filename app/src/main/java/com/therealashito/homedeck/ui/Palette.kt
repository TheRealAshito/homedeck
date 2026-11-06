package com.therealashito.homedeck.ui

import android.content.Context
import android.graphics.Color

/** Accent colors available for tiles (index is persisted on the tile). */
object Palette {

    val accents: List<Int> = listOf(
        Color.parseColor("#5B6CFF"),
        Color.parseColor("#14B8A6"),
        Color.parseColor("#22C55E"),
        Color.parseColor("#F59E0B"),
        Color.parseColor("#F43F5E"),
        Color.parseColor("#A855F7"),
        Color.parseColor("#06B6D4"),
        Color.parseColor("#FB7185")
    )

    fun color(index: Int): Int {
        val size = accents.size
        val wrapped = ((index % size) + size) % size
        return accents[wrapped]
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
