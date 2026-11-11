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
 *
 * A stream is JPEG or H.264 from start to end ([StreamFormat]). H.264 comes
 * from whichever of the two is capturing, [ScreenCaptureService] or
 * [AccessibilityScreenCapture], while every viewer can draw it and its
 * encoder works ([sendVideo]); everything else is JPEG ([send]).
 */
object PhoneScreenCapture {
    /** How the screen is captured, as `screen-info` and `diagnostic-pong` name it. */
    const val ACCESSIBILITY = "accessibility"
    const val SCREEN_SHARE = "screen-share"

    /** What pictures are sent as, as `codecs` and `codec` name it. */
    const val JPEG = "jpeg"
    const val H264 = "h264"

    private val main = Handler(Looper.getMainLooper())

    /** Who is viewing, and the `codecs` each offered: none, from an app that knows only type 1. */
    private val viewers = ConcurrentHashMap<String, Set<String>>()

    /** Each viewer's identity, which it keeps when it reconnects under a new id (see [KeepAwake]). */
    private val viewerDevice = ConcurrentHashMap<String, String>()

    private fun identityOf(peerId: String): String = PeerAuth.fingerprintOfPeer(peerId) ?: peerId
    private var appContext: Context? = null
    val stateListeners = CopyOnWriteArrayList<() -> Unit>()

    val isAvailable: Boolean get() = ScreenCaptureService.isRunning || canViewWithoutSharing

    /**
     * Whether this phone can show its screen without smooth sharing: by
     * Accessibility pictures, which need Android 11 and the service
     * switched on. Where it cannot, smooth sharing is all a viewer has.
     */
    val canViewWithoutSharing: Boolean
        get() = Build.VERSION.SDK_INT >= 30 && InputAccessibilityService.instance?.canCaptureScreen == true

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
        if (started) identityOf(peerId).let {
            viewerDevice[peerId] = it
            ScreenCaptureService.viewerStarted(it)
        }
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

    /** The viewers that know `smooth-status`: those on an app that offers `codecs`. */
    fun statusRecipients(): List<String> = recipients().filter { !viewers[it].isNullOrEmpty() }

    /**
     * Clear requests on disconnect/revocation so a later session never resumes an old view.
     * [byViewer] when [peerId] itself said it has stopped, with `stop-view`.
     */
    fun onViewStopped(peerId: String? = null, byViewer: Boolean = false) {
        for (id in if (peerId == null) viewers.keys.toList() else listOf(peerId)) {
            // A stop-view also counts from a device whose view was already lost with its connection.
            val wasViewing = viewers.remove(id) != null
            if (wasViewing || byViewer) ScreenCaptureService.viewerStopped(viewerDevice.remove(id) ?: identityOf(id), byViewer)
        }
        forgetGone()
        // If it starts again it is told how things stand again, though nothing has changed.
        LockState.forget(peerId)
        refresh()
    }

    /** [peerId] may no longer view. Not its own doing, so it is not a view that it closed. */
    private fun onViewEnded(peerId: String) {
        if (viewers.remove(peerId) != null)
            ScreenCaptureService.viewerStopped(viewerDevice.remove(peerId) ?: identityOf(peerId), byItself = false)
    }

    /** Rechecked before capture and before sending, including after asynchronous screenshots. */
    fun maySendFrames(context: Context): Boolean =
        ConnectionManager.state == "connected" && Prefs(context).allowControl &&
            PeerAuth.allPeersTrusted && viewers.keys.any { mayView(context, it) }

