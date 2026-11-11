package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Holds the phone's screen upright or on its side for a viewer, whatever way
 * the phone is lying (`rotate` in PROTOCOL.md).
 *
 * An app can't turn the screen for other apps, but Android turns the display
 * to suit the topmost window that asks for an orientation, and an
 * accessibility service may add a window. So this adds one that is empty,
 * takes no touches, and asks. Removing it gives rotation back to the phone.
 * The window belongs to the service: if the service stops or the app dies it
 * goes too, so the phone is never left stuck sideways.
 *
 * It is let go when nobody is viewing any more ([PhoneScreenCapture]), and
 * when the screen turns off, which is also when the phone locks: the lock
 * screen is where someone unlocks the phone, and keeps the phone's own
 * orientation. It is not put back afterwards.
 */
internal class RotationOverride(private val service: AccessibilityService) {
    companion object {
        const val AUTO = "auto"
        const val PORTRAIT = "portrait"
        const val LANDSCAPE = "landscape"

        /** The orientation a window asks Android for, or null for [AUTO] and
         *  for anything that isn't one of the three. */
        fun windowOrientation(orientation: String): Int? = when (orientation) {
            PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> null
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** What is being held, as `phone-state` reports it. Set at once, so the
     *  answer to a `rotate` already says it; the window follows on the main thread. */
    @Volatile var orientation: String = AUTO
        private set

    // Main thread only.
    private var window: View? = null
    private var listening = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            set(AUTO)
        }
    }

    /** Holds the screen [wanted] way, or gives rotation back for [AUTO].
     *  Returns false for anything else, which changes nothing. */
    fun set(wanted: String): Boolean {
        if (wanted != AUTO && windowOrientation(wanted) == null) return false
        orientation = wanted
        main.post(::apply)
        return true
    }

    /** Gives rotation back to the phone. */
    fun release() {
        if (orientation != AUTO) set(AUTO)
    }

    /** The service is going: take the window with it now rather than when Android gets round to it. */
    fun close() {
        orientation = AUTO
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else main.post(::apply)
    }

    /** Makes the window match [orientation]. Main thread. */
    private fun apply() {
        val wanted = windowOrientation(orientation)
        val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            val view = window
            when {
                wanted == null -> if (view != null) {
                    window = null
                    manager.removeView(view)
                }
                view == null -> {
                    val added = View(service)
                    manager.addView(added, params(wanted))
                    window = added
                }
                else -> manager.updateViewLayout(view, params(wanted))
            }
        } catch (e: RuntimeException) {
            // E.g. the service was switched off between the message and now.
            Log.w("RotationOverride", "Could not change the rotation window", e)
            if (window == null) orientation = AUTO
        }
        listen(window != null)
    }

    private fun params(orientation: Int) = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        screenOrientation = orientation
        title = "Remote Desktop rotation"
    }

    /** Listens for the screen turning off only while there is something to let go of. */
    private fun listen(on: Boolean) {
        if (listening == on) return
        listening = on
        try {
            if (on) service.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            else service.unregisterReceiver(screenOff)
        } catch (_: RuntimeException) {
            // Not registered after all; nothing to undo.
        }
    }
}
