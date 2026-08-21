package co.joypilot.remotedesktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Renders the streamed desktop image (all monitors already stitched into one
 * bitmap by the host) and drives a **virtual mouse pointer** that is always
 * visible, touchpad-style — like the Microsoft RD client's "mouse pointer"
 * mode:
 *
 *  - 1-finger drag                       -> move the pointer
 *  - 1-finger tap                        -> left click (at the pointer)
 *  - press & hold, then drag             -> left-click drag
 *  - 2-finger tap                        -> right click (at the pointer)
 *  - 2-finger drag                       -> pan the local viewport
 *  - 2-finger pinch                      -> zoom the local viewport
 *  - 3-finger drag (throttle)            -> mouse wheel: the scroll speed tracks
 *                                           how far the fingers are held from
 *                                           where they first landed; return them
 *                                           to the start to stop
 *
 * Two-finger pan and pinch are combined into one gesture. The viewport also
 * auto-pans to keep the pointer visible while zoomed in.
 */
class RemoteScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Normalized (0..1) coordinates over the stitched desktop.
    var onMouseMove: ((Double, Double) -> Unit)? = null
    var onMouseButton: ((button: String, action: String, Double, Double) -> Unit)? = null
    var onWheel: ((Double, Double) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var imgW = 0
    private var imgH = 0

    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var fitScale = 1f
    private var matrixInitialized = false
    private var bottomOcclusionPx = 0

    // Debug overlay populated by ViewerActivity from the patch metadata. It is
    // deliberately drawn here (after the desktop bitmap) so the rectangles stay
    // aligned while the user pans or zooms.
    private data class DirtyHighlight(val rect: RectF, val expiresAt: Long)
    private val dirtyHighlights = ArrayList<DirtyHighlight>()
    private var dirtyRectHighlightsEnabled = false
    private val dirtyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 255, 0, 0)
        style = Paint.Style.FILL
    }
    private val dirtyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density

    // ---------------- frame / viewport ----------------

    fun setFrame(bmp: Bitmap, dirtyRects: List<Rect>? = null) {
        val sizeChanged = bmp.width != imgW || bmp.height != imgH
        bitmap = bmp
        imgW = bmp.width
        imgH = bmp.height
        if (cursorX < 0) {
            cursorX = imgW / 2f
            cursorY = imgH / 2f
        }
        if (sizeChanged || !matrixInitialized) {
            post {
                resetToFit()
                dirtyRects?.let {
                    addDirtyHighlights(it)
                    invalidateImageRegions(it)
                }
            }
        } else if (dirtyRects != null) {
            // Preserve image-space damage for Android 8's partial invalidation
            // and for the optional debug overlay. Newer Android versions may
            // promote partial damage to a whole-View redraw internally.
            post {
                addDirtyHighlights(dirtyRects)
                invalidateImageRegions(dirtyRects)
            }
        } else {
            postInvalidate()
        }
    }

    /** Pixels at the bottom currently covered by a custom keyboard or IME. */
    fun setBottomOcclusion(bottomPx: Int) {
        val next = bottomPx.coerceIn(0, height.coerceAtLeast(0))
        if (next == bottomOcclusionPx) return
        bottomOcclusionPx = next
        if (matrixInitialized) {
            clampTranslation()
            keepPointerVisible()
        }
        invalidate()
    }

    fun setDirtyRectHighlightsEnabled(enabled: Boolean) {
        dirtyRectHighlightsEnabled = enabled
        if (!enabled) dirtyHighlights.clear()
        invalidate()
    }

    private fun resetToFit() {
        if (imgW == 0 || imgH == 0 || width == 0 || height == 0) return
        fitScale = min(width.toFloat() / imgW, height.toFloat() / imgH)
        matrix.reset()
        val dx = (width - imgW * fitScale) / 2f
        val dy = (height - imgH * fitScale) / 2f
        matrix.postScale(fitScale, fitScale)
        matrix.postTranslate(dx, dy)
        matrixInitialized = true
        clampTranslation()
        keepPointerVisible()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        matrixInitialized = false
        resetToFit()
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    // How far past a view edge the image may be dragged, so an edge (and the
    // corners) can be pulled out from under the on-screen button overlay.
    private val overscroll = 64f * density

    private fun clampTranslation() {
        val v = FloatArray(9)
        matrix.getValues(v)
        val scale = v[Matrix.MSCALE_X]
        v[Matrix.MTRANS_X] = clampAxis(v[Matrix.MTRANS_X], imgW * scale, width.toFloat())
        v[Matrix.MTRANS_Y] = clampAxis(v[Matrix.MTRANS_Y], imgH * scale, visibleHeight())
        matrix.setValues(v)
    }

    private fun visibleHeight(): Float =
        (height - bottomOcclusionPx).toFloat().coerceAtLeast(pointerMargin * 2f + 1f)

    /**
     * Clamp one axis of the image translation to a band that is *continuous* in
     * the displayed size [disp]. When the image is larger than the view it may
     * overscroll by at most [overscroll] px past each edge; as it shrinks, the
     * band closes smoothly onto the centred position. The previous clamp forced
     * an exact centre the instant a dimension fit while allowing a big overscroll
     * just above that size, so zooming out across the fit size snapped the image
     * (a visible jump) and left panning stuck once it had centred.
     */
    private fun clampAxis(t: Float, disp: Float, view: Float): Float {
        val center = (view - disp) / 2f
        val maxT = maxOf(center, overscroll)
        val minT = minOf(center, view - disp - overscroll)
        return t.coerceIn(minT, maxT)
    }

    // ---------------- virtual pointer ----------------

    // Pointer position in image pixels; -1 until the first frame arrives.
    private var cursorX = -1f
    private var cursorY = -1f
    private var lastMoveSentAt = 0L

    private fun cursorNormX() = (cursorX / imgW).toDouble().coerceIn(0.0, 1.0)
    private fun cursorNormY() = (cursorY / imgH).toDouble().coerceIn(0.0, 1.0)

    /** Move the pointer by a finger delta given in view pixels. */
    private fun movePointerBy(dxView: Float, dyView: Float) {
        if (imgW == 0 || imgH == 0) return
        val scale = currentScale()
        cursorX = (cursorX + dxView / scale).coerceIn(0f, imgW.toFloat())
        cursorY = (cursorY + dyView / scale).coerceIn(0f, imgH.toFloat())
        keepPointerVisible()
        sendPointer()
        invalidate()
    }

    private fun sendPointer(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastMoveSentAt < 33) return
        lastMoveSentAt = now
        onMouseMove?.invoke(cursorNormX(), cursorNormY())
    }

    // The pointer is kept at least this far (screen px) from every viewport edge.
    private val pointerMargin = 24f * density

    /**
     * How far the pointer, mapped to the screen, currently sits *past* the safe
     * margin at each edge, in screen pixels. Zero on an axis means it's inside.
     */
    private fun pointerEdgeOverflow(): Pair<Float, Float> {
        val pts = floatArrayOf(cursorX, cursorY)
        matrix.mapPoints(pts)
        var dx = 0f
        var dy = 0f
        if (pts[0] < pointerMargin) dx = pointerMargin - pts[0]
        if (pts[0] > width - pointerMargin) dx = width - pointerMargin - pts[0]
        if (pts[1] < pointerMargin) dy = pointerMargin - pts[1]
        val safeBottom = visibleHeight() - pointerMargin
        if (pts[1] > safeBottom) dy = safeBottom - pts[1]
        return Pair(dx, dy)
    }

    /** One-finger move: auto-pan the viewport so the pointer never leaves the edge. */
    private fun keepPointerVisible() {
        val (dx, dy) = pointerEdgeOverflow()
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
            clampTranslation()
        }
    }

    /** Two-finger pan: drag the pointer along so it doesn't get panned off-screen. */
    private fun keepPointerInView() {
        val (dx, dy) = pointerEdgeOverflow()
        if (dx != 0f || dy != 0f) {
            val scale = currentScale()
            cursorX = (cursorX + dx / scale).coerceIn(0f, imgW.toFloat())
            cursorY = (cursorY + dy / scale).coerceIn(0f, imgH.toFloat())
            sendPointer()
        }
    }

    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val cursorOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val cursorPath = Path()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        bitmap?.let { canvas.drawBitmap(it, matrix, paint) }
        drawDirtyHighlights(canvas)
        drawCursor(canvas)
    }

    private fun addDirtyHighlights(rects: List<Rect>) {
        if (!dirtyRectHighlightsEnabled) return
        val expires = SystemClock.uptimeMillis() + DIRTY_HIGHLIGHT_MS
        rects.forEach { dirtyHighlights.add(DirtyHighlight(RectF(it), expires)) }
        // Redraw once after expiry so an otherwise-idle desktop does not leave
        // the final red outline stuck on screen.
        postInvalidateDelayed(DIRTY_HIGHLIGHT_MS + 16L)
    }

    private fun drawDirtyHighlights(canvas: Canvas) {
        if (!dirtyRectHighlightsEnabled || dirtyHighlights.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        dirtyHighlights.removeAll { it.expiresAt <= now }
        for (highlight in dirtyHighlights) {
            val mapped = RectF(highlight.rect)
            matrix.mapRect(mapped)
            canvas.drawRect(mapped, dirtyFill)
            canvas.drawRect(mapped, dirtyStroke)
        }
    }

    @Suppress("DEPRECATION") // Partial View damage still helps on Android 8/8.1.
    private fun invalidateImageRegions(rects: List<Rect>) {
        if (rects.isEmpty()) return
        val damage = RectF()
        var haveDamage = false
        for (rect in rects) {
            val mapped = RectF(rect)
            matrix.mapRect(mapped)
            if (haveDamage) damage.union(mapped) else {
                damage.set(mapped)
                haveDamage = true
            }
        }
        if (!haveDamage) return
        val pad = if (dirtyRectHighlightsEnabled) dirtyStroke.strokeWidth + 2f else 1f
        invalidate(
            (damage.left - pad).toInt(),
            (damage.top - pad).toInt(),
            (damage.right + pad).toInt(),
            (damage.bottom + pad).toInt()
        )
    }

    private fun drawCursor(canvas: Canvas) {
        if (cursorX < 0 || imgW == 0) return
        val pts = floatArrayOf(cursorX, cursorY)
        matrix.mapPoints(pts)
        val s = density * 1.5f // arrow size, independent of zoom
        cursorPath.reset()
        cursorPath.moveTo(pts[0], pts[1])
        cursorPath.lineTo(pts[0], pts[1] + 14f * s)
        cursorPath.lineTo(pts[0] + 3.2f * s, pts[1] + 10.8f * s)
        cursorPath.lineTo(pts[0] + 5.8f * s, pts[1] + 16.2f * s)
        cursorPath.lineTo(pts[0] + 7.8f * s, pts[1] + 15.2f * s)
        cursorPath.lineTo(pts[0] + 5.2f * s, pts[1] + 9.8f * s)
        cursorPath.lineTo(pts[0] + 9.4f * s, pts[1] + 9.8f * s)
        cursorPath.close()
        canvas.drawPath(cursorPath, cursorFill)
        canvas.drawPath(cursorPath, cursorOutline)
    }

    // ---------------- gesture handling ----------------

    private enum class State { NONE, POINTER1, POINTER2, DRAG_LEFT, PAN_ZOOM, WHEEL }

    private var state = State.NONE
    private var downTime = 0L
    private var twoDownTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var prevDist = 0f
    private var prevFocusX = 0f
    private var prevFocusY = 0f
    private var downDist = 0f
    private var downFocusX = 0f
    private var downFocusY = 0f

    // Three-finger wheel throttle: origin = finger focus when the 3rd finger
    // landed, current = the latest focus. wheelTicker scrolls based on the
    // standing displacement (current - origin) until the fingers return home.
    private var wheelOriginX = 0f
    private var wheelOriginY = 0f
    private var wheelCurrentX = 0f
    private var wheelCurrentY = 0f
    private val wheelDeadzone = touchSlop.toFloat()

    private val holdRunnable = Runnable {
        // Press and hold without moving starts a left-button drag: the button
        // goes down now and stays down until the finger lifts, so drag-and-drop
        // and long-press-and-drag targets work. Because there is no preceding
        // tap (unlike a double-tap), the host sees a clean down…move…up drag
        // rather than a double-click followed by a drag.
        if (state == State.POINTER1 && !moved) startDrag(State.DRAG_LEFT)
    }

    // Repeats while three fingers are down, turning the standing finger
    // displacement into a continuous scroll (touch events stop firing once the
    // fingers hold still, so the timer is what keeps it going). Self-stops if
    // the gesture has ended, in addition to being cancelled on finger-up.
    private val wheelTicker = object : Runnable {
        override fun run() {
            if (state != State.WHEEL) return
            val dx = wheelNotches(wheelCurrentX - wheelOriginX)
            val dy = wheelNotches(wheelCurrentY - wheelOriginY)
            // Fingers held below their start (positive dispY) scroll the content
            // down, which is a negative wheel delta (positive dy scrolls up).
            if (dx != 0.0 || dy != 0.0) onWheel?.invoke(dx, -dy)
            postDelayed(this, WHEEL_TICK_MS)
        }
    }

    /**
     * Wheel notches to emit this tick for a finger displacement of [dispPx] from
     * the gesture origin. Inside the deadzone it's zero (so bringing the fingers
     * back to where they started stops the scroll); past it the rate ramps
     * linearly with distance.
     */
    private fun wheelNotches(dispPx: Float): Double {
        val mag = abs(dispPx)
        if (mag <= wheelDeadzone) return 0.0
        val perSec = ((mag - wheelDeadzone) / WHEEL_PX_PER_NOTCH) * WHEEL_SPEED
        val notches = perSec * (WHEEL_TICK_MS / 1000.0)
        return if (dispPx < 0) -notches else notches
    }

    private fun twoMoved(): Boolean =
        hypot(prevFocusX - downFocusX, prevFocusY - downFocusY) > touchSlop ||
            abs(prevDist - downDist) > touchSlop

    private companion object {
        const val TAP_MS = 300L
        const val HOLD_MS = 320L
        const val WHEEL_PX_PER_NOTCH = 90.0
        // Max pinch-zoom, relative to the fit-to-screen scale (so you can zoom
        // well past 1:1 to read fine detail).
        const val MAX_ZOOM = 40f
        // Three-finger scrolling is a throttle: while the fingers are held away
        // from where they first landed, wheel notches are emitted on a timer at
        // a rate proportional to that displacement. WHEEL_SPEED is the rate (in
        // notches per second) reached when the fingers sit one notch-worth of
        // distance (WHEEL_PX_PER_NOTCH) past the neutral deadzone.
        const val WHEEL_TICK_MS = 40L
        const val WHEEL_SPEED = 10.0
        const val DIRTY_HIGHLIGHT_MS = 350L
    }

    private fun startDrag(dragState: State) {
        state = dragState
        sendPointer(force = true)
        onMouseButton?.invoke("left", "down", cursorNormX(), cursorNormY())
    }

    private fun endDrag() {
        sendPointer(force = true)
        onMouseButton?.invoke("left", "up", cursorNormX(), cursorNormY())
        state = State.NONE
    }

    private fun click(button: String) {
        sendPointer(force = true)
        onMouseButton?.invoke(button, "down", cursorNormX(), cursorNormY())
        onMouseButton?.invoke(button, "up", cursorNormX(), cursorNormY())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imgW == 0 || imgH == 0) return true // no frame yet, nothing to control
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                moved = false
                state = State.POINTER1
                // Arm the press-and-hold timer: if the finger stays put for
                // HOLD_MS it becomes a left-button drag; if it moves first it's
                // a pointer move, and if it lifts first it's a tap (left click).
                postDelayed(holdRunnable, HOLD_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(holdRunnable)
                removeCallbacks(wheelTicker)
                when (event.pointerCount) {
                    2 -> {
                        if (state == State.DRAG_LEFT) endDrag()
                        state = State.POINTER2
                        twoDownTime = System.currentTimeMillis()
                        downDist = spacing(event); prevDist = downDist
                        downFocusX = avgX(event, 2); downFocusY = avgY(event, 2)
                        prevFocusX = downFocusX; prevFocusY = downFocusY
                    }
                    3 -> {
                        // A third finger switches to mouse-wheel scrolling. Record
                        // where the fingers landed; the ticker then scrolls based
                        // on how far they're held from here.
                        state = State.WHEEL
                        wheelOriginX = avgX(event, 3); wheelOriginY = avgY(event, 3)
                        wheelCurrentX = wheelOriginX; wheelCurrentY = wheelOriginY
                        postDelayed(wheelTicker, WHEEL_TICK_MS)
                    }
                    else -> return true // ignore 4+ fingers
                }
            }

            MotionEvent.ACTION_MOVE -> when (state) {
                State.POINTER1, State.DRAG_LEFT -> handleOneFingerMove(event)
                State.POINTER2, State.PAN_ZOOM -> handleTwoFingerMove(event)
                State.WHEEL -> handleThreeFingerMove(event)
                else -> {}
            }

            MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(holdRunnable)
                removeCallbacks(wheelTicker)
                val now = System.currentTimeMillis()
                if (state == State.POINTER2 && now - twoDownTime < TAP_MS && !twoMoved()) {
                    click("right")
                }
                if (event.pointerCount - 1 >= 2) {
                    // Dropped from 3+ fingers down to 2 — resume pan/zoom and
                    // rebuild the baseline from the two surviving fingers so the
                    // viewport doesn't jump.
                    val (i0, i1) = remainingTwoIndices(event)
                    downDist = spacing(event, i0, i1); prevDist = downDist
                    downFocusX = (event.getX(i0) + event.getX(i1)) / 2f
                    downFocusY = (event.getY(i0) + event.getY(i1)) / 2f
                    prevFocusX = downFocusX; prevFocusY = downFocusY
                    twoDownTime = 0L // can't be mistaken for a two-finger tap
                    state = State.PAN_ZOOM
                } else {
                    // Down to one finger — continue as plain pointer movement.
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining); lastY = event.getY(remaining)
                    state = State.POINTER1
                    moved = true // suppress an accidental tap on final release
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                removeCallbacks(wheelTicker)
                val now = System.currentTimeMillis()
                when (state) {
                    State.DRAG_LEFT -> endDrag()
                    State.POINTER1 -> {
                        if (now - downTime < TAP_MS && !moved) click("left")
                    }
                    else -> {}
                }
                state = State.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                removeCallbacks(wheelTicker)
                if (state == State.DRAG_LEFT) endDrag()
                state = State.NONE
            }
        }
        return true
    }

    private fun handleOneFingerMove(event: MotionEvent) {
        val dx = event.x - lastX
        val dy = event.y - lastY
        if (!moved && hypot(event.x - downX, event.y - downY) > touchSlop) {
            moved = true
            // Moving before the hold fired means this is a pointer move, not a
            // drag — cancel the pending hold. (If the hold already started a
            // drag, state is DRAG_LEFT and these moves extend it below.)
            removeCallbacks(holdRunnable)
        }
        if (!moved) return
        movePointerBy(dx, dy)
        lastX = event.x
        lastY = event.y
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val dist = spacing(event)
        val focusX = avgX(event, 2)
        val focusY = avgY(event, 2)
        val dFocusX = focusX - prevFocusX
        val dFocusY = focusY - prevFocusY

        when (state) {
            State.POINTER2 -> {
                // Any pinch or drag past slop turns this into a pan+zoom gesture.
                if (abs(dist - downDist) > touchSlop * 2 ||
                    hypot(focusX - downFocusX, focusY - downFocusY) > touchSlop
                ) {
                    state = State.PAN_ZOOM
                }
            }
            State.PAN_ZOOM -> {
                // Pinch to zoom about the focus point…
                val dScale = if (prevDist > 0) dist / prevDist else 1f
                val cur = currentScale()
                val target = (cur * dScale).coerceIn(fitScale, fitScale * MAX_ZOOM)
                val applied = target / cur
                matrix.postScale(applied, applied, focusX, focusY)
                // …and pan by however the two fingers moved together.
                matrix.postTranslate(dFocusX, dFocusY)
                clampTranslation()
                // If the pan pushed the pointer to the edge, drag it along.
                keepPointerInView()
                invalidate()
            }
            else -> {}
        }

        prevDist = dist
        prevFocusX = focusX
        prevFocusY = focusY
    }

    private fun handleThreeFingerMove(event: MotionEvent) {
        if (event.pointerCount < 3) return
        // Just track where the fingers are now; wheelTicker turns the standing
        // displacement from the origin into a continuous scroll.
        wheelCurrentX = avgX(event, 3)
        wheelCurrentY = avgY(event, 3)
    }

    /** Average X of the first [n] pointers (clamped to the available count). */
    private fun avgX(event: MotionEvent, n: Int): Float {
        val c = min(n, event.pointerCount)
        if (c == 0) return 0f
        var sum = 0f
        for (i in 0 until c) sum += event.getX(i)
        return sum / c
    }

    private fun avgY(event: MotionEvent, n: Int): Float {
        val c = min(n, event.pointerCount)
        if (c == 0) return 0f
        var sum = 0f
        for (i in 0 until c) sum += event.getY(i)
        return sum / c
    }

    /** The two pointer indices that remain after the ACTION_POINTER_UP finger lifts. */
    private fun remainingTwoIndices(event: MotionEvent): Pair<Int, Int> {
        val up = event.actionIndex
        val kept = (0 until event.pointerCount).filter { it != up }
        return Pair(kept[0], kept[1])
    }

    private fun spacing(event: MotionEvent, i0: Int, i1: Int): Float =
        hypot(event.getX(i0) - event.getX(i1), event.getY(i0) - event.getY(i1))

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }
}