    fun refresh() {
        main.post {
            val context = appContext ?: return@post
            // Approval taken back, or control switched off.
            for (id in viewers.keys.filter { !mayView(context, it) }) onViewEnded(id)
            forgetGone()
            // A screen turned for a viewer goes back to the phone's own way with the last of them.
            if (viewers.isEmpty()) InputAccessibilityService.instance?.releaseRotation()
            // A prompt for smooth sharing goes when whoever asked for it has.
            SmoothPrompt.viewersNow(viewers.keys.toList())
            val active = maySendFrames(context)
            // No pictures for now, e.g. while a device waits to be approved. The stream is over.
            if (!active) pauseStream()
            // Pictures may go, and the video stream that is running had one go unsent a
            // moment ago, while they might not. On a screen that has not changed since,
            // nothing would bring the keyframe it now needs.
            else if (synchronized(streamLock) { stream != 0L && codec == H264 && needKey && nextSeq > 0 })
                askKeyframe(false)
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

    /** What the stream's pictures are: [JPEG] or [H264]. */
    private var codec = JPEG

    /**
     * An H.264 stream may only go on with a keyframe: it has just started,
     * or a picture of it could not be sent. Every other picture builds on
     * the one before it, so until a keyframe comes nothing else is sent.
     */
    private var needKey = false

    /** The last keyframe of an H.264 stream: its number, and when it went. */
    private var lastKeySeq = -1L
    private var lastKeyAt = 0L

    /** What each viewer that offered `codecs` was last told about the stream. */
    private val told = HashMap<String, String>()

    /** What has been sent of the current stream and what each viewer says has arrived. */
    private val inFlight = InFlight()

    /** The last picture that went out in a stream, kept in case it has to be sent again. */
    private class Sent(val stream: Long, val jpeg: ByteArray, val width: Int, val height: Int,
                       val capturedAt: Long, val capture: String)
    private var last: Sent? = null

    /** When each picture of the last second went out (uptime). */
    private val sentAt = ArrayDeque<Long>()

    /** A new stream's id: random, so that nobody who was not told it can guess it. */
    internal var newStreamId: () -> Long = {
        val random = SecureRandom()
        var id: Long
        do { id = random.nextLong() } while (id == 0L)
        id
    }

    /**
     * Asks whichever is capturing for a keyframe, or for the screen to be
     * encoded again: smooth sharing while it is on, and the Accessibility
     * service's screenshots otherwise. Replaced in tests.
     */
    internal var askKeyframe: (byViewer: Boolean) -> Unit = { byViewer ->
        if (ScreenCaptureService.isRunning) ScreenCaptureService.keyframeWanted(byViewer)
        else InputAccessibilityService.instance?.keyframeWanted(byViewer)
    }
    internal var askResend: () -> Unit = {
        if (ScreenCaptureService.isRunning) ScreenCaptureService.resendWanted()
        else InputAccessibilityService.instance?.resendWanted()
    }

    private const val TYPE_1 = "type 1"
    private const val PAUSED = "paused"

    /** How long a keyframe is given to reach a viewer before its asking for one is believed. */
    private const val KEYFRAME_ON_ITS_WAY_MS = 1_000L

    /** The soonest the newest picture is looked at again after a try to send it again found no room. */
    private const val RESEND_CHECK_MS = 100L

    /**
     * Tells every viewer the picture's size, and those that offered `codecs`
     * how pictures are being sent. Both ways of capturing call this where
     * they have always sent `screen-info`: when streaming starts, when a
     * viewer asks, and when the size changes. A viewer on an older app sizes
     * its window on every one, so it is sent no others.
     *
     * @param video whether what follows is H.264, if every viewer can draw it.
     * @param always false to tell only the viewers this is news to: those
     * that offered `codecs`, when the stream is not the one they were told of.
     * @return whether it was said; false when the gate has closed, and the
     * caller still owes it.
     */
    fun sendScreenInfo(context: Context, width: Int, height: Int, capture: String,
                       video: Boolean = false, always: Boolean = true): Boolean {
        if (!maySendFrames(context)) return false
        val to = recipients()
        synchronized(streamLock) {
            val word = settle(to, capture, video) ?: return false
            size = width to height
            for (viewer in to) tell(viewer, word, always)
            return true
        }
    }

    /** What each of [to] offered to draw. */
    private fun offers(to: List<String>) = to.map { viewers[it].orEmpty() }

    /**
     * Whether pictures would go as H.264 if the phone had it to send: there
     * is a viewer, and every viewer lists it. Whichever is capturing makes
     * video while this is so.
     */
    fun videoWanted(): Boolean = StreamFormat.of(offers(recipients()), video = true) == StreamFormat.H264

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
    fun send(context: Context, jpeg: ByteArray, width: Int, height: Int, capturedAt: Long, capture: String): Boolean =
        send(context, jpeg, width, height, capturedAt, capture, repeatOf = null)

    /** [repeatOf] is the picture this is, when it is one being sent again; null for a new picture. */
    private fun send(context: Context, jpeg: ByteArray, width: Int, height: Int, capturedAt: Long, capture: String,
                     repeatOf: Sent?): Boolean {
        if (!maySendFrames(context)) return false
        val to = recipients()
        synchronized(streamLock) {
            // A picture is sent again only while it is still the last one that went. Its stream
            // may be over, and then nobody is waiting for it. Or a newer picture went in the
            // instant before this, and the older one sent after it would be what the viewer is left with.
            if (repeatOf != null && last !== repeatOf) return false
            val word = settle(to, capture, video = false) ?: return false
            if (repeatOf != null && last !== repeatOf) return false
            size = width to height
            for (viewer in to) tell(viewer, word, always = false)
            val now = SystemClock.uptimeMillis()
            val sent = if (stream == 0L) ConnectionManager.sendBinary(Crypto.CH_VIDEO, jpeg, to) else {
                val header = PhoneFrame.Header(stream, nextSeq, keyframe = true, format = PhoneFrame.JPEG,
                    repeat = repeatOf != null, width = width, height = height, captureMs = capturedAt and 0xFFFFFFFFL)
                // A picture the queue refused was never sent, and takes no number.
                ConnectionManager.sendBinary(Crypto.CH_PHONE, PhoneFrame.build(header, jpeg), to).also {
                    if (!it) return@also
                    inFlight.sent(nextSeq, now, to, repeat = repeatOf != null)
                    last = Sent(stream, jpeg, width, height, capturedAt, capture)
                    nextSeq++
                }
            }
            if (sent) noteSent(now)
            scheduleResend(now)
            return sent
        }
    }

    /**
     * Sends one H.264 picture, a whole access unit, to whoever is viewing,
     * as a type 5 frame of the current stream. Unlike a JPEG it is never
     * held back for a full queue: the pictures after it are built on it, so
     * the time to hold back was before it was encoded ([hasRoom]).
     *
     * It is not sent when the gate is closed, or when some viewer cannot
     * draw H.264: then nobody is being sent video just now. Usually what
     * comes next is a new stream. But the gate can close and open again
     * before anyone has ended the stream (a device joins the session and is
     * recognised a moment later), and then the stream goes on, with a
     * picture missing that the next one was built on. So a stream that had a
     * picture go unsent may only go on with a keyframe, like one that has
     * just started and one whose picture the socket would not take; and
     * until a keyframe comes, nothing else of it is sent.
     *
     * @param repeat the same screen as the picture before.
     * @param resend it was made because the picture before went unacknowledged.
     * @param capturedAt when the screen was captured, on the uptime clock.
     * @param capture how the screen was captured: [SCREEN_SHARE] or [ACCESSIBILITY].
     */
    internal fun sendVideo(context: Context, unit: ByteArray, keyframe: Boolean, repeat: Boolean, resend: Boolean,
                           width: Int, height: Int, capturedAt: Long, capture: String = SCREEN_SHARE): VideoSent {
        val open = maySendFrames(context)
        val to = recipients()
        synchronized(streamLock) {
            if (!open || StreamFormat.of(offers(to), video = true) != StreamFormat.H264) {
                // No viewer counts a gap for it, since it takes no number. But if this stream
                // goes on, its next picture was built on this one.
                if (stream != 0L && codec == H264) needKey = true
                return VideoSent.NOT_SENT
            }
            val word = settle(to, capture, video = true) ?: return VideoSent.NOT_SENT
            size = width to height
            for (viewer in to) tell(viewer, word, always = false)
            if (needKey && !keyframe) return VideoSent.NEEDS_KEYFRAME
            val now = SystemClock.uptimeMillis()
            val header = PhoneFrame.Header(stream, nextSeq, keyframe, format = PhoneFrame.H264, repeat = repeat,
                width = width, height = height, captureMs = capturedAt and 0xFFFFFFFFL)
            if (!ConnectionManager.sendBinary(Crypto.CH_PHONE, PhoneFrame.build(header, unit), to, always = true)) {
                // It took no number, so no viewer counts a gap. But the next picture was built on it.
                needKey = true
                return VideoSent.NEEDS_KEYFRAME
            }
            needKey = false
            if (keyframe) {
                lastKeySeq = nextSeq
                lastKeyAt = now
            }
            inFlight.sent(nextSeq, now, to, repeat = resend)
            nextSeq++
            noteSent(now)
            scheduleResend(now)
            return VideoSent.SENT
        }
    }

    /**
     * `request-keyframe` from a device that is viewing: it lost a frame of
     * the H.264 stream, or its decoder failed. The next picture encoded is a
     * keyframe, at most one a second for all viewers together
     * ([VideoPacer]).
     *
     * Dropped when it names a stream other than the one being sent, since a
     * new stream starts with a keyframe anyway. Dropped too while a keyframe
     * sent in the last second has not been acknowledged by that viewer: the
     * request and the keyframe have crossed on the way.
     */
    fun onKeyframeRequest(peerId: String, msg: JSONObject) {
        val named = msg.optString("stream")
        synchronized(streamLock) {
            if (stream == 0L || codec != H264) return
            if (named.isNotEmpty() && PhoneFrame.parseStream(named) != stream) return
            val onItsWay = lastKeySeq >= 0 && (inFlight.ackedBy(peerId) ?: -1) < lastKeySeq &&
                SystemClock.uptimeMillis() - lastKeyAt < KEYFRAME_ON_ITS_WAY_MS
            if (onItsWay) return
        }
        askKeyframe(true)
    }

    /** Whether every viewer acknowledges what it receives, which is while a type 5 stream is running. */
    val acknowledged: Boolean get() = synchronized(streamLock) { stream != 0L }

    /**
     * Whether a picture sent now would be worth sending. The app's own queue
     * must be all but empty. And when every viewer acknowledges what it
     * receives, none of them may be behind ([InFlight]): the queue only shows
     * what has not left the app, and most of what waits on a slow upload
     * waits further down, where only an acknowledgement shows it.
     *
     * Both ways of capturing ask before they compress a picture, and keep
     * the newest picture for when there is room.
     */
    fun hasRoom(): Boolean {
        if (!ConnectionManager.hasRoomForVideo()) return false
        synchronized(streamLock) { return stream == 0L || inFlight.hasRoom(SystemClock.uptimeMillis()) }
    }

    /** Looks again when the newest picture would be due to be sent again, if it is still unacknowledged then. */
    private fun scheduleResend(now: Long) {
        main.removeCallbacks(resend)
        if (stream == 0L) return
        inFlight.resendIn(now)?.let { main.postDelayed(resend, it.coerceAtLeast(RESEND_CHECK_MS)) }
    }

    /**
     * The newest picture has gone unacknowledged for too long with nothing
     * sent after it: the relay may have dropped it for a viewer that was
     * backed up, and on a screen that has stopped changing nothing else
     * would ever replace it. It goes again, marked as a repeat.
     *
     * Only while there is room, though, as for any picture. A viewer that is
     * merely behind has not lost anything, and a second copy of an old
     * picture would be one more for it to work through before the next new
     * one. While there is no room this looks again shortly.
     */
    private val resend = Runnable {
        val context = appContext ?: return@Runnable
        val picture = synchronized(streamLock) {
            if (stream == 0L) return@Runnable
            val kept = last?.takeIf { it.stream == stream }
            if (kept == null && codec != H264) return@Runnable
            val now = SystemClock.uptimeMillis()
            if (!inFlight.resendDue(now) || !ConnectionManager.hasRoomForVideo() || !inFlight.hasRoom(now)) {
                scheduleResend(now)
                return@Runnable
            }
            kept
        }
        // No H.264 picture can be sent a second time: the screen as it is now is encoded again,
        // as a keyframe, since whatever came after a lost picture could not be drawn either.
        if (picture == null) askResend()
        else send(context, picture.jpeg, picture.width, picture.height, picture.capturedAt, picture.capture,
            repeatOf = picture)
    }

    /** The stream is over: its id is dead, and nothing of it is waited for or sent again. */
    private fun endStream() {
        stream = 0L
        inFlight.reset()
        last = null
        needKey = false
        lastKeySeq = -1
        main.removeCallbacks(resend)
    }

    /**
     * Chooses the format again for the viewers there are now, and starts or
     * ends the stream to match. A stream starts when pictures can first go as
     * type 5, and anew when the way the screen is captured changes or the
     * pictures change between JPEG and H.264; it ends when a viewer that
     * knows only type 1 is among them.
     *
     * @param video whether the sender has H.264 in hand. It still goes as
     * JPEG's stream, or none, if some viewer cannot draw it.
     * @return what viewers are to be told, or null when there is nobody to send to.
     */
    private fun settle(to: List<String>, capture: String, video: Boolean): String? {
        if (to.isEmpty()) return null
        val format = StreamFormat.of(offers(to), video)
        if (format == StreamFormat.TYPE_1) endStream()
        else if (stream == 0L || this.capture != capture || codec != format.codec) {
            endStream()
            stream = newStreamId()
            nextSeq = 0
            codec = format.codec ?: JPEG
            // An H.264 stream starts with a keyframe, whatever the encoder was in the middle of.
            needKey = format == StreamFormat.H264
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
        // Whether smooth sharing is on, or can be asked for: a viewer's menu row goes by it.
        val smooth = appContext?.let { SmoothPrompt.offer(it) }
        val key = "$word $capture $smooth"
        if (!always && (!offered || told[viewer] == key)) return
        // A viewer that comes to an H.264 stream part of the way through cannot draw
        // anything until a keyframe comes, so one is made for it.
        if (offered && told[viewer] != key && word != TYPE_1 && word != PAUSED && codec == H264 && nextSeq > 0)
            askKeyframe(false)
        if (offered) told[viewer] = key
        val msg = JSONObject().put("type", "screen-info")
        size?.let { (width, height) -> msg.put("width", width).put("height", height) }
        msg.put("typing", InputAccessibilityService.canType)
        if (offered) {
            capture?.let { msg.put("capture", it) }
            smooth?.let { msg.put("smooth", it) }
            when (word) {
                TYPE_1 -> Unit
                PAUSED -> msg.put("paused", true)
                else -> msg.put("stream", word).put("codec", codec)
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
        endStream()
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
        // The last viewer has gone: the stream ends with it.
        if (viewers.isEmpty()) endStream()
    }

    /**
     * `frame-ack` from a device that is viewing: the highest frame of the
     * current stream it has received. It is what the phone holds back by
     * ([hasRoom]), and what tells it a picture never arrived.
     */
    fun onFrameAck(peerId: String, msg: JSONObject) {
        val id = PhoneFrame.parseStream(msg.optString("stream")) ?: return
        val seq = msg.optLong("seq", -1)
        synchronized(streamLock) {
            if (stream == 0L || id != stream || seq < 0 || seq >= nextSeq) return
            val now = SystemClock.uptimeMillis()
            inFlight.acked(peerId, seq, now)
            scheduleResend(now)
        }
    }

    /** The highest frame of the current stream [peerId] has acknowledged, if any. */
    internal fun ackedSeq(peerId: String): Long? = synchronized(streamLock) { inFlight.ackedBy(peerId) }

    /**
     * `diagnostic-pong`, without its `to`: this phone's clock as the ping was
     * handled, which is the clock `captureMs` is read from, and what the
     * stream is doing.
     */
    fun pong(nonce: Long, queueBytes: Long): JSONObject = synchronized(streamLock) {
        val now = SystemClock.uptimeMillis()
        val lastSecond = noteSent()
        val video = stream != 0L && codec == H264
        JSONObject().put("type", "diagnostic-pong")
            .put("nonce", nonce)
            .put("hostTimeMs", now)
            .put("hostQueueBytes", queueBytes)
            .put("codec", if (video) H264 else JPEG)
            .put("capture", if (ScreenCaptureService.isRunning) SCREEN_SHARE else ACCESSIBILITY)
            .put("fps", lastSecond)
            .apply {
                if (video) (ScreenCaptureService.videoBitrate ?: InputAccessibilityService.instance?.videoBitrate)
                    ?.let { put("bitrate", it) }
            }
    }
}
