package co.remotedesktop

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
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
 *
 * While the phone is locked with its screen on, a viewer is also told where
 * the pattern or PIN is entered once the unlock screen is up, and what the
 * lock screen's message line says ([LockScreenReader]). That is the only time
 * the lock screen is read, and it stops the moment any of the three ends:
 * locked, screen on, someone viewing.
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
     * counts while it is off. [lockScreen] is what the unlock screen shows,
     * and only counts while the phone is locked with its screen on.
     */
    fun message(reading: Reading, wakeFailed: String? = null,
                lockScreen: LockScreenReader.Found = LockScreenReader.Found.NOTHING): JSONObject {
        val msg = JSONObject()
            .put("type", "lock-state")
            .put("screenOn", reading.screenOn)
            .put("locked", reading.locked)
        if (!reading.screenOn && wakeFailed != null) msg.put("wakeFailed", wakeFailed)
        if (reading.screenOn && reading.locked) {
            entry(lockScreen.entry)?.let { msg.put("entry", it) }
            lockScreen.message?.let { msg.put("message", it) }
        }
        return msg
    }

    /** `entry` of `lock-state`: where the pattern, PIN or password is entered. */
    fun entry(entry: LockScreenReader.Entry?): JSONObject? = when (entry) {
        null -> null
        is LockScreenReader.Entry.Pattern -> JSONObject().put("kind", "pattern").put("area", JSONArray(entry.area.toList()))
        is LockScreenReader.Entry.Pin -> JSONObject().put("kind", "pin").put("keys", JSONArray().also { keys ->
            for (key in entry.keys) keys.put(JSONObject().put("label", key.label).put("rect", JSONArray(key.rect.toList())))
        })
        LockScreenReader.Entry.Password -> JSONObject().put("kind", "password")
    }

    // Everything below is used on the main thread only.

    private val main = Handler(Looper.getMainLooper())
    private var listening = false
    private var wakeFailed: String? = null

    /** The service that is reporting changes on the lock screen; null while
     *  the lock screen is not to be read. */
    private var lockScreenService: InputAccessibilityService? = null
    private var lockScreen = LockScreenReader.Found.NOTHING
    /** The lock screen has changed since it was read. */
    private var lockScreenStale = false
    private var rereadPending = false
    /** A lock screen that is counting down changes every second; it is read
     *  no more often than this. */
    private const val REREAD_MS = 150L
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

    /**
     * A device has stopped viewing ([peerId]), or all have (null). What it
     * was told is forgotten in the order things happened: a viewer that
     * stops and starts again before this phone has caught up would
     * otherwise be told nothing, because nothing has changed since.
     */
    fun forget(peerId: String?) {
        main.post { if (peerId == null) told.clear() else told.remove(peerId) }
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

    /**
     * The accessibility service saw something change in a window of the
     * system's interface. It matters only while the lock screen is being
     * read, and then it is read again shortly, once for however many changes
     * arrive meanwhile.
     */
    fun lockScreenChanged(context: Context) {
        if (lockScreenService == null || rereadPending) return
        rereadPending = true
        val app = context.applicationContext
        main.postDelayed({
            rereadPending = false
            lockScreenStale = true
            tell(app)
        }, REREAD_MS)
    }

    /**
     * Reads the lock screen if it is time to, and stops reading it if not.
     * [wanted] is asked afresh every time: the phone may have been unlocked
     * since the change that brought us here.
     */
    private fun lookAtLockScreen(wanted: Boolean) {
        val service = if (wanted) InputAccessibilityService.instance else null
        if (service !== lockScreenService) {
            lockScreenService?.listenToLockScreen(false)
            lockScreenService = service
            service?.listenToLockScreen(true)
            lockScreenStale = true
        }
        if (lockScreenStale) {
            lockScreenStale = false
            lockScreen = service?.readLockScreen() ?: LockScreenReader.Found.NOTHING
        }
    }

    /** Tells each viewer how things stand, unless it has been told just that. */
    private fun tell(context: Context) {
        val viewers = PhoneScreenCapture.recipients()
        told.keys.retainAll(viewers.toSet())
        val reading = read(context)
        val viewed = listening && PhoneScreenCapture.maySendFrames(context)
        lookAtLockScreen(viewed && reading.screenOn && reading.locked)
        if (!viewed) return
        if (reading.screenOn) wakeFailed = null
        // The screen is being turned on. Wait to see whether it comes on, so a
        // viewer isn't told "asleep" for the moment that takes.
        else if (ScreenWaker.pending) return
        val text = message(reading, wakeFailed, lockScreen).toString()
        val news = viewers.filter { told[it] != text }
        if (news.isEmpty()) return
        // Not the message line: it is the phone's own words to whoever holds it.
        Log.i("LockState", "To ${news.size} viewer(s): screenOn=${reading.screenOn} locked=${reading.locked}" +
            " wakeFailed=$wakeFailed entry=${lockScreen.entry?.javaClass?.simpleName}")
        for (viewer in news) {
            told[viewer] = text
            ConnectionManager.sendJson(JSONObject(text).put("to", viewer))
        }
    }
}
