package co.remotedesktop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns the screen on for an approved viewer, who would otherwise see a blank
 * picture. Any lock screen stays in place. The phone goes back to sleep on its
 * usual timeout.
 *
 * Android may refuse without saying so: a Samsung phone does while the app's
 * notifications are off. So a second later this looks whether the screen came
 * on, and [LockState] tells the viewers if it didn't.
 */
internal object ScreenWaker {
    private const val HOLD_MS = 3000L
    /** How long the screen has to come on. On the S26 it takes well under a second. */
    const val CHECK_MS = 1000L

    private val main = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean()

    /** The screen was asked to come on and it isn't known yet whether it did. */
    val pending: Boolean get() = checking.get()

    fun wake(context: Context) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isInteractive) return
        try {
            // Deprecated in favour of an activity's turnScreenOn, but there is
            // no activity to show here.
            @Suppress("DEPRECATION")
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "remotedesktop:viewer"
            ).acquire(HOLD_MS)
        } catch (_: SecurityException) {
            // The screen stays off, as it did before this existed.
        }
        // One look covers every try made while it is awaited.
        if (!checking.compareAndSet(false, true)) return
        val app = context.applicationContext
        main.postDelayed({
            checking.set(false)
            LockState.wakeChecked(app, woke = power.isInteractive)
        }, CHECK_MS)
    }
}
