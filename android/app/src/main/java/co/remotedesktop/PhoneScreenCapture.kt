package co.remotedesktop

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routes phone viewing to the optional smooth stream, or persistent
 * Accessibility capture, and is the one place a picture leaves the phone
 * from ([send]): the gate, who it goes to, and how it is wrapped.
 *
 * Viewers on a current app say what they can draw (`codecs` in `start-view`).
 * While every viewer has, pictures go as type 5 frames of a stream with a
 * random id, which each viewer is told in a `screen-info` addressed to it
 * before the first frame. With any viewer that has not, everyone gets the
 * bare type 1 JPEG older apps know. See PROTOCOL.md, "Phone frames".
 */
object PhoneScreenCapture {
    /** How the screen is captured, as `screen-info` and `diagnostic-pong` name it. */
    const val ACCESSIBILITY = "accessibility"
    const val SCREEN_SHARE = "screen-share"

    /** The one format this app sends so far, as `codecs` and `codec` name it. */
    const val JPEG = "jpeg"

    private val main = Handler(Looper.getMainLooper())

    /** Who is viewing, and the `codecs` each offered: none, from an app that knows only type 1. */
    private val viewers = ConcurrentHashMap<String, Set<String>>()
    private var appContext: Context? = null
    val stateListeners = CopyOnWriteArrayList<() -> Unit>()

