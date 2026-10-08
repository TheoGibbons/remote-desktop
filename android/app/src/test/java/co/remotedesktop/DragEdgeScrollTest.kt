package co.remotedesktop

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h800dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DragEdgeScrollTest {
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun screen(): RemoteScreenView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = RemoteScreenView(activity)
        activity.setContentView(view)
        view.setDesktopSize(4000, 3200)
        idle(200)
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

    @Test
    fun heldSelectionMovesContinuouslyInEveryDirectionAndStopsInTheCentreAndOnRelease() {
        val view = screen()
        val centre = view.width / 2f to view.height / 2f
        val moves = mutableListOf<Pair<Double, Double>>()
        val buttons = mutableListOf<String>()
        val wheels = mutableListOf<Pair<Double, Double>>()
        view.onMouseMove = { x, y -> moves.add(x to y) }
        view.onMouseButton = { _, action, _, _ -> buttons.add(action) }
        view.onWheel = { x, y -> wheels.add(x to y) }
        for ((point, direction) in listOf(
            (5f to centre.second) to (-1 to 0),
            (view.width - 5f to centre.second) to (1 to 0),
            (centre.first to 5f) to (0 to -1),
            (centre.first to view.height - 5f) to (0 to 1),
        )) {
            moves.clear()
            touch(view, MotionEvent.ACTION_DOWN, point)
            idle(480)
            assertTrue("Pointer continues moving without further touch events", moves.size >= 4)
            val dx = moves.last().first - moves.first().first
            val dy = moves.last().second - moves.first().second
            assertEquals(direction.first, Math.signum(dx).toInt())
            assertEquals(direction.second, Math.signum(dy).toInt())
            touch(view, MotionEvent.ACTION_MOVE, centre)
            moves.clear()
            idle(160)
            assertTrue(moves.isEmpty())
            touch(view, MotionEvent.ACTION_UP, centre)
            moves.clear()
            idle(200)
            assertTrue(moves.isEmpty())
        }
        assertEquals(List(4) { listOf("down", "up") }.flatten(), buttons)
        assertTrue("Selection dragging does not inject document wheel events", wheels.isEmpty())
    }

    @Test
    fun zoomedViewportFollowsTheHeldPointerAndReportsNewRegionsDuringTheDrag() {
        val view = screen()
        val cx = view.width / 2f
        val cy = view.height / 2f
        touch(view, MotionEvent.ACTION_DOWN, cx - 100f to cy)
        touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            cx - 100f to cy, cx + 100f to cy)
        for (distance in listOf(200f, 300f, 400f)) {
            touch(view, MotionEvent.ACTION_MOVE, cx - distance to cy, cx + distance to cy)
        }
        touch(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            cx - 400f to cy, cx + 400f to cy)
        touch(view, MotionEvent.ACTION_UP, cx - 400f to cy)
        val regions = mutableListOf<Double>()
        view.onViewportChanged = { x, _, _, _, _, _ -> regions.add(x) }
        view.resendViewport()
        idle(200)
        val initialRegion = regions.last()
        regions.clear()
        touch(view, MotionEvent.ACTION_DOWN, view.width - 5f to cy)
        idle(4500)
        assertTrue("The view pans during a held selection", regions.any { it > initialRegion + .05 })
        assertTrue("Viewport requests are not delayed until finger-up", regions.size >= 3)
        view.releaseInput()
    }

    @Test
    fun occludedEdgesAndCancellationDoNotLeaveTimersOrMouseButtonsHeld() {
        val view = screen()
        view.setTopOcclusion(36)
        view.setBottomOcclusion(300)
        val moves = mutableListOf<Pair<Double, Double>>()
        val buttons = mutableListOf<String>()
        view.onMouseMove = { x, y -> moves.add(x to y) }
        view.onMouseButton = { _, action, _, _ -> buttons.add(action) }
        val bottom = view.width / 2f to view.height - 310f
        for (cancel in listOf(false, true)) {
            touch(view, MotionEvent.ACTION_DOWN, bottom)
            idle(480)
            assertTrue(moves.last().second > moves.first().second)
            if (cancel) touch(view, MotionEvent.ACTION_CANCEL, bottom) else view.releaseInput()
            moves.clear()
            idle(200)
            assertTrue(moves.isEmpty())
        }
        assertEquals(listOf("down", "up", "down", "up"), buttons)
        touch(view, MotionEvent.ACTION_DOWN, view.width / 2f to 45f)
        idle(480)
        assertTrue(moves.last().second < moves.first().second)
        // Another finger releases the selection before switching to pan/zoom.
        touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            view.width / 2f to 45f, view.width / 2f + 100f to 45f)
        assertEquals("up", buttons.last())
        moves.clear()
        idle(200)
        assertTrue(moves.isEmpty())
        view.releaseInput()
    }
}
