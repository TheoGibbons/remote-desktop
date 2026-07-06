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
 *  - 2-finger double-tap & hold, drag    -> right-click drag
 *  - 2-finger hold & drag up/down        -> mouse wheel
 *  - 2-finger pinch                      -> zoom the local viewport
 *
 * The viewport auto-pans to keep the pointer visible while zoomed in.
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

        tx = if (dispW <= width) (width - dispW) / 2f
        else tx.coerceIn(width - dispW, 0f)
        ty = if (dispH <= height) (height - dispH) / 2f
        else ty.coerceIn(height - dispH, 0f)

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

    /** Auto-pan the viewport so the pointer never leaves the visible area. */
    private fun keepPointerVisible() {
        val pts = floatArrayOf(cursorX, cursorY)
        matrix.mapPoints(pts)
        val margin = 48f * density / 2f
        var dx = 0f
        var dy = 0f
        if (pts[0] < margin) dx = margin - pts[0]
        if (pts[0] > width - margin) dx = width - margin - pts[0]
        if (pts[1] < margin) dy = margin - pts[1]
        if (pts[1] > height - margin) dy = height - margin - pts[1]
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
            clampTranslation()
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

    private enum class State { NONE, POINTER1, POINTER2, DRAG_LEFT, DRAG_RIGHT, WHEEL, ZOOM }

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

    // Double-tap bookkeeping for drag gestures.
    private var lastTap1At = 0L
    private var lastTap2At = 0L
    private var leftDragCandidate = false
    private var rightDragCandidate = false

    private val holdRunnable = Runnable {
        // Double-tap & hold without moving: start the drag even before the
        // finger moves, so drag-and-hold targets (long-press UIs) work too.
        if (state == State.POINTER1 && leftDragCandidate && !moved) startDrag(State.DRAG_LEFT)
        else if (state == State.POINTER2 && rightDragCandidate && !twoMoved()) startDrag(State.DRAG_RIGHT)
    }

    private fun twoMoved(): Boolean =
        hypot(prevFocusX - downFocusX, prevFocusY - downFocusY) > touchSlop ||
            abs(prevDist - downDist) > touchSlop

    private companion object {
        const val TAP_MS = 300L
        const val DOUBLE_TAP_MS = 350L
        const val HOLD_MS = 320L
        const val WHEEL_PX_PER_NOTCH = 90.0
    }

    private fun startDrag(dragState: State) {
        state = dragState
        val button = if (dragState == State.DRAG_LEFT) "left" else "right"
        sendPointer(force = true)
        onMouseButton?.invoke(button, "down", cursorNormX(), cursorNormY())
    }

    private fun endDrag() {
        val button = if (state == State.DRAG_LEFT) "left" else "right"
        sendPointer(force = true)
        onMouseButton?.invoke(button, "up", cursorNormX(), cursorNormY())
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
                rightDragCandidate = false
                if (leftDragCandidate) postDelayed(holdRunnable, HOLD_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount != 2) return true
                removeCallbacks(holdRunnable)
                if (state == State.DRAG_LEFT) endDrag()
                state = State.POINTER2
                twoDownTime = System.currentTimeMillis()
                downDist = spacing(event); prevDist = downDist
                downFocusX = (event.getX(0) + event.getX(1)) / 2f
                downFocusY = (event.getY(0) + event.getY(1)) / 2f
                prevFocusX = downFocusX; prevFocusY = downFocusY
                rightDragCandidate = twoDownTime - lastTap2At < DOUBLE_TAP_MS
                if (rightDragCandidate) postDelayed(holdRunnable, HOLD_MS)
            }

            MotionEvent.ACTION_MOVE -> when (state) {
                State.POINTER1, State.DRAG_LEFT -> handleOneFingerMove(event)
                State.POINTER2, State.ZOOM, State.WHEEL, State.DRAG_RIGHT -> handleTwoFingerMove(event)
                else -> {}
            }

            MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(holdRunnable)
                val now = System.currentTimeMillis()
                when (state) {
                    State.POINTER2 -> {
                        if (now - twoDownTime < TAP_MS && !twoMoved()) {
                            click("right")
                            lastTap2At = now
                        }
                    }
                    State.DRAG_RIGHT -> endDrag()
                    else -> {}
                }
                // Continue with the remaining finger as pointer movement only.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                state = State.POINTER1
                moved = true // suppress an accidental tap on final release
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
                if (state == State.DRAG_LEFT || state == State.DRAG_RIGHT) endDrag()
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
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val focusY = (event.getY(0) + event.getY(1)) / 2f
        val dFocusX = focusX - prevFocusX
        val dFocusY = focusY - prevFocusY

        when (state) {
            State.POINTER2 -> {
                // Decide what this two-finger gesture is once it clears slop.
                if (abs(dist - downDist) > touchSlop * 2) {
                    removeCallbacks(holdRunnable)
                    state = State.ZOOM
                } else if (hypot(focusX - downFocusX, focusY - downFocusY) > touchSlop) {
                    removeCallbacks(holdRunnable)
                    state = if (rightDragCandidate) State.DRAG_RIGHT else State.WHEEL
                    if (state == State.DRAG_RIGHT) startDrag(State.DRAG_RIGHT)
                }
            }
            State.ZOOM -> {
                val dScale = if (prevDist > 0) dist / prevDist else 1f
                val cur = currentScale()
                val target = (cur * dScale).coerceIn(fitScale, fitScale * 8f)
                val applied = target / cur
                matrix.postScale(applied, applied, focusX, focusY)
                clampTranslation()
                invalidate()
            }
            State.WHEEL -> {
                onWheel?.invoke(dFocusX / WHEEL_PX_PER_NOTCH, -dFocusY / WHEEL_PX_PER_NOTCH)
            }
            State.DRAG_RIGHT -> {
                movePointerBy(dFocusX, dFocusY)
            }
            else -> {}
        }

        prevDist = dist
        prevFocusX = focusX
        prevFocusY = focusY
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }
}
