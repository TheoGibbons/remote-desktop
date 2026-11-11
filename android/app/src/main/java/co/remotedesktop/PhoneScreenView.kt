package co.remotedesktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * The latest picture of another phone, fitted with black bars, and the
 * fingers on it turned into input for that phone by [PhoneTouchInput].
 *
 * Android leaves a phone's unlock screen out of its picture. When the other
 * phone says where its pattern or PIN is entered, the dots or the keys are
 * drawn there, with the trail of the finger drawing a pattern. That is only
 * drawing: the touches go to the other phone exactly as they do without it.
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

    // The other phone's unlock screen, drawn: where it says the pattern or
    // PIN is entered, and the line that goes above it.
    private var unlockEntry: LockScreenReader.Entry? = null
    private var unlockHeading: String? = null
    // The drag being made over the pattern grid, as fractions of the other
    // phone's screen, and the dots it has passed over. Drawn, never sent or
    // kept: both are emptied when the finger lifts.
    private val trail = ArrayList<Pair<Float, Float>>()
    internal val trailDots = HashSet<Int>()
    internal val trailLength: Int get() = trail.size

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x73FFFFFF
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    }
    private val keyLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val trailPath = Path()
    private val keyBox = RectF()

    /** What to draw of the other phone's unlock screen: the pattern grid or
     *  the PIN's keys with [heading] above them, or nothing for null. */
    internal fun showUnlock(entry: LockScreenReader.Entry?, heading: String?) {
        val drawn = entry.takeIf { it is LockScreenReader.Entry.Pattern || it is LockScreenReader.Entry.Pin }
        if (drawn == unlockEntry && heading == unlockHeading) return
        unlockEntry = drawn
        unlockHeading = heading
        if (drawn !is LockScreenReader.Entry.Pattern) clearTrail()
        invalidate()
    }

    /** A finger is down or moving at a point of this view: remembers where,
     *  while the pattern grid is shown, so the drag can be seen. */
    private fun trailTo(x: Float, y: Float) {
        val pattern = unlockEntry as? LockScreenReader.Entry.Pattern ?: return
        if (picture.isEmpty) return
        val fx = (x - picture.left) / picture.width()
        val fy = (y - picture.top) / picture.height()
        if (fx < 0 || fx > 1 || fy < 0 || fy > 1) return
        trail.add(fx to fy)
        val dot = LockCaption.dotAt(fx.toDouble(), fy.toDouble(), pattern.area)
        if (dot >= 0) trailDots.add(dot)
        invalidate()
    }

    private fun clearTrail() {
        if (trail.isEmpty() && trailDots.isEmpty()) return
        trail.clear()
        trailDots.clear()
        invalidate()
    }

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
        if (!picture.isEmpty) unlockEntry?.let { drawUnlock(canvas, it) }
    }

    /** The dots or the keys where the other phone says they are, and the
     *  line above them. Everything is placed by the picture. */
    private fun drawUnlock(canvas: Canvas, entry: LockScreenReader.Entry) {
        fun x(fraction: Double) = picture.left + fraction.toFloat() * picture.width()
        fun y(fraction: Double) = picture.top + fraction.toFloat() * picture.height()
        val top: Float
        when (entry) {
            is LockScreenReader.Entry.Pattern -> {
                val area = entry.area
                top = y(area.y)
                val cell = area.width.toFloat() * picture.width() / 3
                if (trail.size > 1) {
                    trailPath.rewind()
                    trail.forEachIndexed { i, (fx, fy) ->
                        val px = picture.left + fx * picture.width()
                        val py = picture.top + fy * picture.height()
                        if (i == 0) trailPath.moveTo(px, py) else trailPath.lineTo(px, py)
                    }
                    faint.strokeWidth = maxOf(dp(2f), cell * 0.06f)
                    canvas.drawPath(trailPath, faint)
                }
                LockCaption.dots(area).forEachIndexed { i, (cx, cy) ->
                    val radius = maxOf(dp(3f), cell * (if (i in trailDots) 0.11f else 0.06f))
                    canvas.drawCircle(x(cx), y(cy), radius, overlay)
                }
            }
            is LockScreenReader.Entry.Pin -> {
                top = entry.keys.minOf { y(it.rect.y) }
                faint.strokeWidth = dp(1f)
                for (key in entry.keys) {
                    keyBox.set(x(key.rect.x), y(key.rect.y), x(key.rect.x + key.rect.width), y(key.rect.y + key.rect.height))
                    val small = minOf(keyBox.width(), keyBox.height())
                    canvas.drawRoundRect(keyBox, small * 0.3f, small * 0.3f, faint)
                    // The key that rubs out, as keyboards draw it.
                    val label = when (key.label) {
                        LockScreenReader.DELETE -> "\u232B"
                        LockScreenReader.OK -> "OK"
                        else -> key.label
                    }
                    keyLabel.textSize = small * (if (key.label.length == 1) 0.42f else 0.3f)
                    canvas.drawText(label, keyBox.centerX(), keyBox.centerY() - (keyLabel.ascent() + keyLabel.descent()) / 2, keyLabel)
                }
            }
            else -> return
        }
        val heading = unlockHeading ?: return
        val width = (picture.width() - dp(24f)).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(heading, 0, heading.length, words, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        canvas.save()
        canvas.translate(picture.left + (picture.width() - width) / 2, maxOf(picture.top + dp(4f), top - layout.height - dp(14f)))
        layout.draw(canvas)
        canvas.restore()
    }

    // Touches are the other phone's input; there is no click to perform here.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val time = event.eventTime
        val i = event.actionIndex
        // One finger draws a pattern; its trail is shown while it is down.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { clearTrail(); trailTo(event.x, event.y) }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) trailTo(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clearTrail()
        }
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
