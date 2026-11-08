package com.therealashito.homedeck

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.therealashito.homedeck.data.SettingsStore
import com.therealashito.homedeck.data.TileStore
import com.therealashito.homedeck.debug.AppLog
import com.therealashito.homedeck.model.Tile
import com.therealashito.homedeck.net.StatusChecker
import com.therealashito.homedeck.net.UpdateChecker
import com.therealashito.homedeck.ui.TileAdapter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ThemedActivity() {

    private lateinit var tileStore: TileStore
    private lateinit var settings: SettingsStore
    private lateinit var adapter: TileAdapter
    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var emptyState: View
    private val statusExecutor = Executors.newFixedThreadPool(4)
    private val tiles = mutableListOf<Tile>()
    private var dashboardReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileStore = TileStore(this)
        settings = SettingsStore(this)
        enableEdgeToEdge()
        try {
            setContentView(R.layout.activity_main)
            setupDashboard()
            AppLog.i("Main", "Dashboard ready (${tiles.size} tiles, debug=${settings.debugMode})")
        } catch (t: Throwable) {
            AppLog.e("Main", "Startup failed", t)
            if (settings.debugMode) showStartupError(t) else throw t
        }
    }

    private fun setupDashboard() {
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
            onMenu = { tile, anchor -> showTileMenu(tile, anchor) },
            onHeaderClick = { category ->
                settings.toggleCollapsed(category)
                adapter.collapsed = settings.collapsedCategories
                adapter.resubmit()
            },
            onHeaderMenu = { category, anchor -> showCategoryMenu(category, anchor) }
        )

        val spanCount = maxOf(2, resources.configuration.screenWidthDp / 150)
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) spanCount else 1
        }
        recycler.layoutManager = layoutManager
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
                return adapter.moveRow(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition
                )
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
        toolbar.menu.findItem(R.id.action_diagnostics)?.isVisible = settings.debugMode
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.action_diagnostics -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
            true
        }
        dashboardReady = true
    }

    override fun onResume() {
        super.onResume()
        if (!dashboardReady) return
        reload()
        maybeCheckForUpdates()
    }

    /**
     * Opt-in update check (Settings), throttled to once a day. Only shows a
     * snackbar when a newer release actually exists.
     */
    private fun maybeCheckForUpdates() {
        if (!settings.autoUpdateCheck) return
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheckMs < UPDATE_CHECK_INTERVAL_MS) return
        settings.lastUpdateCheckMs = now
        val current = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        statusExecutor.execute {
            val release = UpdateChecker.fetchLatest()
            if (release != null && UpdateChecker.isNewer(release.tag, current)) {
                runOnUiThread {
                    AppLog.i("Main", "update available: ${release.tag} (installed: $current)")
                    Snackbar.make(
                        findViewById(R.id.root),
                        "Update ${release.tag} is available",
                        Snackbar.LENGTH_LONG
                    ).setAction("Download") {
                        UpdateChecker.openRelease(this, release)
                    }.show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        statusExecutor.shutdown()
    }

    private fun reload() {
        tiles.clear()
        tiles.addAll(tileStore.load())
        adapter.statusChecksEnabled = settings.statusChecks
        adapter.debugMode = settings.debugMode
        adapter.collapsed = settings.collapsedCategories
        adapter.submit(tiles)
        emptyState.visibility = if (tiles.isEmpty()) View.VISIBLE else View.GONE
        updateSubtitle()
        refreshStatuses()
    }

    private fun updateSubtitle() {
        val prefix = if (settings.debugMode) "DEBUG \u00B7 " else ""
        toolbar.subtitle = if (settings.statusChecks) {
            val online = tiles.count { adapter.statuses[it.id]?.status == StatusChecker.Status.ONLINE }
            "$prefix${tiles.size} services \u00B7 $online online"
        } else {
            "$prefix${tiles.size} services"
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
                val status = StatusChecker.probe(tile.url, trusted)
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
        popup.menu.add(0, MENU_CATEGORY, 3, "Move to category")
        popup.menu.add(0, MENU_REMOVE, 4, "Remove")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> EditTileActivity.start(this, tile.id)
                MENU_BROWSER -> openExternally(tile.url)
                MENU_DUPLICATE -> duplicate(tile)
                MENU_CATEGORY -> showMoveToCategory(tile)
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

    private fun showStartupError(t: Throwable) {
        val scroll = ScrollView(this)
        val text = TextView(this).apply {
            setPadding(32, 48, 32, 32)
            text = "Homedeck failed to start, but debug mode kept it open.\n\n" +
                t.stackTraceToString().take(4000) +
                "\n\nCopy this from Diagnostics > Log tail into the bug report."
            setTextIsSelectable(true)
            textSize = 12f
        }
        scroll.addView(text)
        setContentView(scroll)
    }

    private fun showCategoryMenu(category: String, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, CATEGORY_ADD, 0, "Add service here")
        popup.menu.add(0, CATEGORY_RENAME, 1, "Rename folder")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                CATEGORY_ADD -> EditTileActivity.start(
                    this, tileId = null, prefillCategory = category
                )
                CATEGORY_RENAME -> renameCategory(category)
            }
            true
        }
        popup.show()
    }

    private fun renameCategory(category: String) {
        val input = EditText(this).apply {
            setText(category)
            setSelection(category.length)
            hint = "Folder name"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename folder")
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != category) {
                    tileStore.save(tileStore.load().map {
                        if (it.category == category) it.copy(category = newName) else it
                    })
                    val collapsedNow = settings.collapsedCategories.toMutableSet()
                    if (collapsedNow.remove(category)) collapsedNow.add(newName)
                    settings.collapsedCategories = collapsedNow
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveToCategory(tile: Tile) {
        val categories = tileStore.load()
            .map { it.category }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val options = categories + "No category"
        MaterialAlertDialogBuilder(this)
            .setTitle("Move ${tile.name} to")
            .setItems(options.toTypedArray()) { _, which ->
                val target = if (which >= categories.size) "" else categories[which]
                tileStore.save(tileStore.load().map {
                    if (it.id == tile.id) it.copy(category = target) else it
                })
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val CATEGORY_ADD = 11
        private const val CATEGORY_RENAME = 12
        private const val MENU_EDIT = 1
        private const val MENU_BROWSER = 2
        private const val MENU_DUPLICATE = 3
        private const val MENU_REMOVE = 4
        private const val MENU_CATEGORY = 5
    }
}
