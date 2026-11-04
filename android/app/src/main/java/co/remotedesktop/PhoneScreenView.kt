package co.remotedesktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * The latest picture of another phone, fitted with black bars, and the
 * fingers on it turned into input for that phone by [PhoneTouchInput].
 */
class PhoneScreenView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Called on the main thread for each thing to send. */
    internal var onInput: (PhoneTouchInput.Out) -> Unit = {}

    private val input = PhoneTouchInput(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var frame: Bitmap? = null
    private val picture = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tick = Runnable { emit(input.tick(SystemClock.uptimeMillis())) }

    val hasFrame: Boolean get() = frame != null

    /** Shows a newer picture. Its own size sets the shape, so a turned screen just works. */
    fun setFrame(bitmap: Bitmap) {
        val reshaped = frame?.let { it.width != bitmap.width || it.height != bitmap.height } ?: true
        frame = bitmap
        if (reshaped) placePicture()
        invalidate()
    }

    /** Lifts a finger left down on the other phone, e.g. as the viewer pauses. */
    fun liftFinger() = emit(input.cancel())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = placePicture()

    private fun placePicture() {
        val f = frame
        if (f == null || width == 0 || height == 0 || f.width == 0 || f.height == 0) {
            picture.setEmpty()
        } else {
            val scale = minOf(width / f.width.toFloat(), height / f.height.toFloat())
            val w = f.width * scale
            val h = f.height * scale
            picture.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }
        input.setPicture(picture.left, picture.top, picture.width(), picture.height())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        frame?.let { canvas.drawBitmap(it, null, picture, paint) }
    }

    // Touches are the other phone's input; there is no click to perform here.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val time = event.eventTime
        val i = event.actionIndex
        emit(when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> input.down(event.getPointerId(0), event.x, event.y, time)
            MotionEvent.ACTION_POINTER_DOWN -> input.pointerDown(event.getPointerId(i), event.getX(i), event.getY(i), time)
            MotionEvent.ACTION_MOVE -> (0 until event.pointerCount).flatMap {
                input.move(event.getPointerId(it), event.getX(it), event.getY(it), time)
            }
            MotionEvent.ACTION_POINTER_UP -> input.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i), time)
            MotionEvent.ACTION_UP -> input.up(event.getPointerId(i), event.getX(i), event.getY(i), time)
            MotionEvent.ACTION_CANCEL -> input.cancel()
            else -> emptyList()
        })
        return true
    }

    private fun emit(out: List<PhoneTouchInput.Out>) {
        out.forEach { onInput(it) }
        removeCallbacks(tick)
        input.nextTick()?.let { postDelayed(tick, (it - SystemClock.uptimeMillis()).coerceAtLeast(0)) }
    }
}
