package co.remotedesktop

import android.content.Context
import android.os.PowerManager

/**
 * Turns the screen on for an approved viewer, who would otherwise see a blank
 * picture. Any lock screen stays in place. The phone goes back to sleep on its
 * usual timeout.
 */
internal object ScreenWaker {
    private const val HOLD_MS = 3000L

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
    }
}
