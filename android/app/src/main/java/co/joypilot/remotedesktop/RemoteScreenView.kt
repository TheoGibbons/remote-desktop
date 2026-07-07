package co.joypilot.remotedesktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
 *  - double-tap & hold, then drag        -> left-click drag
 *  - 2-finger tap                        -> right click (at the pointer)
 *  - 2-finger drag                       -> pan the local viewport
 *  - 2-finger pinch                      -> zoom the local viewport
 *  - 3-finger drag up/down               -> mouse wheel
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

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density

    // ---------------- frame / viewport ----------------

    fun setFrame(bmp: Bitmap) {
        val sizeChanged = bmp.width != imgW || bmp.height != imgH
        bitmap = bmp
        imgW = bmp.width
        imgH = bmp.height
        if (cursorX < 0) {
            cursorX = imgW / 2f
            cursorY = imgH / 2f
        }
        if (sizeChanged || !matrixInitialized) {
            post { resetToFit() }
        }
        postInvalidate()
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

    private fun clampTranslation() {
        val v = FloatArray(9)
        matrix.getValues(v)
        val scale = v[Matrix.MSCALE_X]
        val dispW = imgW * scale
        val dispH = imgH * scale
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]

        // When zoomed in, allow half a viewport of overscroll past each edge:
        // any edge of the desktop can be dragged all the way to the centre of the
        // view. That lets the user pull the top/corners out from under the
        // on-screen button overlay. (When the desktop fits, keep it centred.)
        tx = if (dispW <= width) (width - dispW) / 2f
        else tx.coerceIn(width / 2f - dispW, width / 2f)
        ty = if (dispH <= height) (height - dispH) / 2f
        else ty.coerceIn(height / 2f - dispH, height / 2f)

        matrix.setValues(v.also { it[Matrix.MTRANS_X] = tx; it[Matrix.MTRANS_Y] = ty })
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
        if (pts[1] > height - pointerMargin) dy = height - pointerMargin - pts[1]
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
        drawCursor(canvas)
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

    // Double-tap bookkeeping for the left-click drag gesture.
    private var lastTap1At = 0L
    private var leftDragCandidate = false

    private val holdRunnable = Runnable {
        // Double-tap & hold without moving: start the drag even before the
        // finger moves, so drag-and-hold targets (long-press UIs) work too.
        if (state == State.POINTER1 && leftDragCandidate && !moved) startDrag(State.DRAG_LEFT)
    }

    private fun twoMoved(): Boolean =
        hypot(prevFocusX - downFocusX, prevFocusY - downFocusY) > touchSlop ||
            abs(prevDist - downDist) > touchSlop

    private companion object {
        const val TAP_MS = 300L
        const val DOUBLE_TAP_MS = 350L
        const val HOLD_MS = 320L
        const val WHEEL_PX_PER_NOTCH = 90.0
        // Max pinch-zoom, relative to the fit-to-screen scale (so you can zoom
        // well past 1:1 to read fine detail).
        const val MAX_ZOOM = 40f
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
                leftDragCandidate = downTime - lastTap1At < DOUBLE_TAP_MS
                if (leftDragCandidate) postDelayed(holdRunnable, HOLD_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(holdRunnable)
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
                        // A third finger switches to mouse-wheel scrolling.
                        state = State.WHEEL
                        prevFocusX = avgX(event, 3); prevFocusY = avgY(event, 3)
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
                val now = System.currentTimeMillis()
                when (state) {
                    State.DRAG_LEFT -> endDrag()
                    State.POINTER1 -> {
                        if (now - downTime < TAP_MS && !moved) {
                            click("left")
                            lastTap1At = now
                        }
                    }
                    else -> {}
                }
                state = State.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
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
            removeCallbacks(holdRunnable)
            // Double-tap & drag: hold the button down from the start point.
            if (leftDragCandidate && state == State.POINTER1) startDrag(State.DRAG_LEFT)
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
        val focusX = avgX(event, 3)
        val focusY = avgY(event, 3)
        onWheel?.invoke(
            (focusX - prevFocusX) / WHEEL_PX_PER_NOTCH,
            -(focusY - prevFocusY) / WHEEL_PX_PER_NOTCH
        )
        prevFocusX = focusX
        prevFocusY = focusY
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
