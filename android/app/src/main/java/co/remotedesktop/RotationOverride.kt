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
 * orientation. It is not put back afterwards, and for the same reason it is
 * never taken up while the screen is off or the phone is locked
 * ([PhoneControls.screenInUse]).
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

    /** What is being held right now, as `phone-state` reports it: what the
     *  window asks for, or [AUTO] when there is no window. */
    @Volatile var orientation: String = AUTO
        private set

    // Main thread only.
    private var window: View? = null
    private var listening = false
    private var closed = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            release()
        }
    }

    /**
     * Holds the screen [wanted] way, or gives rotation back for [AUTO].
     *
     * It happens on the main thread, in the order asked. [allowed] is asked
     * there, at that moment: whoever asked may have stopped viewing, or the
     * screen may have gone off, since the message was let in, and then
     * nothing is held. [then] runs straight after, when [orientation] says
     * what is really held, so an answer sent from it never claims a window
     * that could not be added.
     *
     * Returns false for anything that isn't one of the three, which changes
     * nothing and runs neither.
     */
    fun set(wanted: String, allowed: () -> Boolean = { true }, then: () -> Unit = {}): Boolean {
        if (wanted != AUTO && windowOrientation(wanted) == null) return false
        main.post {
            apply(if (wanted == AUTO || allowed()) wanted else AUTO)
            then()
        }
        return true
    }

    /** Gives rotation back to the phone: at once on the main thread, where
     *  the viewers are counted and the screen is heard turning off. */
    fun release() = onMain { apply(AUTO) }

    /** The service is going: take the window with it now rather than when
     *  Android gets round to it, and add none after. */
    fun close() = onMain {
        closed = true
        apply(AUTO)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Makes the window ask for [target]. Main thread. */
    private fun apply(target: String) {
        val wanted = if (closed) null else windowOrientation(target)
        try {
            val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = window
            when {
                wanted == null -> if (view != null) {
                    window = null
                    orientation = AUTO
                    manager.removeView(view)
                }
                view == null -> {
                    val added = View(service)
                    manager.addView(added, params(wanted))
                    window = added
                    orientation = target
                }
                else -> {
                    manager.updateViewLayout(view, params(wanted))
                    orientation = target
                }
            }
        } catch (e: RuntimeException) {
            // E.g. the service was switched off between the message and now.
            // What is held is what it was: nothing, if there is no window.
            Log.w("RotationOverride", "Could not change the rotation window", e)
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
