package com.therealashito.homedeck

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.therealashito.homedeck.data.SettingsStore
import com.therealashito.homedeck.data.TileStore
import com.therealashito.homedeck.model.Tile
import com.therealashito.homedeck.net.StatusChecker
import com.therealashito.homedeck.ui.TileAdapter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var tileStore: TileStore
    private lateinit var settings: SettingsStore
    private lateinit var adapter: TileAdapter
    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var emptyState: View
    private val statusExecutor = Executors.newFixedThreadPool(4)
    private val tiles = mutableListOf<Tile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileStore = TileStore(this)
        settings = SettingsStore(this)
        AppCompatDelegate.setDefaultNightMode(settings.themeMode)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        swipe = findViewById(R.id.swipe)
        emptyState = findViewById(R.id.emptyState)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val fab = findViewById<ExtendedFloatingActionButton>(R.id.fab)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        adapter = TileAdapter(
            onClick = { tile -> startActivity(WebViewActivity.intent(this, tile)) },
            onMenu = { tile, anchor -> showTileMenu(tile, anchor) }
        )

        val spanCount = maxOf(2, resources.configuration.screenWidthDp / 150)
        recycler.layoutManager = GridLayoutManager(this, spanCount)
        recycler.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Swipe-to-dismiss is disabled; removal lives in the tile menu.
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                tileStore.save(adapter.tiles)
            }
        }).attachToRecyclerView(recycler)

        swipe.setColorSchemeResources(R.color.primary)
        swipe.setOnRefreshListener { refreshStatuses() }
        fab.setOnClickListener { EditTileActivity.start(this) }

        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusExecutor.shutdown()
    }

    private fun reload() {
        tiles.clear()
        tiles.addAll(tileStore.load())
        adapter.statusChecksEnabled = settings.statusChecks
        adapter.submit(tiles)
        emptyState.visibility = if (tiles.isEmpty()) View.VISIBLE else View.GONE
        updateSubtitle()
        refreshStatuses()
    }

    private fun updateSubtitle() {
        toolbar.subtitle = if (settings.statusChecks) {
            val online = tiles.count { adapter.statuses[it.id] == StatusChecker.Status.ONLINE }
            "${tiles.size} services \u00B7 $online online"
        } else {
            "${tiles.size} services"
        }
    }

    private fun refreshStatuses() {
        if (!settings.statusChecks) {
            adapter.clearStatuses()
            updateSubtitle()
            return
        }
        val snapshot = tiles.toList()
        if (snapshot.isEmpty()) {
            swipe.isRefreshing = false
            return
        }
        swipe.isRefreshing = true
        val remaining = AtomicInteger(snapshot.size)
        val trusted = settings.trustedHosts()
        for (tile in snapshot) {
            statusExecutor.execute {
                val status = StatusChecker.check(tile.url, trusted)
                runOnUiThread {
                    adapter.setStatus(tile.id, status)
                    if (remaining.decrementAndGet() == 0) {
                        swipe.isRefreshing = false
                        updateSubtitle()
                    }
                }
            }
        }
    }

    private fun showTileMenu(tile: Tile, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_EDIT, 0, "Edit")
        popup.menu.add(0, MENU_BROWSER, 1, "Open in browser")
        popup.menu.add(0, MENU_DUPLICATE, 2, "Duplicate")
        popup.menu.add(0, MENU_REMOVE, 3, "Remove")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> EditTileActivity.start(this, tile.id)
                MENU_BROWSER -> openExternally(tile.url)
                MENU_DUPLICATE -> duplicate(tile)
                MENU_REMOVE -> confirmRemove(tile)
            }
            true
        }
        popup.show()
    }

    private fun openExternally(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun duplicate(tile: Tile) {
        val tiles = tileStore.load()
        val copy = tile.copy(id = UUID.randomUUID().toString())
        val index = tiles.indexOfFirst { it.id == tile.id }
        if (index >= 0) tiles.add(index + 1, copy) else tiles.add(copy)
        tileStore.save(tiles)
        reload()
    }

    private fun confirmRemove(tile: Tile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove ${tile.name}?")
            .setMessage("Only the shortcut is removed. The service itself is not affected.")
            .setPositiveButton("Remove") { _, _ ->
                tileStore.save(tileStore.load().filterNot { it.id == tile.id })
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val MENU_EDIT = 1
        private const val MENU_BROWSER = 2
        private const val MENU_DUPLICATE = 3
        private const val MENU_REMOVE = 4
    }
}
