package co.remotedesktop

import org.json.JSONObject

/**
 * The viewer's side of a phone's pictures: which of the frames that arrive
 * are that phone's, and may be drawn.
 *
 * A binary frame names no sender, and anyone holding the session key can make
 * one. So a phone on a current app picks a random id for its stream and tells
 * each viewer in a `screen-info` addressed to it, and from then on this
 * viewer draws only type 5 frames that carry the id, and no type 1 frames at
 * all. A phone on an older app announces nothing and its type 1 frames are
 * drawn as they always were.
 *
 * Fed in the order things arrive: [announce] and [take] are called on the
 * thread that reads the socket. PhoneVideoStream.cs is the same for the PC.
 */
internal class PhoneVideoStream {
    enum class Verdict {
        /** Of the current stream, and a picture this viewer can draw. */
        DRAW,
        /** Of the current stream, in a format this viewer did not offer. It arrived, and is not drawn. */
        SKIP,
        /** Not of the current stream, or one that has been seen already. */
        REFUSE,
    }

    private var stream: Long? = null
    private var paused = false
    private var lastSeq: Long? = null

    /** Frames of the current stream that never arrived, by the numbers of those that did. */
    var gaps = 0L
        @Synchronized get
        private set

    /** The current stream's id, or null while the phone has announced none. */
    val current: Long? @Synchronized get() = stream

    /** Whether a type 1 frame may be drawn: not while a stream is announced,
     *  and not while the phone has said it is sending nothing. */
    val drawsType1: Boolean @Synchronized get() = stream == null && !paused

    /**
     * What a `screen-info` from the phone being viewed said: its `stream`,
     * if it had one, and whether it said `paused`. The caller has checked
     * that it is from that phone, and that this device has approved it and
     * offered it `codecs`.
     */
    @Synchronized
    fun announce(streamHex: String?, paused: Boolean) {
        val id = PhoneFrame.parseStream(streamHex)
        if (id != stream) lastSeq = null
        stream = id
        this.paused = id == null && paused
    }

    /** A type 5 frame arrived. */
    @Synchronized
    fun take(header: PhoneFrame.Header): Verdict {
        if (header.stream != stream) return Verdict.REFUSE
        lastSeq?.let { last ->
            // A number seen before is a frame played back, whoever by.
            if (header.seq <= last) return Verdict.REFUSE
            gaps += header.seq - last - 1
        }
        lastSeq = header.seq
        return if (header.format == PhoneFrame.JPEG) Verdict.DRAW else Verdict.SKIP
    }

    /** Viewing stopped, or nothing was offered: whatever was announced is over. */
    @Synchronized
    fun forget() {
        stream = null
        paused = false
        lastSeq = null
        gaps = 0
    }
}

/**
 * Paces `frame-ack`: the viewer tells the phone the highest frame number that
 * has arrived, at most once every [minGapMs]. A frame that arrives sooner
 * than that after the last acknowledgement is still acknowledged, a moment
 * later ([due]), because the phone holds back on what it has not heard about.
 */
internal class AckPacer(private val minGapMs: Long = 30) {
    data class Ack(val stream: Long, val seq: Long) {
        /** `frame-ack`, without its `to`. */
        fun message(): JSONObject = JSONObject().put("type", "frame-ack")
            .put("stream", PhoneFrame.streamHex(stream)).put("seq", seq)
    }

    private var sentAt: Long? = null
    private var owed: Ack? = null

    /** A frame of the current stream arrived. The acknowledgement to send
     *  now, or null when one went out too recently and this one is owed. */
    @Synchronized
    fun received(stream: Long, seq: Long, nowMs: Long): Ack? {
        owed = Ack(stream, seq)
        return due(nowMs)
    }

    /** The acknowledgement that is owed, once it may go. */
    @Synchronized
    fun due(nowMs: Long): Ack? {
        val ack = owed ?: return null
        sentAt?.let { if (nowMs - it < minGapMs) return null }
        owed = null
        sentAt = nowMs
        return ack
    }

    /** How long until [due] has something to send, or null when nothing is owed. */
    @Synchronized
    fun dueIn(nowMs: Long): Long? {
        if (owed == null) return null
        return ((sentAt ?: return 0) + minGapMs - nowMs).coerceAtLeast(0)
    }

    @Synchronized
    fun forget() {
        sentAt = null
        owed = null
    }
}
