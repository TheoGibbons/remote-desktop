package co.remotedesktop

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
 * locked, screen on, someone viewing. [LockScreenWatch] keeps the reading
 * seldom, and it is done off the main thread.
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
    /** When the lock screen is read again, and what viewers are told of it. */
    private val watch = LockScreenWatch()
    private var readInFlight = false
    /** Goes up each time reading starts or stops, so a read that comes back
     *  after that is known to be about a time that is over. */
    private var readEpoch = 0
    private val readDue = Runnable { readLockScreen() }

    /** Where the lock screen is walked: it is a string of calls into another
     *  process, and the main thread is where a viewer's touches are played. */
    internal var readOn: Executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "lock-screen-reader").apply { isDaemon = true }
    }

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
     * The accessibility service saw something change in the system's
     * interface: a window came or went ([windowChanged]), or something
     * inside one changed. It matters only while the lock screen is being
     * read, and [LockScreenWatch] says how soon it is worth reading again.
     */
    fun lockScreenChanged(windowChanged: Boolean) {
        if (lockScreenService == null) return
        val now = SystemClock.uptimeMillis()
        if (windowChanged) watch.windowChanged(now) else watch.contentChanged(now)
        scheduleRead()
    }

    /**
     * Starts reading the lock screen if it is time to, and stops if not.
     * [wanted] is asked afresh every time: the phone may have been unlocked
     * since the change that brought us here.
     */
    private fun lookAtLockScreen(wanted: Boolean) {
        val service = if (wanted) InputAccessibilityService.instance else null
        if (service === lockScreenService) return
        lockScreenService?.listenToLockScreen(false)
        lockScreenService = service
        readEpoch++
        // What was read is dropped with the reading; a new one starts from nothing.
        watch.start(SystemClock.uptimeMillis())
        main.removeCallbacks(readDue)
        if (service != null) {
            service.listenToLockScreen(true)
            scheduleRead()
        }
    }

    private fun scheduleRead() {
        main.removeCallbacks(readDue)
        // One read at a time. When it comes back, whatever is due by then is set down.
        if (readInFlight || lockScreenService == null) return
        val due = watch.nextDue ?: return
        main.postDelayed(readDue, (due - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun readLockScreen() {
        val service = lockScreenService ?: return
        val context = service.applicationContext
        // Asked afresh, and again where the reading is done: an unlocked phone is never read.
        if (!read(context).let { it.screenOn && it.locked }) {
            tell(context)
            return
        }
        watch.reading(SystemClock.uptimeMillis())
        readInFlight = true
        val epoch = readEpoch
        readOn.execute {
            val result = try {
                if (read(context).let { it.screenOn && it.locked }) service.readLockScreen() else LockScreenReader.Read.Unknown
            } catch (e: RuntimeException) {
                LockScreenReader.Read.Unknown
            }
            main.post {
                readInFlight = false
                // A read from before reading stopped, or started over, says nothing about now.
                if (epoch == readEpoch && watch.read(result, SystemClock.uptimeMillis())) tell(context)
                scheduleRead()
            }
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
        val text = message(reading, wakeFailed, watch.published).toString()
        val news = viewers.filter { told[it] != text }
        if (news.isEmpty()) return
        // Not the message line: it is the phone's own words to whoever holds it.
        Log.i("LockState", "To ${news.size} viewer(s): screenOn=${reading.screenOn} locked=${reading.locked}" +
            " wakeFailed=$wakeFailed entry=${watch.published.entry?.javaClass?.simpleName}")
        for (viewer in news) {
            told[viewer] = text
            ConnectionManager.sendJson(JSONObject(text).put("to", viewer))
        }
    }
}
