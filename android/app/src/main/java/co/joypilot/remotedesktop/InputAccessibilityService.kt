package co.joypilot.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * Injects taps/swipes sent by the paired computer. Enabled once in
 * Settings → Accessibility; survives reboots.
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
}
