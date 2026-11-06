package com.therealashito.homedeck.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.GridView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Small grid dialog for picking a tile emoji. */
class EmojiPickerDialog(
    private val context: Context,
    private val onPick: (String) -> Unit
) {

    fun show() {
        val grid = GridView(context).apply {
            numColumns = 6
            horizontalSpacing = 14
            verticalSpacing = 10
            setPadding(36, 12, 36, 0)
            adapter = object :
                ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, EMOJIS) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.textSize = 26f
                    view.gravity = Gravity.CENTER
                    return view
                }
            }
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Pick an icon")
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
            "\uD83D\uDCBB", "\uD83D\uDCE1", "\uD83D\uDCCA", "\uD83D\uDE80", "\uD83D\uDC33", "\uD83D\uDD12",
            "\uD83D\uDCC8", "\uD83C\uDF10", "\uD83D\uDDC4\uFE0F", "\uD83C\uDFAC", "\uD83C\uDFB5", "\uD83D\uDCF7",
            "\uD83D\uDCA1", "\uD83C\uDFE0", "\uD83E\uDDED", "\u2699\uFE0F", "\uD83D\uDEE1\uFE0F", "\uD83D\uDCE6",
            "\uD83D\uDD79\uFE0F", "\uD83D\uDCFA", "\uD83E\uDDEA", "\uD83D\uDDC3\uFE0F", "\u2709\uFE0F", "\uD83E\uDD16",
            "\uD83E\uDDF0", "\uD83D\uDD0D", "\u23F1\uFE0F", "\uD83D\uDCF6", "\uD83E\uDDE9", "\uD83D\uDEE0\uFE0F",
            "\uD83D\uDCBE", "\uD83D\uDD0C", "\uD83D\uDCFB", "\uD83D\uDDFA\uFE0F", "\uD83D\uDD0B", "\uD83C\uDF21\uFE0F",
            "\uD83D\uDCF9", "\uD83C\uDFAE", "\uD83D\uDCDA", "\u2601\uFE0F"
        )
    }
}
