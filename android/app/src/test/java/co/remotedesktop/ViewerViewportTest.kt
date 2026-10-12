package co.remotedesktop

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h800dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ViewerViewportTest {
    private fun screen(state: Bundle? = null): RemoteScreenView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = RemoteScreenView(activity)
        view.restoreViewportState(state)
        activity.setContentView(view)
        view.setDesktopSize(4000, 3200)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        return view
    }

    private fun touch(view: RemoteScreenView, action: Int, vararg points: Pair<Float, Float>) {
        val now = SystemClock.uptimeMillis()
        val properties = points.indices.map { i -> MotionEvent.PointerProperties().apply {
            id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coords = points.map { point -> MotionEvent.PointerCoords().apply {
            x = point.first; y = point.second; pressure = 1f; size = 1f
        } }.toTypedArray()
        MotionEvent.obtain(now, now, action, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0).also {
            view.onTouchEvent(it)
            it.recycle()
        }
    }

    private fun beginPinch(view: RemoteScreenView, distance: Float) {
        val x = view.width / 2f
        val y = view.height / 2f
        touch(view, MotionEvent.ACTION_DOWN, x - distance to y)
        touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            x - distance to y, x + distance to y)
    }

    private fun pinch(view: RemoteScreenView, distance: Float, offsetX: Float = 0f, offsetY: Float = 0f) {
        val x = view.width / 2f + offsetX
        val y = view.height / 2f + offsetY
        touch(view, MotionEvent.ACTION_MOVE, x - distance to y, x + distance to y)
    }

    private fun zoomAndPan(view: RemoteScreenView) {
        beginPinch(view, 100f)
        pinch(view, 200f)
        pinch(view, 300f)
        pinch(view, 400f, 100f, -60f)
        view.releaseInput()
        assertTrue(view.saveViewportState()!!.getFloat("zoom") > 1.5f)
    }

    private fun assertSameViewport(expected: Bundle, actual: Bundle) {
        for (key in listOf("zoom", "centerX", "centerY", "cursorX", "cursorY")) {
            assertEquals(key, expected.getFloat(key), actual.getFloat(key), .00001f)
        }
    }

    @Test
    fun backgroundLayoutAndReturnKeepZoomAndPosition() {
        val view = screen()
        zoomAndPan(view)
        val before = view.saveViewportState()!!
        val w = view.width
        val h = view.height
        view.layout(0, 0, 0, 0)
        view.setTopOcclusion(0)
        view.setBottomOcclusion(0)
        view.layout(0, 0, w, h)
        assertSameViewport(before, view.saveViewportState()!!)

        view.layout(0, 0, w + 100, h - 100)
        assertSameViewport(before, view.saveViewportState()!!)
    }

    @Test
    fun firstPinchAfterReturningChangesScaleOnlyByFingerMovement() {
        val view = screen()
        zoomAndPan(view)
        val w = view.width
        val h = view.height
        view.layout(0, 0, 0, 0)
        view.layout(0, 0, w, h)
        beginPinch(view, 200f)
        pinch(view, 220f)
        val before = view.saveViewportState()!!.getFloat("zoom")
        pinch(view, 240f)
        assertEquals(before * 240f / 220f, view.saveViewportState()!!.getFloat("zoom"), .00001f)
        view.releaseInput()
    }

    @Test
    fun recreatedViewerRestoresViewportAndPointerWhenDesktopSizeArrives() {
        val original = screen()
        zoomAndPan(original)
        val saved = original.saveViewportState()!!
        val recreated = screen(saved)
        assertSameViewport(saved, recreated.saveViewportState()!!)
    }

    @Test
    fun fullDesktopFramesUseDesktopBoundsRegardlessOfEncodedResolution() {
        val view = screen()
        zoomAndPan(view)
        val before = view.saveViewportState()!!
        assertEquals(Rect(0, 0, 4000, 3200), view.fullDesktopRect(1000, 800))
        view.setDesktopSize(4000, 3200)
        assertSameViewport(before, view.saveViewportState()!!)
    }
}
