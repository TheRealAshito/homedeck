package com.therealashito.homedeck

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.therealashito.homedeck.data.TileStore
import com.therealashito.homedeck.model.Tile
import com.therealashito.homedeck.model.Urls
import com.therealashito.homedeck.ui.EmojiPickerDialog
import com.therealashito.homedeck.ui.Palette
import java.util.UUID

/** Create or edit one dashboard tile. */
class EditTileActivity : ThemedActivity() {

    private lateinit var tileStore: TileStore
    private lateinit var nameLayout: TextInputLayout
    private lateinit var urlLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private lateinit var urlInput: TextInputEditText
    private lateinit var emojiBtn: MaterialButton
    private lateinit var colorRow: LinearLayout
    private lateinit var deleteBtn: MaterialButton

    private var existingId: String? = null
    private var emoji: String = ""
    private var colorIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_edit_tile)
        tileStore = TileStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        nameLayout = findViewById(R.id.nameLayout)
        urlLayout = findViewById(R.id.urlLayout)
        nameInput = findViewById(R.id.nameInput)
        urlInput = findViewById(R.id.urlInput)
        emojiBtn = findViewById(R.id.emojiBtn)
        colorRow = findViewById(R.id.colorRow)
        deleteBtn = findViewById(R.id.deleteBtn)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        existingId = intent.getStringExtra(EXTRA_TILE_ID)
        val existing = existingId?.let { id -> tileStore.load().firstOrNull { it.id == id } }
        if (existing != null) {
            toolbar.title = "Edit service"
            nameInput.setText(existing.name)
            urlInput.setText(existing.url)
            emoji = existing.emoji
            colorIndex = existing.colorIndex
            deleteBtn.visibility = View.VISIBLE
        } else {
            toolbar.title = "Add service"
            nameInput.setText(intent.getStringExtra(EXTRA_PREFILL_NAME).orEmpty())
            urlInput.setText(intent.getStringExtra(EXTRA_PREFILL_URL).orEmpty())
            colorIndex = tileStore.load().size % Palette.accents.size
        }
        renderEmoji()
        renderColors()

        toolbar.inflateMenu(R.menu.menu_edit)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> save()
            }
            true
        }

        emojiBtn.setOnClickListener {
            EmojiPickerDialog(this) { picked ->
                emoji = picked
                renderEmoji()
            }.show()
        }

        deleteBtn.setOnClickListener { confirmDelete() }
    }

    private fun save() {
        val name = nameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            nameLayout.error = "Give it a name"
            return
        }
        nameLayout.error = null

        val url = Urls.normalize(urlInput.text?.toString().orEmpty())
        if (url == null) {
            urlLayout.error = "Enter a valid address, e.g. kuma.lan"
            return
        }
        urlLayout.error = null

        val tile = Tile(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            url = url,
            emoji = emoji,
            colorIndex = colorIndex
        )
        val tiles = tileStore.load()
        val index = tiles.indexOfFirst { it.id == tile.id }
        if (index >= 0) tiles[index] = tile else tiles.add(tile)
        tileStore.save(tiles)
        finish()
    }

    private fun confirmDelete() {
        val name = nameInput.text?.toString()?.trim().orEmpty().ifBlank { "this service" }
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove $name?")
            .setPositiveButton("Remove") { _, _ ->
                val id = existingId
                if (id != null) {
                    tileStore.save(tileStore.load().filterNot { it.id == id })
                }
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderEmoji() {
        emojiBtn.text = if (emoji.isBlank()) "Pick an icon" else emoji
    }

    private fun renderColors() {
        colorRow.removeAllViews()
        Palette.accents.forEachIndexed { index, color ->
            val dot = View(this)
            val size = Palette.dp(this, 38f)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginEnd = Palette.dp(this, 12f)
            dot.layoutParams = params
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (index == colorIndex) {
                    setStroke(
                        Palette.dp(this@EditTileActivity, 3f),
                        ContextCompat.getColor(this@EditTileActivity, R.color.on_background)
                    )
                }
            }
            dot.setOnClickListener {
                colorIndex = index
                renderColors()
            }
            colorRow.addView(dot)
        }
    }

    companion object {
        const val EXTRA_TILE_ID = "extra_tile_id"
        const val EXTRA_PREFILL_NAME = "extra_prefill_name"
        const val EXTRA_PREFILL_URL = "extra_prefill_url"

        fun start(
            context: Context,
            tileId: String? = null,
            prefillName: String? = null,
            prefillUrl: String? = null
        ) {
            val intent = Intent(context, EditTileActivity::class.java)
            tileId?.let { intent.putExtra(EXTRA_TILE_ID, it) }
            prefillName?.let { intent.putExtra(EXTRA_PREFILL_NAME, it) }
            prefillUrl?.let { intent.putExtra(EXTRA_PREFILL_URL, it) }
            context.startActivity(intent)
        }
    }
}
