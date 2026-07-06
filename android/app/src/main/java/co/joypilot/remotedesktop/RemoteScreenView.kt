package co.joypilot.remotedesktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the streamed desktop image (all monitors already stitched into one
 * bitmap by the host) and translates touch gestures into remote mouse actions.
 *
 * Gestures:
 *  - 1-finger tap        -> left click at that point
 *  - 1-finger long-press -> right click at that point
 *  - 1-finger drag       -> pan the zoomed view (or drag with left button when [dragMode])
 *  - 2-finger pinch      -> zoom in / out about the pinch center
 *  - 2-finger drag       -> mouse wheel scroll
 */
class RemoteScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Normalized (0..1) coordinates over the stitched desktop.
    var onLeftClick: ((Double, Double) -> Unit)? = null
    var onRightClick: ((Double, Double) -> Unit)? = null
    var onWheel: ((Double, Double) -> Unit)? = null
    var onLeftDown: ((Double, Double) -> Unit)? = null
    var onMove: ((Double, Double) -> Unit)? = null
    var onLeftUp: ((Double, Double) -> Unit)? = null

    /** When true, a 1-finger drag becomes a left-button drag instead of a pan. */
    var dragMode = false

    private var bitmap: Bitmap? = null
    private var imgW = 0
    private var imgH = 0

    private val matrix = Matrix()
    private val inverse = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var fitScale = 1f
    private var matrixInitialized = false

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    fun setFrame(bmp: Bitmap) {
        val sizeChanged = bmp.width != imgW || bmp.height != imgH
        bitmap = bmp
        imgW = bmp.width
        imgH = bmp.height
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

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        bitmap?.let { canvas.drawBitmap(it, matrix, paint) }
    }

    /** Screen pixel -> normalized image coord (0..1), clamped. */
    private fun toNormalized(px: Float, py: Float): Pair<Double, Double>? {
        if (imgW == 0 || imgH == 0) return null
        matrix.invert(inverse)
        val pts = floatArrayOf(px, py)
        inverse.mapPoints(pts)
        val nx = (pts[0] / imgW).toDouble().coerceIn(0.0, 1.0)
        val ny = (pts[1] / imgH).toDouble().coerceIn(0.0, 1.0)
        return Pair(nx, ny)
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

    // ---------------- gesture handling ----------------

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            toNormalized(e.x, e.y)?.let { onLeftClick?.invoke(it.first, it.second) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            toNormalized(e.x, e.y)?.let { onRightClick?.invoke(it.first, it.second) }
        }
    })

    private var mode = Mode.NONE
    private enum class Mode { NONE, ONE_FINGER, TWO_FINGER, DRAGGING }

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var prevDist = 0f
    private var prevFocusX = 0f
    private var prevFocusY = 0f
    private var movedBeyondSlop = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let the detector see single-finger events for tap / long-press.
        if (event.pointerCount == 1 && mode != Mode.TWO_FINGER) gesture.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.ONE_FINGER
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                movedBeyondSlop = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = Mode.TWO_FINGER
                prevDist = spacing(event)
                prevFocusX = (event.getX(0) + event.getX(1)) / 2f
                prevFocusY = (event.getY(0) + event.getY(1)) / 2f
                // Cancel any in-progress drag started with one finger.
                if (dragMode && movedBeyondSlop) {
                    toNormalized(lastX, lastY)?.let { onLeftUp?.invoke(it.first, it.second) }
                }
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.TWO_FINGER -> handleTwoFinger(event)
                Mode.ONE_FINGER, Mode.DRAGGING -> handleOneFingerMove(event)
                else -> {}
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Dropped to one finger; reset baseline to remaining pointer.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                mode = Mode.ONE_FINGER
                movedBeyondSlop = true // suppress accidental tap after multitouch
            }

            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAGGING) {
                    toNormalized(event.x, event.y)?.let { onLeftUp?.invoke(it.first, it.second) }
                }
                mode = Mode.NONE
            }

            MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return true
    }

    private fun handleOneFingerMove(event: MotionEvent) {
        val dx = event.x - lastX
        val dy = event.y - lastY
        if (!movedBeyondSlop && hypot(event.x - downX, event.y - downY) > touchSlop) {
            movedBeyondSlop = true
            if (dragMode) {
                mode = Mode.DRAGGING
                toNormalized(downX, downY)?.let { onLeftDown?.invoke(it.first, it.second) }
            }
        }
        if (!movedBeyondSlop) return

        if (mode == Mode.DRAGGING) {
            toNormalized(event.x, event.y)?.let { onMove?.invoke(it.first, it.second) }
        } else {
            // Pan the viewport.
            matrix.postTranslate(dx, dy)
            clampTranslation()
            invalidate()
        }
        lastX = event.x; lastY = event.y
    }

    private fun handleTwoFinger(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val dist = spacing(event)
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val focusY = (event.getY(0) + event.getY(1)) / 2f

        val dScale = if (prevDist > 0) dist / prevDist else 1f
        val pinch = abs(dist - prevDist)
        val focusDy = focusY - prevFocusY
        val focusDx = focusX - prevFocusX

        if (pinch > touchSlop) {
            // Zoom about the pinch center, clamped to [fit, 8x fit].
            val cur = currentScale()
            val target = (cur * dScale).coerceIn(fitScale, fitScale * 8f)
            val applied = target / cur
            matrix.postScale(applied, applied, focusX, focusY)
            clampTranslation()
            invalidate()
        } else if (abs(focusDy) > 0.5f || abs(focusDx) > 0.5f) {
            // Two-finger drag -> mouse wheel. ~90px per notch.
            onWheel?.invoke(focusDx.toDouble() / 90.0, -focusDy.toDouble() / 90.0)
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
