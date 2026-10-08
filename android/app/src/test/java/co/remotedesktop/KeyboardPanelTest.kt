package co.remotedesktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardPanelTest {
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun KeyboardPanel.tap(label: String) {
        buttons(this).single { it.contentDescription.toString() == label }.performClick()
    }

    @Test
    fun numberLayerSwitchesBackAndForthAndEmitsLiteralSymbols() {
        val panel = KeyboardPanel(RuntimeEnvironment.getApplication())
        val text = mutableListOf<String>()
        panel.onText = { text.add(it) }
        assertFalse(buttons(panel).any { it.text == "1" })
        panel.tap("?123")
        panel.tap("1")
        panel.tap("@")
        panel.tap("!")
        panel.tap("=\\<")
        panel.tap("_")
        panel.tap(">")
        panel.tap("ABC")
        assertFalse(buttons(panel).any { it.text == "1" })
        panel.tap("q")
        panel.tap("Space")
        assertEquals(listOf("1", "@", "!", "_", ">", "q", " "), text)
    }

    @Test
    fun shortcutsAndModifierReleaseSurviveLayerRebuilds() {
        val panel = KeyboardPanel(RuntimeEnvironment.getApplication())
        val keys = mutableListOf<Pair<String, String>>()
        val text = mutableListOf<String>()
        panel.onKey = { code, action -> keys.add(code to action) }
        panel.onText = { text.add(it) }
        panel.tap("Ctrl")
        panel.tap("Shift")
        panel.tap("A")
        panel.tap("Left arrow")
        panel.tap("Fn")
        panel.tap("F12")
        panel.releaseAll()
        panel.tap("a")
        assertEquals(listOf(
            "CTRL" to "down",
            "SHIFT" to "down", "A" to "press", "SHIFT" to "up",
            "SHIFT" to "down", "LEFT" to "press", "SHIFT" to "up",
            "SHIFT" to "down", "F12" to "press", "SHIFT" to "up",
            "CTRL" to "up",
        ), keys)
        assertEquals(listOf("a"), text)
    }

    @Test
    fun portraitAndLandscapeKeepLabelsVisibleAndRenderPreviews() {
        for ((name, qualifiers) in listOf(
            "portrait" to "w411dp-h891dp-port-420dpi",
            "landscape" to "w891dp-h411dp-land-420dpi",
        )) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val panel = KeyboardPanel(RuntimeEnvironment.getApplication())
            for (layer in listOf("letters", "symbols", "function")) {
                if (layer == "symbols") panel.tap("?123")
                if (layer == "function") { panel.tap("ABC"); panel.tap("Fn") }
                val width = panel.resources.displayMetrics.widthPixels
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                panel.layout(0, 0, width, panel.measuredHeight)
                buttons(panel).forEach { it.onPreDraw() }
                assertTrue("$name/$layer leaves desktop space", panel.height < panel.resources.displayMetrics.heightPixels * .70f)
                for (button in buttons(panel)) {
                    assertTrue("Keys stay inside their row", button.top >= 0 && button.bottom <= (button.parent as View).height)
                    assertTrue("$name/$layer: ${button.contentDescription}",
                        button.paint.measureText(button.text.toString()) <= button.width)
                }
                val bitmap = Bitmap.createBitmap(width, panel.height, Bitmap.Config.ARGB_8888)
                panel.draw(Canvas(bitmap))
                val file = File("build/reports/keyboard/$name-$layer.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
