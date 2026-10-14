package co.remotedesktop

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

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

    private fun tapAt(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            try {
                assertTrue(view.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun touch(view: View, action: Int, vararg points: Pair<Float, Float>, ids: List<Int> = points.indices.toList()) {
        val now = SystemClock.uptimeMillis()
        val properties = points.indices.map { i -> MotionEvent.PointerProperties().apply {
            id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coords = points.map { (x, y) -> MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(now, now, action, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun touchPanel(layer: String = "letters"): KeyboardPanel {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val panel = KeyboardPanel(activity)
        activity.setContentView(panel)
        if (layer == "symbols" || layer == "extraSymbols") panel.tap("?123")
        if (layer == "extraSymbols") panel.tap("=\\<")
        if (layer == "function") panel.tap("Fn")
        shadowOf(Looper.getMainLooper()).idle()
        panel.layoutKeys()
        return panel
    }

    private fun KeyboardPanel.layoutKeys() {
        measure(View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun KeyboardPanel.button(label: String) = buttons(this).single { it.contentDescription == label }

    private fun KeyboardPanel.center(label: String): Pair<Float, Float> {
        val key = button(label)
        val row = key.parent as View
        return (row.left + key.left + key.width / 2f) to (row.top + key.top + key.height / 2f)
    }

    @Test
    fun heldArrowShiftAppliesToEnterNavigationLettersAndOtherKeys() {
        for (orientation in listOf("w411dp-h891dp-port-420dpi", "w891dp-h411dp-land-420dpi")) {
            RuntimeEnvironment.setQualifiers(orientation)
            val panel = touchPanel("function")
            val keys = mutableListOf<Pair<String, String>>()
            val text = mutableListOf<String>()
            panel.onKey = { code, action -> keys.add(code to action) }
            panel.onText = { text.add(it) }
            val shift = panel.center("Shift")
            val shiftButton = panel.button("Shift")
            touch(panel, MotionEvent.ACTION_DOWN, shift)
            assertEquals(listOf("SHIFT" to "down"), keys)
            assertTrue(shiftButton.isSelected)
            val targets = listOf("Enter" to "ENTER", "Tab" to "TAB", "Left arrow" to "LEFT",
                "Home" to "HOME", "F1" to "F1", "A" to "A", "B" to "B", "Space" to "SPACE", "." to ".")
            for ((label, code) in targets) {
                val point = panel.center(label)
                touch(panel, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), shift, point)
                assertEquals(code to "press", keys.last())
                val count = keys.size
                touch(panel, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), shift, point)
                assertEquals(count, keys.size)
                assertSame(shiftButton, panel.button("Shift"))
            }
            touch(panel, MotionEvent.ACTION_UP, shift)
            assertEquals(listOf("SHIFT" to "down") + targets.map { it.second to "press" } + ("SHIFT" to "up"), keys)
            assertFalse(panel.button("Shift").isSelected)
            assertTrue(text.isEmpty())
            tapAt(panel, panel.center("c").first, panel.center("c").second)
            assertEquals(listOf("c"), text)
        }
    }

    @Test
    fun shiftEnterStillWorksWhenShiftFingerLiftsFirst() {
        val panel = touchPanel()
        val keys = mutableListOf<Pair<String, String>>()
        panel.onKey = { code, action -> keys.add(code to action) }
        val shift = panel.center("Shift")
        val enter = panel.center("Enter")
        touch(panel, MotionEvent.ACTION_DOWN, shift)
        touch(panel, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), shift, enter)
        touch(panel, MotionEvent.ACTION_POINTER_UP, shift, enter)
        touch(panel, MotionEvent.ACTION_UP, enter, ids = listOf(1))
        assertEquals(listOf("SHIFT" to "down", "ENTER" to "press", "SHIFT" to "up"), keys)
        assertFalse(panel.button("Shift").isSelected)
    }

    @Test
    fun heldArrowShiftCombinesWithCtrlAndDeletionRepeat() {
        val panel = touchPanel()
        val keys = mutableListOf<Pair<String, String>>()
        panel.onKey = { code, action -> keys.add(code to action) }
        panel.tap("Ctrl")
        val shift = panel.center("Shift")
        val backspace = panel.center("Backspace")
        touch(panel, MotionEvent.ACTION_DOWN, shift)
        touch(panel, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), shift, backspace)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        assertEquals(listOf("CTRL" to "down", "SHIFT" to "down") + List(4) { "BACKSPACE" to "press" }, keys)
        touch(panel, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), shift, backspace)
        touch(panel, MotionEvent.ACTION_UP, shift)
        panel.releaseAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf("CTRL" to "down", "SHIFT" to "down") + List(4) { "BACKSPACE" to "press" } +
            listOf("SHIFT" to "up", "CTRL" to "up"), keys)
    }

    @Test
    fun heldArrowShiftIsReleasedOnCancelMoveRebuildHideAndDetach() {
        for (stop in listOf("cancel", "move", "releaseAll", "layer", "hide", "detach", "rotate")) {
            val panel = touchPanel()
            val keys = mutableListOf<Pair<String, String>>()
            panel.onKey = { code, action -> keys.add(code to action) }
            val shift = panel.center("Shift")
            touch(panel, MotionEvent.ACTION_DOWN, shift)
            when (stop) {
                "cancel" -> touch(panel, MotionEvent.ACTION_CANCEL, shift)
                "move" -> touch(panel, MotionEvent.ACTION_MOVE, -100f to -100f)
                "releaseAll" -> panel.releaseAll()
                "layer" -> panel.tap("Fn")
                "hide" -> panel.visibility = View.GONE
                "detach" -> (panel.parent as ViewGroup).removeView(panel)
                "rotate" -> panel.dispatchConfigurationChanged(panel.resources.configuration)
            }
            shadowOf(Looper.getMainLooper()).idle()
            touch(panel, MotionEvent.ACTION_UP, shift)
            panel.tap("Enter")
            assertEquals(stop, listOf("SHIFT" to "down", "SHIFT" to "up", "ENTER" to "press"), keys)
            assertFalse(panel.button("Shift").isSelected)
        }
    }

    @Test
    fun touchShiftTapsStillCapitalizeAndDoubleTapsLockCaps() {
        val panel = touchPanel()
        val text = mutableListOf<String>()
        panel.onText = { text.add(it) }
        fun tap(label: String) {
            val (x, y) = panel.center(label)
            tapAt(panel, x, y)
            panel.layoutKeys()
        }
        tap("Shift")
        tap("A")
        tap("b")
        tap("Shift")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        tap("Shift")
        tap("C")
        tap("D")
        tap("Shift")
        tap("e")
        assertEquals(listOf("A", "b", "C", "D", "e"), text)
    }

    @Test
    fun shiftChordsConsumeOneShotShiftAndPreserveCapsLock() {
        for (capsLock in listOf(false, true)) {
            val panel = touchPanel()
            panel.tap("Shift")
            if (capsLock) panel.tap("Shift")
            panel.layoutKeys()
            val keys = mutableListOf<Pair<String, String>>()
            val text = mutableListOf<String>()
            panel.onKey = { code, action -> keys.add(code to action) }
            panel.onText = { text.add(it) }
            val shift = panel.center("Shift")
            touch(panel, MotionEvent.ACTION_DOWN, shift)
            for (label in listOf("A", "B", "Enter")) panel.tap(label)
            touch(panel, MotionEvent.ACTION_UP, shift)
            assertEquals(listOf("SHIFT" to "down", "A" to "press", "B" to "press", "ENTER" to "press", "SHIFT" to "up"), keys)
            assertEquals(capsLock, panel.button("Shift").isSelected)
            panel.tap(if (capsLock) "C" else "c")
            assertEquals(listOf(if (capsLock) "C" else "c"), text)
        }
    }

    @Test
    fun deletionKeysFireImmediatelyAndQuickTapsDoNotRepeatOnRelease() {
        for (layer in listOf("letters", "symbols", "extraSymbols", "function")) {
            val panel = touchPanel(layer)
            val keys = mutableListOf<Pair<String, String>>()
            panel.onKey = { code, action -> keys.add(code to action) }
            for ((label, code) in listOf("Del" to "DELETE", "Backspace" to "BACKSPACE")) {
                keys.clear()
                val point = panel.center(label)
                touch(panel, MotionEvent.ACTION_DOWN, point)
                assertEquals(listOf(code to "press"), keys)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                touch(panel, MotionEvent.ACTION_UP, point)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                assertEquals(listOf(code to "press"), keys)
                panel.tap(label) // Accessibility/programmatic clicks still work.
                assertEquals(List(2) { code to "press" }, keys)
            }
        }
    }

    @Test
    fun holdingDeletionKeysRepeatsQuicklyAfterADelayAndStopsOnRelease() {
        for ((label, code) in listOf("Del" to "DELETE", "Backspace" to "BACKSPACE")) {
            val panel = touchPanel()
            val keys = mutableListOf<Pair<String, String>>()
            panel.onKey = { key, action -> keys.add(key to action) }
            val point = panel.center(label)
            touch(panel, MotionEvent.ACTION_DOWN, point)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(listOf(code to "press"), keys)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertEquals(List(6) { code to "press" }, keys)
            touch(panel, MotionEvent.ACTION_UP, point)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(List(6) { code to "press" }, keys)
            assertFalse(panel.button(label).isPressed)
        }
    }

    @Test
    fun deletionRepeatStopsOnCancellationMovingAwayRebuildHideAndDetach() {
        for (label in listOf("Del", "Backspace")) {
            for (stop in listOf("cancel", "move", "releaseAll", "layer", "hide", "detach")) {
                val panel = touchPanel()
                val keys = mutableListOf<Pair<String, String>>()
                panel.onKey = { code, action -> keys.add(code to action) }
                val point = panel.center(label)
                touch(panel, MotionEvent.ACTION_DOWN, point)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
                val beforeStop = keys.toList()
                when (stop) {
                    "cancel" -> touch(panel, MotionEvent.ACTION_CANCEL, point)
                    "move" -> touch(panel, MotionEvent.ACTION_MOVE, -100f to -100f)
                    "releaseAll" -> panel.releaseAll()
                    "layer" -> panel.tap("?123")
                    "hide" -> panel.visibility = View.GONE
                    "detach" -> (panel.parent as ViewGroup).removeView(panel)
                }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                touch(panel, MotionEvent.ACTION_UP, point)
                assertEquals("$label/$stop", beforeStop, keys)
                assertFalse(buttons(panel).any { it.isPressed })
            }
        }
    }

    @Test
    fun simultaneousDeletionHoldsStopIndependentlyAndRespectModifiers() {
        val panel = touchPanel()
        val keys = mutableListOf<Pair<String, String>>()
        panel.onKey = { code, action -> keys.add(code to action) }
        panel.tap("Ctrl")
        panel.tap("Sticky Shift")
        panel.layoutKeys()
        val delete = panel.center("Del")
        val backspace = panel.center("Backspace")
        touch(panel, MotionEvent.ACTION_DOWN, delete)
        touch(panel, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), delete, backspace)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        assertEquals(4, keys.count { it == "DELETE" to "press" })
        assertEquals(4, keys.count { it == "BACKSPACE" to "press" })
        touch(panel, MotionEvent.ACTION_POINTER_UP, delete, backspace)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(4, keys.count { it == "DELETE" to "press" })
        assertEquals(7, keys.count { it == "BACKSPACE" to "press" })
        touch(panel, MotionEvent.ACTION_UP, backspace, ids = listOf(1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        panel.releaseAll()
        assertEquals("CTRL" to "down", keys.first())
        assertEquals("CTRL" to "up", keys.last())
        for (press in keys.drop(1).dropLast(1).chunked(3)) {
            assertEquals("SHIFT" to "down", press.first())
            assertTrue(press[1].first in listOf("DELETE", "BACKSPACE"))
            assertEquals("SHIFT" to "up", press.last())
        }
        assertEquals(7, keys.count { it == "BACKSPACE" to "press" })
    }

    private fun KeyboardPanel.gap(first: String, second: String, fraction: Float): Pair<Float, Float> {
        val a = button(first)
        val b = button(second)
        val row = a.parent as View
        return (row.left + a.right + (b.left - a.right) * fraction) to (row.top + a.top + a.height / 2f)
    }

    @Test
    fun gapsChooseTheClosestKeyAcrossOrientationsAndLayers() {
        for (qualifiers in listOf("w411dp-h891dp-port-420dpi", "w891dp-h411dp-land-420dpi")) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            for ((layer, labels) in listOf(
                "letters" to ("q" to "w"), "symbols" to ("1" to "2"),
                "extraSymbols" to ("~" to "`"), "function" to ("F1" to "F2"),
            )) {
                val panel = touchPanel(layer)
                val text = mutableListOf<String>()
                val keys = mutableListOf<Pair<String, String>>()
                panel.onText = { text.add(it) }
                panel.onKey = { code, action -> keys.add(code to action) }
                for (fraction in listOf(.25f, .75f)) {
                    val (x, y) = panel.gap(labels.first, labels.second, fraction)
                    tapAt(panel, x, y)
                }
                if (layer == "function") {
                    assertEquals(listOf("F1" to "press", "F2" to "press"), keys)
                    assertTrue(text.isEmpty())
                } else {
                    assertEquals(listOf(labels.first, labels.second), text)
                    assertTrue(keys.isEmpty())
                }
            }
            val panel = touchPanel()
            val text = mutableListOf<String>()
            val keys = mutableListOf<Pair<String, String>>()
            panel.onText = { text.add(it) }
            panel.onKey = { code, action -> keys.add(code to action) }
            val q = panel.button("q")
            val qRow = q.parent as View
            tapAt(panel, qRow.left + q.left + q.width / 2f, qRow.top + q.bottom + 1f)
            val a = panel.button("a")
            val aRow = a.parent as ViewGroup
            tapAt(panel, aRow.left + a.left + a.width / 2f, aRow.top + a.top - 1f)
            tapAt(panel, aRow.left + a.left - 1f, aRow.top + a.top + a.height / 2f)
            val space = panel.button("Space")
            tapAt(panel, (space.parent as View).left + space.left + space.width / 2f, panel.height - 1f)
            val backspace = panel.button("Backspace")
            tapAt(panel, panel.width - 1f, (backspace.parent as View).top + backspace.top + backspace.height / 2f)
            val shift = panel.button("Shift")
            tapAt(panel, 1f, (shift.parent as View).top + shift.top + shift.height / 2f)
            panel.tap("Q")
            assertEquals(listOf("q", "a", "a", " ", "Q"), text)
            assertEquals(listOf("BACKSPACE" to "press", "SHIFT" to "down", "SHIFT" to "up"), keys)
        }
    }

    @Test
    fun simultaneousGapTouchesTypeEachKeyOnce() {
        val panel = touchPanel()
        val text = mutableListOf<String>()
        panel.onText = { text.add(it) }
        val q = panel.gap("q", "w", .25f)
        val w = panel.gap("q", "w", .75f)
        touch(panel, MotionEvent.ACTION_DOWN, q)
        touch(panel, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, w)
        assertTrue(panel.button("q").isPressed)
        assertTrue(panel.button("w").isPressed)
        touch(panel, MotionEvent.ACTION_POINTER_UP, q, w)
        touch(panel, MotionEvent.ACTION_UP, w, ids = listOf(1))
        assertEquals(listOf("q", "w"), text)
        assertFalse(panel.button("q").isPressed)
        assertFalse(panel.button("w").isPressed)
    }

    @Test
    fun cancelledMovedAndRebuiltGapTouchesDoNotType() {
        val panel = touchPanel()
        val text = mutableListOf<String>()
        panel.onText = { text.add(it) }
        val gap = panel.gap("q", "w", .25f)
        touch(panel, MotionEvent.ACTION_DOWN, gap)
        touch(panel, MotionEvent.ACTION_CANCEL, gap)
        touch(panel, MotionEvent.ACTION_DOWN, gap)
        touch(panel, MotionEvent.ACTION_MOVE, panel.width - 1f to gap.second)
        touch(panel, MotionEvent.ACTION_UP, panel.width - 1f to gap.second)
        touch(panel, MotionEvent.ACTION_DOWN, gap)
        panel.tap("?123")
        touch(panel, MotionEvent.ACTION_UP, gap)
        assertTrue(text.isEmpty())
        assertFalse(buttons(panel).any { it.isPressed })
    }

    @Test
    fun keyboardBackgroundChoosesKeysWithoutClickingTheDesktop() {
        for (qualifiers in listOf("w411dp-h891dp-port-420dpi", "w891dp-h411dp-land-420dpi")) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val context = activity
            val root = FrameLayout(context)
            val screen = RemoteScreenView(context)
            screen.setDesktopSize(1920, 1080)
            val mouse = mutableListOf<Pair<String, String>>()
            screen.onMouseButton = { button, action, _, _ -> mouse.add(button to action) }
            root.addView(screen, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val panel = KeyboardPanel(context)
            val text = mutableListOf<String>()
            val keys = mutableListOf<Pair<String, String>>()
            panel.onText = { text.add(it) }
            panel.onKey = { code, action -> keys.add(code to action) }
            root.addView(panel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            activity.setContentView(root)
            val width = context.resources.displayMetrics.widthPixels
            val height = context.resources.displayMetrics.heightPixels
            for (layer in listOf("letters", "symbols", "function")) {
                if (layer == "symbols") panel.tap("?123")
                if (layer == "function") { panel.tap("ABC"); panel.tap("Fn") }
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                shadowOf(Looper.getMainLooper()).idle()
                val firstRow = panel.getChildAt(0) as ViewGroup
                val firstKey = firstRow.getChildAt(0)
                val typingRow = panel.getChildAt(2) as ViewGroup
                // Outer padding, horizontal key margins, row gaps, and bottom padding.
                for ((x, y) in listOf(
                    1f to 1f,
                    (firstRow.left + firstKey.right + 1f) to (firstRow.top + firstKey.top + firstKey.height / 2f),
                    (typingRow.left + typingRow.width / 2f) to (typingRow.bottom - 1f),
                    (panel.width / 2f) to (panel.height - 1f),
                )) {
                    tapAt(root, panel.left + x, panel.top + y)
                }
                if (layer != "symbols") {
                    val letterRow = buttons(panel).single { it.contentDescription.toString().equals("a", ignoreCase = true) }.parent as ViewGroup
                    val spacer = letterRow.getChildAt(0)
                    tapAt(root, panel.left + letterRow.left + spacer.width / 2f,
                        panel.top + letterRow.top + letterRow.height / 2f)
                }
                assertTrue("$qualifiers/$layer: background must not click the mouse", mouse.isEmpty())
            }
            text.clear()
            keys.clear()
            panel.releaseAll()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
            val q = buttons(panel).single { it.contentDescription == "q" }
            val qRow = q.parent as View
            tapAt(root, panel.left + qRow.left + q.left + q.width / 2f,
                panel.top + qRow.top + q.top + q.height / 2f)
            assertEquals(listOf("q"), text)
            assertTrue(mouse.isEmpty())
            tapAt(root, width / 2f, panel.top / 2f)
            assertEquals(listOf("left" to "down", "left" to "up"), mouse)
            panel.visibility = View.GONE
            tapAt(root, width / 2f, height - 1f)
            assertEquals(List(2) { listOf("left" to "down", "left" to "up") }.flatten(), mouse)
        }
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
        val modifierRow = panel.getChildAt(0) as ViewGroup
        assertEquals("Ctrl", modifierRow.getChildAt(0).contentDescription)
        assertEquals("Sticky Shift", modifierRow.getChildAt(1).contentDescription)
        panel.tap("Sticky Shift")
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
    fun typingShiftCapitalizesOnlyTheNextLetterAndIgnoresNonLetters() {
        val panel = KeyboardPanel(RuntimeEnvironment.getApplication())
        val text = mutableListOf<String>()
        val keys = mutableListOf<Pair<String, String>>()
        panel.onText = { text.add(it) }
        panel.onKey = { code, action -> keys.add(code to action) }
        panel.tap("Shift")
        assertTrue(panel.button("Shift").isSelected)
        panel.tap("Space")
        panel.tap(".")
        panel.tap("Backspace")
        panel.tap("Left arrow")
        panel.tap("?123")
        panel.tap("1")
        panel.tap("ABC")
        panel.tap("A")
        assertFalse(panel.button("Shift").isSelected)
        panel.tap("b")
        assertEquals(listOf(" ", ".", "1", "A", "b"), text)
        assertEquals(listOf("BACKSPACE" to "press", "LEFT" to "press"), keys)
    }

    @Test
    fun doubleTapLocksCapitalLettersUntilShiftIsTappedAgain() {
        val panel = KeyboardPanel(RuntimeEnvironment.getApplication())
        val text = mutableListOf<String>()
        panel.onText = { text.add(it) }
        panel.tap("Shift")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        panel.tap("Shift")
        panel.tap("A")
        panel.tap("B")
        panel.tap("?123")
        panel.tap("ABC")
        assertTrue(panel.button("Shift").isSelected)
        panel.tap("C")
        panel.tap("Shift")
        assertFalse(panel.button("Shift").isSelected)
        panel.tap("d")
        assertEquals(listOf("A", "B", "C", "d"), text)
    }

    @Test
    fun slowSecondTapTurnsShiftOffAndTypingSeparatesShiftTapSequences() {
        val panel = KeyboardPanel(RuntimeEnvironment.getApplication())
        val text = mutableListOf<String>()
        panel.onText = { text.add(it) }
        panel.tap("Shift")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getDoubleTapTimeout() + 1L))
        panel.tap("Shift")
        panel.tap("a")
        panel.tap("Shift")
        panel.tap("B")
        panel.tap("Shift")
        panel.tap("C")
        panel.tap("d")
        assertEquals(listOf("a", "B", "C", "d"), text)
    }

    @Test
    fun typingAndStickyShiftStayIndependentAndReleaseAllClearsBoth() {
        val panel = KeyboardPanel(RuntimeEnvironment.getApplication())
        val text = mutableListOf<String>()
        panel.onText = { text.add(it) }
        panel.tap("Sticky Shift")
        panel.tap("Shift")
        panel.tap("A")
        assertTrue(panel.button("Sticky Shift").isSelected)
        assertFalse(panel.button("Shift").isSelected)
        panel.tap("B")
        panel.tap("Sticky Shift")
        panel.tap("c")
        panel.tap("Shift")
        panel.tap("Shift")
        panel.tap("Sticky Shift")
        panel.releaseAll()
        assertFalse(panel.button("Sticky Shift").isSelected)
        assertFalse(panel.button("Shift").isSelected)
        panel.tap("d")
        assertEquals(listOf("A", "B", "c", "d"), text)
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
