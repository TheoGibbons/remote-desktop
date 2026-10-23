package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Injects input sent by the paired computer. Enabled once in
 * Settings → Accessibility; survives reboots.
 *
 * Supports one-shot taps/swipes, a streamed `touch` pointer (down/move/up,
 * dispatched as continued gesture strokes so the desktop mouse can click,
 * long-press and drag in real time), a two-finger `pinch` for zoom, and the
 * desktop keyboard's `text` and `key` (see [PhoneKeyboard]).
 *
 * Note: Android does not allow apps to unlock a secure lockscreen (PIN /
 * pattern / biometric). Taps work on the lockscreen surface itself, so a
 * device with no secure lock (swipe-to-unlock) can be unlocked remotely.
 */
class InputAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: InputAccessibilityService? = null
            private set

        /** Whether text typed on the desktop reaches the phone: it needs the
         *  service's own input method, which Android 13 added. */
        val canType: Boolean
            get() = Build.VERSION.SDK_INT >= 33 && instance?.serviceInfo?.let {
                it.flags and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR != 0
            } == true
    }

    private val main = Handler(Looper.getMainLooper())
    private var screenCapture: AccessibilityScreenCapture? = null

    val canCaptureScreen: Boolean get() = screenCapture != null

    fun setScreenStreaming(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) screenCapture?.setStreaming(on)
    }

    fun resendScreenInfo() {
        if (Build.VERSION.SDK_INT >= 30) screenCapture?.resendScreenInfo()
    }

    override fun onServiceConnected() {
        instance = this
        if (Build.VERSION.SDK_INT >= 30) screenCapture?.close()
        screenCapture = null
        if (Build.VERSION.SDK_INT >= 30 &&
            (serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0) {
            screenCapture = AccessibilityScreenCapture(this)
        }
        // Keeps the session connected even if the process was restarted for this
        // service without the launcher activity being recreated.
        ConnectionManager.start(this)
        PhoneScreenCapture.refresh()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (Build.VERSION.SDK_INT >= 30) screenCapture?.close()
        screenCapture = null
        PhoneScreenCapture.refresh()
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
            "text", "key" -> main.post { type(msg) }
        }
    }

    // ---------- desktop keyboard ----------

    private fun type(msg: JSONObject) {
        val code = msg.optString("code")
        // Esc is the Back key of hardware keyboards on Android.
        if (msg.optString("type") == "key" && code == "ESC") {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) typeIntoField(msg, code)
    }

    @RequiresApi(33)
    private fun typeIntoField(msg: JSONObject, code: String) {
        val method = inputMethod ?: return
        // Null while no text field has focus.
        val field = method.currentInputConnection
        if (msg.optString("type") == "text") {
            field?.commitText(msg.optString("text"), 1, null)
            return
        }
        val ctrl = msg.optBoolean("ctrl")
        val shift = msg.optBoolean("shift")
        if (field == null) {
            // With nothing to type into, arrows and Enter move between and
            // press controls, as on a hardware keyboard.
            if (ctrl || shift) return
            when (code) {
                "UP" -> GLOBAL_ACTION_DPAD_UP
                "DOWN" -> GLOBAL_ACTION_DPAD_DOWN
                "LEFT" -> GLOBAL_ACTION_DPAD_LEFT
                "RIGHT" -> GLOBAL_ACTION_DPAD_RIGHT
                "ENTER" -> GLOBAL_ACTION_DPAD_CENTER
                else -> null
            }?.let { performGlobalAction(it) }
            return
        }
        if (code == "ENTER" && !ctrl && !shift) {
            PhoneKeyboard.enterAction(method.currentInputEditorInfo)?.let {
                field.performEditorAction(it)
                return
            }
        }
        val keyCode = PhoneKeyboard.keyCode(code) ?: return
        PhoneKeyboard.press(keyCode, ctrl, shift, SystemClock.uptimeMillis()).forEach(field::sendKeyEvent)
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
