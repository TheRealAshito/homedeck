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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.therealashito.homedeck.R
import com.therealashito.homedeck.model.Tile
import com.therealashito.homedeck.model.Urls
import com.therealashito.homedeck.net.FaviconLoader
import com.therealashito.homedeck.net.StatusChecker

/**
 * Dashboard rows: category folder headers plus service tiles. Tapping a
 * header expands/collapses its folder; tiles drag-reorder within their own
 * category. Status dots come from [StatusChecker], debug mode shows the
 * probe detail instead of the plain label.
 */
class TileAdapter(
    private val onClick: (Tile) -> Unit,
    private val onMenu: (Tile, View) -> Unit,
    private val onHeaderClick: (String) -> Unit,
    private val onHeaderMenu: (String, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val tiles = mutableListOf<Tile>()
    private val rows = mutableListOf<Row>()
    val statuses = mutableMapOf<String, StatusChecker.Probe>()
    var collapsed: Set<String> = emptySet()
    var order: List<String> = emptyList()
    var statusChecksEnabled = true
    var debugMode = false

    fun submit(newTiles: List<Tile>) {
        tiles.clear()
        tiles.addAll(newTiles)
        resubmit()
    }

    /** Rebuilds rows from [tiles] + [collapsed] with item animations. */
    fun resubmit() {
        val newRows = Rows.build(tiles, collapsed, order)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = rows.size
            override fun getNewListSize(): Int = newRows.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                rows[oldPos].key == newRows[newPos].key

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                rows[oldPos] == newRows[newPos]
        })
        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(this)
    }

    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is Row.Header

    /** Drag reorder within one category; headers and cross-category refused. */
    fun moveRow(from: Int, to: Int): Boolean {
        val a = rows.getOrNull(from) ?: return false
        val b = rows.getOrNull(to) ?: return false
        if (a !is Row.Item || b !is Row.Item) return false
        if (a.tile.category != b.tile.category) return false
        val moved = rows.removeAt(from)
        rows.add(to, moved)
        notifyItemMoved(from, to)
        swapTiles(a.tile.id, b.tile.id)
        return true
    }

    private fun swapTiles(idA: String, idB: String) {
        val ixA = tiles.indexOfFirst { it.id == idA }
        val ixB = tiles.indexOfFirst { it.id == idB }
        if (ixA < 0 || ixB < 0) return
        val tmp = tiles[ixA]
        tiles[ixA] = tiles[ixB]
        tiles[ixB] = tmp
    }

    fun setStatus(id: String, status: StatusChecker.Probe) {
        statuses[id] = status
        val index = rows.indexOfFirst { it is Row.Item && it.tile.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    fun clearStatuses() {
        statuses.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (isHeader(position)) TYPE_HEADER else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_category_header, parent, false))
        } else {
            TileHolder(inflater.inflate(R.layout.item_tile, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Item -> (holder as TileHolder).bind(row.tile)
        }
    }

    inner class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.categoryName)
        private val count: TextView = itemView.findViewById(R.id.categoryCount)
        private val chevron: ImageButton = itemView.findViewById(R.id.chevron)

        fun bind(header: Row.Header) {
            name.text = header.category
            count.text = if (header.count == 1) "1 service" else "${header.count} services"
            chevron.rotation = if (header.expanded) 180f else 0f
            itemView.setOnClickListener { onHeaderClick(header.category) }
            chevron.setOnClickListener { onHeaderClick(header.category) }
            itemView.setOnLongClickListener {
                onHeaderMenu(header.category, it)
                true
            }
        }
    }

    inner class TileHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val emojiChip: TextView = itemView.findViewById(R.id.emojiChip)
        private val favicon: ImageView = itemView.findViewById(R.id.favicon)
        private val title: TextView = itemView.findViewById(R.id.tileTitle)
        private val host: TextView = itemView.findViewById(R.id.tileUrl)
        private val statusDot: View = itemView.findViewById(R.id.statusDot)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val moreBtn: ImageButton = itemView.findViewById(R.id.moreBtn)

        fun bind(tile: Tile) {
            val accent = Palette.color(tile.colorIndex)

            title.text = tile.name
            host.text = Urls.hostOf(tile.url)

            val chipShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Palette.dp(itemView.context, 15f).toFloat()
                setColor(Palette.withAlpha(accent, 0x38))
            }
            emojiChip.background = chipShape
            favicon.background = chipShape.constantState?.newDrawable()?.mutate()
            emojiChip.text = tile.emoji

            if (tile.emoji.isBlank()) {
                emojiChip.visibility = View.INVISIBLE
                favicon.visibility = View.GONE
                FaviconLoader.load(itemView.context, tile.url) { bitmap: Bitmap? ->
                    val position = bindingAdapterPosition
                    val current = if (position == RecyclerView.NO_POSITION) null else rows.getOrNull(position)
                    val currentTile = (current as? Row.Item)?.tile
                    if (bitmap != null && currentTile?.id == tile.id) {
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
            val probe = statuses[tile.id]
            if (!statusChecksEnabled || probe == null) {
                statusDot.visibility = View.GONE
                statusText.visibility = View.GONE
                return
            }
            statusDot.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            val (colorRes, label) = when (probe.status) {
                StatusChecker.Status.ONLINE -> R.color.status_online to "Online"
                StatusChecker.Status.DEGRADED -> R.color.status_degraded to "Reachable"
                StatusChecker.Status.OFFLINE -> R.color.status_offline to "Offline"
                StatusChecker.Status.UNKNOWN -> R.color.status_unknown to "Checking"
            }
            statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(itemView.context, colorRes))
            }
            statusText.text = if (debugMode) probe.detail else label
        }
    }

    companion object {
        private const val TYPE_TILE = 0
        private const val TYPE_HEADER = 1
    }
}
