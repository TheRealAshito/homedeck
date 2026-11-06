package com.therealashito.homedeck

import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.therealashito.homedeck.model.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Boots every activity the way the device does and forces a real
 * measure/layout pass so inflation and binding of item tiles execute.
 * Any startup crash the device showed must fail one of these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupSmokeTest {

    private fun layOut(activity: android.app.Activity) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, 1080, 1920)
    }

    @Test
    fun dashboardLaunchesInflatesAndLaysOut() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        layOut(activity)

        val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
        assertNotNull(recycler)
        assertNotNull(activity.findViewById(R.id.fab))
        assertNotNull(activity.findViewById(R.id.toolbar))

        @Suppress("UNCHECKED_CAST")
        val adapter = recycler.adapter as? com.therealashito.homedeck.ui.TileAdapter
        assertNotNull(adapter)
        assertEquals(6, adapter!!.tiles.size)
    }

    @Test
    fun webviewActivityLaunches() {
        val intent = WebViewActivity.intent(
            ApplicationProvider.getApplicationContext(),
            Tile(name = "Test", url = "http://t.lan")
        )
        val controller = Robolectric.buildActivity(WebViewActivity::class.java, intent).setup()
        val activity = controller.get()
        layOut(activity)
        assertNotNull(activity.findViewById(R.id.webview))
    }

    @Test
    fun editTileActivityLaunchesInAddMode() {
        val controller = Robolectric.buildActivity(EditTileActivity::class.java).setup()
        val activity = controller.get()
        layOut(activity)
        assertNotNull(activity.findViewById(R.id.nameInput))
    }

    @Test
    fun settingsActivityLaunches() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        layOut(activity)
        assertNotNull(activity.findViewById(R.id.themeGroup))
    }
}
