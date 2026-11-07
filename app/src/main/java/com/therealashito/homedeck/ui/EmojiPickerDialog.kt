package com.therealashito.homedeck.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.GridView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Icon picker: a large grid of emoji with big cells so the glyphs are easy to
 * tell apart. "Clear icon" switches the tile to the site's own favicon.
 */
class EmojiPickerDialog(
    private val context: Context,
    private val onPick: (String) -> Unit
) {

    fun show() {
        val grid = GridView(context).apply {
            numColumns = 5
            horizontalSpacing = 10
            verticalSpacing = 6
            setPadding(28, 8, 28, 0)
            adapter = object :
                ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, EMOJIS) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.textSize = 31f
                    view.gravity = Gravity.CENTER
                    view.setPadding(0, 10, 0, 10)
                    return view
                }
            }
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Pick an icon")
            .setMessage("Tap an emoji for the tile. Clear icon uses the site's own favicon.")
            .setView(grid)
            .setNegativeButton("Clear icon") { _, _ -> onPick("") }
            .setNeutralButton("Cancel", null)
            .create()
        grid.setOnItemClickListener { _, _, position, _ ->
            onPick(EMOJIS[position])
            dialog.dismiss()
        }
        dialog.show()
    }

    companion object {
        val EMOJIS: List<String> = listOf(
            // computers & homelab
            "🖥️", "📟", "📱", "💻", "⌨️", "🖨️", "🖱️", "🧮",
            "🗄️", "📦", "🛰️", "📡", "🌐", "📶", "🔌", "🔋",
            "⚡", "💾", "📀", "💿", "📇", "🖲️",
            // tools & monitoring
            "🧰", "🛠️", "🔧", "🔨", "🪛", "🔩", "🔍", "🔬",
            "🧪", "🧬", "📊", "📈", "📉", "🗂️", "📁", "📂",
            "🗃️", "📋", "📌", "📍", "🏷️", "🗓️", "📅", "⏱️",
            "⏰", "⏳", "🧭", "🧩", "🔗", "📐", "📏",
            // security
            "🔑", "🔒", "🔓", "🛡️", "🚨", "🚦", "🔐", "🧯",
            // media & fun
            "🎛️", "🎚️", "🎙️", "🔊", "📢", "🎧", "🎵", "🎶",
            "🎬", "📺", "📹", "📷", "🎞️", "🎮", "🕹️", "🏆",
            "🥇", "🎯", "🎲", "🎨", "🖌️", "🎭", "🃏",
            // home & smart living
            "🏠", "🏡", "🛋️", "🚪", "🪟", "💡", "🌡️", "🌬️",
            "❄️", "🔥", "💧", "🚰", "🧹", "🧺", "🍳", "🍕",
            "🍔", "☕", "🍺", "🌱", "🌳", "🚗", "🏍️", "🚲",
            // misc
            "🤖", "👾", "🚀", "🛸", "🐳", "🐋", "🐟", "🐈",
            "🐕", "🦉", "🦊", "🐝", "🌙", "⭐", "🌈", "☀️",
            "⛅", "🌦️", "🌊", "🌋", "🌍", "🏙️", "🏢", "🌌"
        )
    }
}
