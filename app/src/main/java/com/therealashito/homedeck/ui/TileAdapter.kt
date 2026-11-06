package com.therealashito.homedeck.ui

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.therealashito.homedeck.R
import com.therealashito.homedeck.model.Tile
import com.therealashito.homedeck.model.Urls
import com.therealashito.homedeck.net.FaviconLoader
import com.therealashito.homedeck.net.StatusChecker

/**
 * Dashboard tile grid. Tiles are drag-reorderable via ItemTouchHelper and
 * carry a per-service status dot fed by [StatusChecker].
 */
class TileAdapter(
    private val onClick: (Tile) -> Unit,
    private val onMenu: (Tile, View) -> Unit
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    val tiles = mutableListOf<Tile>()
    val statuses = mutableMapOf<String, StatusChecker.Status>()
    var statusChecksEnabled = true

    fun submit(newTiles: List<Tile>) {
        tiles.clear()
        tiles.addAll(newTiles)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        if (from < 0 || to < 0 || from >= tiles.size || to >= tiles.size) return
        val tile = tiles.removeAt(from)
        tiles.add(to, tile)
        notifyItemMoved(from, to)
    }

    fun setStatus(id: String, status: StatusChecker.Status) {
        statuses[id] = status
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    fun clearStatuses() {
        statuses.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = tiles.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(tiles[position])
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val emojiChip: TextView = itemView.findViewById(R.id.emojiChip)
        private val favicon: ImageView = itemView.findViewById(R.id.favicon)
        private val title: TextView = itemView.findViewById(R.id.tileTitle)
        private val host: TextView = itemView.findViewById(R.id.tileUrl)
        private val statusDot: View = itemView.findViewById(R.id.statusDot)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val moreBtn: ImageButton = itemView.findViewById(R.id.moreBtn)

        fun bind(tile: Tile) {
            val accent = Palette.color(tile.colorIndex)
            val chipColor = Palette.withAlpha(accent, 0x2E)

            title.text = tile.name
            host.text = Urls.hostOf(tile.url)

            val chipShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Palette.dp(itemView.context, 13f).toFloat()
                setColor(chipColor)
            }
            emojiChip.background = chipShape
            favicon.background = chipShape.constantState?.newDrawable()?.mutate()
            emojiChip.text = tile.emoji

            if (tile.emoji.isBlank()) {
                emojiChip.visibility = View.INVISIBLE
                favicon.visibility = View.GONE
                FaviconLoader.load(itemView.context, tile.url) { bitmap: Bitmap? ->
                    val position = bindingAdapterPosition
                    val current = if (position == RecyclerView.NO_POSITION) null else tiles.getOrNull(position)
                    if (bitmap != null && current?.id == tile.id) {
                        favicon.setImageBitmap(bitmap)
                        favicon.visibility = View.VISIBLE
                    }
                }
            } else {
                emojiChip.visibility = View.VISIBLE
                favicon.visibility = View.GONE
            }

            bindStatus(tile)

            itemView.setOnClickListener { onClick(tile) }
            moreBtn.setOnClickListener { onMenu(tile, moreBtn) }
        }

        private fun bindStatus(tile: Tile) {
            val status = statuses[tile.id]
            if (!statusChecksEnabled || status == null) {
                statusDot.visibility = View.GONE
                statusText.visibility = View.GONE
                return
            }
            statusDot.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            val (colorRes, label) = when (status) {
                StatusChecker.Status.ONLINE -> R.color.status_online to "Online"
                StatusChecker.Status.DEGRADED -> R.color.status_degraded to "Reachable"
                StatusChecker.Status.OFFLINE -> R.color.status_offline to "Offline"
                StatusChecker.Status.UNKNOWN -> R.color.status_unknown to "Checking"
            }
            statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(itemView.context, colorRes))
            }
            statusText.text = label
        }
    }
}
