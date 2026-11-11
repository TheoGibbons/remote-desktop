package co.remotedesktop

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * Whether this phone's screen is on and whether it is locked, and telling
 * that to the devices viewing it (`lock-state` in PROTOCOL.md). A viewer of a
 * sleeping or locked phone gets a dark picture; this is how it can say why.
 *
 * Each viewer is told when it starts viewing and whenever something changes,
 * through the same gate as the picture ([PhoneScreenCapture.maySendFrames]
 * and [PhoneScreenCapture.recipients]), never by broadcast. The phone listens
 * for its screen and its lock only while someone is viewing.
 */
internal object LockState {
    /** What Android says right now. */
    data class Reading(val screenOn: Boolean, val locked: Boolean) {
        /**
         * On and past the lock screen, which is the only time the screen may
         * be turned for a viewer ([RotationOverride]). The lock screen is
         * where someone unlocks the phone, and keeps the phone's own
         * orientation; and a screen turned while it was off would come back
         * on turned, with nothing left to tell the phone to let go.
         */
        val inUse: Boolean get() = screenOn && !locked
    }

    /** `wakeFailed` when Android refused because the app's notifications are off. */
    const val WAKE_NOTIFICATIONS = "notifications"
    /** `wakeFailed` when the screen stayed off for a reason the app can't see. */
    const val WAKE_UNKNOWN = "unknown"

    fun read(context: Context): Reading {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return Reading(power.isInteractive, keyguard.isKeyguardLocked)
    }

    /**
     * Builds `lock-state`, without its `to`. [wakeFailed] says why the screen
     * is still off after the phone tried to turn it on for a viewer, and only
     * counts while it is off.
     */
    fun message(reading: Reading, wakeFailed: String? = null): JSONObject {
        val msg = JSONObject()
            .put("type", "lock-state")
            .put("screenOn", reading.screenOn)
            .put("locked", reading.locked)
        if (!reading.screenOn && wakeFailed != null) msg.put("wakeFailed", wakeFailed)
        return msg
    }

    // Everything below is used on the main thread only.

    private var listening = false
    private var wakeFailed: String? = null
    /** What each viewer was last told, so it is told again only what is new. */
    private val told = HashMap<String, String>()

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = tell(context)
    }

    /**
     * Called by [PhoneScreenCapture.refresh] whenever a viewer starts or
     * stops, with whether anyone may be sent the picture now. Tells a viewer
     * that has just started how things stand.
     */
    fun watch(context: Context, viewing: Boolean) {
        if (listening != viewing) {
            listening = viewing
            try {
                if (viewing) context.registerReceiver(changes, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    // The lock screen has been passed.
                    addAction(Intent.ACTION_USER_PRESENT)
                })
                else context.unregisterReceiver(changes)
            } catch (_: RuntimeException) {
                // Not registered after all; viewers are still told at each start.
            }
            // Whether a wake failed is news only to whoever was viewing then.
            if (!viewing) wakeFailed = null
        }
        tell(context)
    }

    /** [ScreenWaker] tried to turn the screen on a moment ago; [woke] is whether it is on now. */
    fun wakeChecked(context: Context, woke: Boolean) {
        if (!listening) return
        wakeFailed = when {
            woke -> null
            // Samsung turns no screen on for an app whose notifications are off.
            !PermissionsActivity.notificationsOn(context) -> WAKE_NOTIFICATIONS
            else -> WAKE_UNKNOWN
        }
        tell(context)
    }

    /** Tells each viewer how things stand, unless it has been told just that. */
    private fun tell(context: Context) {
        val viewers = PhoneScreenCapture.recipients()
        told.keys.retainAll(viewers.toSet())
        if (!PhoneScreenCapture.maySendFrames(context)) return
        val reading = read(context)
        if (reading.screenOn) wakeFailed = null
        // The screen is being turned on. Wait to see whether it comes on, so a
        // viewer isn't told "asleep" for the moment that takes.
        else if (ScreenWaker.pending) return
        val text = message(reading, wakeFailed).toString()
        val news = viewers.filter { told[it] != text }
        if (news.isEmpty()) return
        Log.i("LockState", "To ${news.size} viewer(s): $text")
        for (viewer in news) {
            told[viewer] = text
            ConnectionManager.sendJson(JSONObject(text).put("to", viewer))
        }
    }
}
