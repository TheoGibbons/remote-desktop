package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Injects input sent by the paired computer. Enabled once in
 * Settings → Accessibility; survives reboots.
 *
 * Supports one-shot taps/swipes, a streamed `touch` pointer (down/move/up,
 * dispatched as continued gesture strokes so the desktop mouse can click,
 * long-press and drag in real time) and a two-finger `pinch` for zoom.
 *
 * Note: Android does not allow apps to unlock a secure lockscreen (PIN /
 * pattern / biometric). Taps work on the lockscreen surface itself, so a
 * device with no secure lock (swipe-to-unlock) can be unlocked remotely.
 */
class InputAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: InputAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        // Keeps the session connected even if the process was restarted for this
        // service without the launcher activity being recreated.
        ConnectionManager.start(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    fun handle(msg: JSONObject) {
        when (msg.optString("type")) {
            "tap" -> {
                val (w, h) = screenSize()
                tap(msg.getDouble("x") * w, msg.getDouble("y") * h)
            }
            "swipe" -> {
                val (w, h) = screenSize()
                swipe(
                    msg.getDouble("x1") * w, msg.getDouble("y1") * h,
                    msg.getDouble("x2") * w, msg.getDouble("y2") * h,
                    msg.optLong("ms", 250).coerceIn(50, 3000)
                )
            }
            "touch" -> {
                val (w, h) = screenSize()
                val x = (msg.optDouble("x", 0.0) * w).toFloat()
                val y = (msg.optDouble("y", 0.0) * h).toFloat()
                val action = msg.optString("action")
                main.post { onTouch(action, x, y) }
            }
            "pinch" -> {
                val (w, h) = screenSize()
                val cx = (msg.optDouble("x", 0.5) * w).toFloat()
                val cy = (msg.optDouble("y", 0.5) * h).toFloat()
                val zoomIn = msg.optString("dir") != "out"
                main.post { pinch(cx, cy, zoomIn) }
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "homebtn" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private fun tap(x: Double, y: Double) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 30))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, ms: Long) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ---------- streamed touch pointer (continued strokes) ----------
    // All state below is touched only on the main thread.

    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var strokeX = 0f
    private var strokeY = 0f
    private var dispatching = false
    private val pendingMoves = ArrayList<Pair<Float, Float>>()
    private var upRequested = false

    private fun onTouch(action: String, x: Float, y: Float) {
        when (action) {
            "down" -> {
                // Abandon any half-finished stroke from a lost "up".
                activeStroke = null
                pendingMoves.clear()
                upRequested = false
                strokeX = x
                strokeY = y
                val path = Path().apply { moveTo(x, y) }
                // willContinue=true keeps the finger "down" until the up arrives.
                val stroke = GestureDescription.StrokeDescription(path, 0, 40, true)
                activeStroke = stroke
                dispatchStroke(stroke)
            }
            "move" -> {
                if (activeStroke == null) return
                pendingMoves.add(Pair(x, y))
                pump()
            }
            "up" -> {
                if (activeStroke == null) return
                upRequested = true
                pump()
            }
        }
    }

    /** Dispatch the next continuation once the previous segment finished. */
    private fun pump() {
        if (dispatching) return
        val stroke = activeStroke ?: return
        if (pendingMoves.isEmpty() && !upRequested) return

        val path = Path().apply {
            moveTo(strokeX, strokeY)
            for ((px, py) in pendingMoves) lineTo(px, py)
        }
        pendingMoves.lastOrNull()?.let { strokeX = it.first; strokeY = it.second }
        val segments = pendingMoves.size
        pendingMoves.clear()

        val finishing = upRequested
        upRequested = false
        val duration = (16L * segments).coerceIn(20L, 300L)
        val next = stroke.continueStroke(path, 0, duration, !finishing)
        activeStroke = if (finishing) null else next
        dispatchStroke(next)
    }

    private fun dispatchStroke(stroke: GestureDescription.StrokeDescription) {
        dispatching = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    dispatching = false
                    pump()
                }

                override fun onCancelled(g: GestureDescription?) {
                    dispatching = false
                    activeStroke = null
                    pendingMoves.clear()
                    upRequested = false
                }
            }, main
        )
        if (!ok) {
            dispatching = false
            activeStroke = null
            pendingMoves.clear()
            upRequested = false
        }
    }

    // ---------- pinch zoom ----------

    private fun pinch(centerX: Float, centerY: Float, zoomIn: Boolean) {
        val (w, h) = screenSize()
        // Two fingers move apart (zoom in) or together (zoom out) through the
        // center, along a 45° diagonal.
        val near = 60f
        val far = 260f
        val (from, to) = if (zoomIn) Pair(near, far) else Pair(far, near)
        val dirX = cos(Math.toRadians(45.0)).toFloat()
        val dirY = sin(Math.toRadians(45.0)).toFloat()

        fun fingerPath(sign: Float): Path {
            fun clampX(v: Float) = v.coerceIn(10f, w - 10f)
            fun clampY(v: Float) = v.coerceIn(10f, h - 10f)
            return Path().apply {
                moveTo(clampX(centerX + sign * dirX * from), clampY(centerY + sign * dirY * from))
                lineTo(clampX(centerX + sign * dirX * to), clampY(centerY + sign * dirY * to))
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(fingerPath(1f), 0, 250))
            .addStroke(GestureDescription.StrokeDescription(fingerPath(-1f), 0, 250))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
