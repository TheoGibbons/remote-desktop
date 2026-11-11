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
import android.util.TypedValue
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import org.json.JSONObject

/**
 * Injects input sent by a paired PC or phone. Enabled once in
 * Settings → Accessibility; survives reboots.
 *
 * Supports one-shot taps/swipes, a streamed `touch` pointer (down/move/up,
 * dispatched as continued gesture strokes so a mouse or finger can click,
 * long-press and drag in real time), a two-finger `pinch` for zoom, and
 * typed `text` and `key` (see [PhoneKeyboard]). [GestureSequencer] keeps
 * them in order.
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

        /** The rows of a viewer's menu, beyond navigation, that this phone can
         *  act on right now, by the names `phone-state` lists them under.
         *  Locking and taking a screenshot came with Android 9. */
        val menuActions: List<String>
            get() = when {
                instance == null -> emptyList()
                Build.VERSION.SDK_INT >= 28 -> listOf("quick-settings", "lock", "screenshot")
                else -> listOf("quick-settings")
            }

        /**
         * Whether a message is real input, which wakes a sleeping phone the
         * way touching it would. `lock` is not: it would turn the screen on
         * only to turn it off again. Pointer moves are frequent and always
         * follow a down. Anything not named here does not wake the phone.
         */
        internal fun wakesPhone(msg: JSONObject): Boolean = when (msg.optString("type")) {
            "touch" -> msg.optString("action") != "move"
            "tap", "swipe", "pinch", "text", "key", "back", "homebtn", "recents", "notifications",
            "quick-settings", "screenshot" -> true
            else -> false
        }
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
        ConnectionService.startFromAccessibility(this)
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
        // The phone may have gone back to sleep while being viewed.
        if (wakesPhone(msg)) ScreenWaker.wake(this)
        when (msg.optString("type")) {
            "tap" -> {
                val (w, h) = screenSize()
                val gesture = tap(msg.getDouble("x") * w, msg.getDouble("y") * h)
                main.post { gestures.whole(gesture) }
            }
            "swipe" -> {
                val (w, h) = screenSize()
                val gesture = swipe(
                    msg.getDouble("x1") * w, msg.getDouble("y1") * h,
                    msg.getDouble("x2") * w, msg.getDouble("y2") * h,
                    msg.optLong("ms", 250).coerceIn(50, 3000)
                )
                main.post { gestures.whole(gesture) }
            }
            "touch" -> {
                val (w, h) = screenSize()
                val x = (msg.optDouble("x", 0.0) * w).toFloat()
                val y = (msg.optDouble("y", 0.0) * h).toFloat()
                val action = msg.optString("action")
                main.post {
                    when (action) {
                        "down" -> gestures.down(x, y)
                        "move" -> gestures.move(x, y)
                        // The finger lifts where the last move left it.
                        "up" -> gestures.up()
                    }
                }
            }
            "pinch" -> {
                val (w, h) = screenSize()
                val cx = (msg.optDouble("x", 0.5) * w).toFloat()
                val cy = (msg.optDouble("y", 0.5) * h).toFloat()
                val zoomIn = msg.optString("dir") != "out"
                val scale = if (msg.has("scale")) msg.optDouble("scale") else null
                val gesture = pinch(w, h, cx, cy, zoomIn, scale)
                main.post { gestures.whole(gesture) }
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "homebtn" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick-settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            // Saved by the phone like any screenshot, so a PC is offered it (see CaptureOffers).
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            "lock" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
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

    private fun tap(x: Double, y: Double): GestureDescription {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 30))
            .build()
    }

    private fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, ms: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
    }

    // ---------- streamed touch pointer (continued strokes) ----------
    // Main thread only, like the sequencer and its callbacks.

    /** The stroke the next segment continues; null once its finger has lifted. */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    private val gestures = GestureSequencer(object : GestureSequencer.Player<GestureDescription> {
        override fun begin(generation: Int, x: Float, y: Float): Boolean {
            val path = Path().apply { moveTo(x, y) }
            // willContinue=true keeps the finger "down" until the up arrives.
            val stroke = GestureDescription.StrokeDescription(path, 0, 40, true)
            activeStroke = stroke
            return dispatch(GestureDescription.Builder().addStroke(stroke).build(), generation)
        }

        override fun extend(generation: Int, fromX: Float, fromY: Float, points: List<Pair<Float, Float>>,
                            durationMs: Long, lift: Boolean): Boolean {
            val stroke = activeStroke ?: return false
            val path = Path().apply {
                moveTo(fromX, fromY)
                for ((px, py) in points) lineTo(px, py)
            }
            val next = stroke.continueStroke(path, 0, durationMs, !lift)
            activeStroke = if (lift) null else next
            return dispatch(GestureDescription.Builder().addStroke(next).build(), generation)
        }

        override fun play(generation: Int, gesture: GestureDescription): Boolean {
            activeStroke = null
            return dispatch(gesture, generation)
        }
    })

    private fun dispatch(gesture: GestureDescription, generation: Int): Boolean =
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = gestures.completed(generation)
            override fun onCancelled(g: GestureDescription?) = gestures.cancelled(generation)
        }, main)

    // ---------- pinch zoom ----------

    /** Fingers closer than this don't zoom (see [PinchGeometry]). */
    private fun minScalingSpan(): Float {
        if (Build.VERSION.SDK_INT >= 29) try {
            return ViewConfiguration.get(this).scaledMinimumScalingSpan.toFloat()
        } catch (_: RuntimeException) {
            // Below is the platform's own default.
        }
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 27f, resources.displayMetrics)
    }

    private fun pinch(w: Int, h: Int, centerX: Float, centerY: Float, zoomIn: Boolean, scale: Double?): GestureDescription {
        val (one, two) = PinchGeometry.fingers(w, h, centerX, centerY, zoomIn, scale, minScalingSpan())
        fun path(f: PinchGeometry.Finger) = Path().apply {
            moveTo(f.startX, f.startY)
            lineTo(f.endX, f.endY)
        }
        // A pinch from another phone moves further, so it gets longer.
        val ms = if (scale == null) 250L else 400L
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path(one), 0, ms))
            .addStroke(GestureDescription.StrokeDescription(path(two), 0, ms))
            .build()
    }
}