    val isAvailable: Boolean
        get() = ScreenCaptureService.isRunning ||
            (Build.VERSION.SDK_INT >= 30 && InputAccessibilityService.instance?.canCaptureScreen == true)

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        PeerAuth.changeListeners.add { refresh() }
    }

    /**
     * `start-view` from [peerId], with the `codecs` it offered if it offered
     * any. A viewer may send it again, and each one replaces its list.
     */
    fun onViewRequested(context: Context, peerId: String, codecs: Set<String> = emptySet()) {
        init(context)
        if (!Prefs(context).allowControl || !mayView(context, peerId)) return
        // Only a device that was not already viewing wakes the phone and is
        // announced. A viewer may ask again, and that changes nothing here.
        val started = viewers.put(peerId, codecs) == null
        // Whatever it was told about the stream, it is told again: it may have started over.
        synchronized(streamLock) { told.remove(peerId) }
        if (started) ScreenWaker.wake(context)
        main.post {
            if (!viewers.containsKey(peerId)) return@post
            if (started) {
                val setupHint = if (Build.VERSION.SDK_INT >= 30)
                    "Enable Screen view and control (Accessibility) in Permissions & Access"
                else "Enable screen sharing in Permissions & Access to view this phone"
                val viewer = ConnectionManager.peers.firstOrNull { it.id == peerId }?.name ?: "A paired device"
                val message = if (isAvailable) "$viewer started viewing this phone" else setupHint
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            // An additional viewer needs dimensions even if the source is already streaming.
            ScreenCaptureService.resendScreenInfo()
            InputAccessibilityService.instance?.resendScreenInfo()
        }
        refresh()
    }

    /** Whether this peer has asked to view this phone and not stopped since. */
    fun isViewing(peerId: String): Boolean = viewers.containsKey(peerId)

    /** An approved PC, or an approved phone while Allow other phones is on. */
    private fun mayView(context: Context, peerId: String): Boolean =
        PeerAuth.isTrusted(peerId) && Prefs(context).allowsDevice(ConnectionManager.deviceOf(peerId))

    /** Who frames are addressed to, so a device watching another host never gets them. */
    fun recipients(): List<String> {
        val context = appContext ?: return emptyList()
        return viewers.keys.filter { mayView(context, it) }
    }

    /** Clear requests on disconnect/revocation so a later session never resumes an old view. */
    fun onViewStopped(peerId: String? = null) {
        if (peerId == null) viewers.clear() else viewers.remove(peerId)
        forgetGone()
        // If it starts again it is told how things stand again, though nothing has changed.
        LockState.forget(peerId)
        refresh()
    }

    /** Rechecked before capture and before sending, including after asynchronous screenshots. */
    fun maySendFrames(context: Context): Boolean =
        ConnectionManager.state == "connected" && Prefs(context).allowControl &&
            PeerAuth.allPeersTrusted && viewers.keys.any { mayView(context, it) }

    fun refresh() {
        main.post {
            val context = appContext ?: return@post
            viewers.keys.removeAll { !mayView(context, it) }
            forgetGone()
            // A screen turned for a viewer goes back to the phone's own way with the last of them.
            if (viewers.isEmpty()) InputAccessibilityService.instance?.releaseRotation()
            val active = maySendFrames(context)
            // No pictures for now, e.g. while a device waits to be approved. The stream is over.
            if (!active) pauseStream()
            // Each viewer is told whether the screen is on and locked, while there is one.
            LockState.watch(context, active)
            val smooth = ScreenCaptureService.isRunning
            // Stop the old source before starting the new one.
            if (smooth) {
                InputAccessibilityService.instance?.setScreenStreaming(false)
                ScreenCaptureService.setStreaming(active)
            } else {
                ScreenCaptureService.setStreaming(false)
                InputAccessibilityService.instance?.setScreenStreaming(active)
            }
            stateListeners.forEach { it() }
        }
    }

    // ---------------- the stream ----------------
    //
    // Pictures come from a capture thread, viewers from the network thread
    // and the gate from the main thread, so everything below is used under
    // streamLock. A send holds it from choosing the format to handing the
    // frame to the socket, which keeps a stream's announcement ahead of its
    // first frame on the wire.

    private val streamLock = Any()

    /** The id of the type 5 stream being sent; 0 while there is none. */
    private var stream = 0L
    private var nextSeq = 0L
    private var capture: String? = null
    private var size: Pair<Int, Int>? = null

    /** What each viewer that offered `codecs` was last told about the stream. */
    private val told = HashMap<String, String>()

    /** The highest frame of the current stream each viewer has acknowledged. */
    private val acked = HashMap<String, Long>()

    /** When each picture of the last second went out (uptime). */
    private val sentAt = ArrayDeque<Long>()

    /** A new stream's id: random, so that nobody who was not told it can guess it. */
    internal var newStreamId: () -> Long = {
        val random = SecureRandom()
        var id: Long
        do { id = random.nextLong() } while (id == 0L)
        id
    }

    private const val TYPE_1 = "type 1"
    private const val PAUSED = "paused"

    /**
     * Tells every viewer the picture's size, and those that offered `codecs`
     * how pictures are being sent. Both ways of capturing call this where
     * they have always sent `screen-info`: when streaming starts, when a
     * viewer asks, and when the size changes. A viewer on an older app sizes
     * its window on every one, so it is sent no others.
     */
    fun sendScreenInfo(context: Context, width: Int, height: Int, capture: String) {
        if (!maySendFrames(context)) return
        val to = recipients()
        synchronized(streamLock) {
            val word = settle(to, capture) ?: return
            size = width to height
            for (viewer in to) tell(viewer, word, always = true)
        }
    }

    /**
     * Sends one JPEG of the screen to whoever is viewing: as a type 5 frame
     * of the current stream when every viewer can draw one, and as a bare
     * type 1 JPEG otherwise. A viewer that has not been told the stream's id
     * is told first. Nothing is sent while the gate is closed, and nothing is
     * ever sent to nobody, which the relay would take for everybody.
     *
     * @param capturedAt when the screen was captured, on the uptime clock.
     * @return whether the picture went out; false also when the queue was too full to take it.
     */
    fun send(context: Context, jpeg: ByteArray, width: Int, height: Int, capturedAt: Long, capture: String): Boolean {
        if (!maySendFrames(context)) return false
        val to = recipients()
        synchronized(streamLock) {
            val word = settle(to, capture) ?: return false
            size = width to height
            for (viewer in to) tell(viewer, word, always = false)
            val sent = if (stream == 0L) ConnectionManager.sendBinary(Crypto.CH_VIDEO, jpeg, to) else {
                val header = PhoneFrame.Header(stream, nextSeq, keyframe = true, format = PhoneFrame.JPEG,
                    width = width, height = height, captureMs = capturedAt and 0xFFFFFFFFL)
                // A picture the queue refused was never sent, and takes no number.
                ConnectionManager.sendBinary(Crypto.CH_PHONE, PhoneFrame.build(header, jpeg), to).also { if (it) nextSeq++ }
            }
            if (sent) noteSent(SystemClock.uptimeMillis())
            return sent
        }
    }

    /**
     * Chooses the format again for the viewers there are now, and starts or
     * ends the stream to match. A stream starts when pictures can first go as
     * type 5, and anew when the way the screen is captured changes; it ends
     * when a viewer that knows only type 1 is among them.
     *
     * @return what viewers are to be told, or null when there is nobody to send to.
     */
    private fun settle(to: List<String>, capture: String): String? {
        if (to.isEmpty()) return null
        if (!to.all { JPEG in viewers[it].orEmpty() }) stream = 0L
        else if (stream == 0L || this.capture != capture) {
            stream = newStreamId()
            nextSeq = 0
            acked.clear()
        }
        this.capture = capture
        return if (stream == 0L) TYPE_1 else PhoneFrame.streamHex(stream)
    }

    /**
     * Sends [viewer] a `screen-info`. Unless [always], only if it offered
     * `codecs` and has not been told just this: these are the extra ones a
     * stream starting or ending brings.
     */
    private fun tell(viewer: String, word: String, always: Boolean) {
        val offered = !viewers[viewer].isNullOrEmpty()
        val key = "$word $capture"
        if (!always && (!offered || told[viewer] == key)) return
        if (offered) told[viewer] = key
        val msg = JSONObject().put("type", "screen-info")
        size?.let { (width, height) -> msg.put("width", width).put("height", height) }
        msg.put("typing", InputAccessibilityService.canType)
        if (offered) {
            capture?.let { msg.put("capture", it) }
            when (word) {
                TYPE_1 -> Unit
                PAUSED -> msg.put("paused", true)
                else -> msg.put("stream", word).put("codec", JPEG)
            }
        }
        ConnectionManager.sendJson(msg.put("to", viewer))
    }

    /**
     * Pictures have stopped with viewers still there: a device is waiting to
     * be approved, say. The stream's id is dead, and the viewers that were
     * given one are told nothing is being sent, so they draw nothing until
     * they are told of the next stream.
     */
    private fun pauseStream() = synchronized(streamLock) {
        stream = 0L
        for (viewer in recipients()) tell(viewer, PAUSED, always = false)
    }

    /** Counts a picture that went out at [now], and keeps the count to the last second. */
    private fun noteSent(now: Long? = null): Int {
        val time = now ?: SystemClock.uptimeMillis()
        if (now != null) sentAt.addLast(now)
        while (sentAt.isNotEmpty() && time - sentAt.first() >= 1000) sentAt.removeFirst()
        return sentAt.size
    }

    /** Forgets what was told to, and heard from, devices that no longer view. */
    private fun forgetGone() = synchronized(streamLock) {
        told.keys.retainAll(viewers.keys)
        acked.keys.retainAll(viewers.keys)
        // The last viewer has gone: the stream ends with it.
        if (viewers.isEmpty()) stream = 0L
    }

    /**
     * `frame-ack` from a device that is viewing: the highest frame of the
     * current stream it has received. Remembered; nothing is held back by it yet.
     */
    fun onFrameAck(peerId: String, msg: JSONObject) {
        val id = PhoneFrame.parseStream(msg.optString("stream")) ?: return
        val seq = msg.optLong("seq", -1)
        synchronized(streamLock) {
            if (stream == 0L || id != stream || seq < 0 || seq >= nextSeq) return
            if (seq > (acked[peerId] ?: -1)) acked[peerId] = seq
        }
    }

    /** The highest frame of the current stream [peerId] has acknowledged, if any. */
    internal fun ackedSeq(peerId: String): Long? = synchronized(streamLock) { acked[peerId] }

    /**
     * `diagnostic-pong`, without its `to`: this phone's clock as the ping was
     * handled, which is the clock `captureMs` is read from, and what the
     * stream is doing.
     */
    fun pong(nonce: Long, queueBytes: Long): JSONObject = synchronized(streamLock) {
        val now = SystemClock.uptimeMillis()
        val lastSecond = noteSent()
        JSONObject().put("type", "diagnostic-pong")
            .put("nonce", nonce)
            .put("hostTimeMs", now)
            .put("hostQueueBytes", queueBytes)
            .put("codec", JPEG)
            .put("capture", if (ScreenCaptureService.isRunning) SCREEN_SHARE else ACCESSIBILITY)
            .put("fps", lastSecond)
    }
}
