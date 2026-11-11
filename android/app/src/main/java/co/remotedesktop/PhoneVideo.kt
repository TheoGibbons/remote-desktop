package co.remotedesktop

/**
 * What turns the mirrored screen into H.264: on a phone, a texture the
 * screen is mirrored into, OpenGL to draw it, and a hardware encoder
 * ([PhoneVideoEncoder]). Behind an interface so that everything decided
 * about it ([PhoneVideo]) can be tested with a made-up one.
 */
internal interface VideoPipe {
    val width: Int
    val height: Int
    val bitrate: Int

    /**
     * Encodes the picture the texture holds now, stamped [ptsUs], asking for
     * a keyframe if [keyframe]. Throws when the encoder or OpenGL fails.
     */
    fun draw(ptsUs: Long, keyframe: Boolean)

    /** Lets go of everything. Nothing is heard from it afterwards. */
    fun close()

    interface Events {
        /** The texture holds a new picture of the screen, captured at [capturedAtMs] (uptime). The pipe's own thread. */
        fun picture(capturedAtMs: Long)

        /**
         * The encoder has produced something: its parameter sets if
         * [config], else one picture, with the stamp it was drawn with. The
         * encoder's thread.
         */
        fun encoded(data: ByteArray, ptsUs: Long, config: Boolean)

        /** The encoder or OpenGL has failed. Any thread. */
        fun failed(error: Throwable)
    }
}

/** What became of an encoded picture that was handed over to be sent. */
internal enum class VideoSent {
    SENT,
    /** Not sent, though video is still wanted: the next picture has to be a keyframe. */
    NEEDS_KEYFRAME,
    /**
     * Not sent: nobody is being sent video just now. Nothing is encoded to
     * make up for it, since there may be nobody to send that to either. But
     * the next picture that is encoded has to be a keyframe.
     */
    NOT_SENT,
}

/**
 * What was drawn into the encoder and has not come out yet, so that a
 * picture coming out can be told what it is (a repeat, a keyframe that was
 * asked for) and so that an encoder that has stopped answering is noticed.
 * Written on the drawing thread and read on the encoder's.
 */
internal class VideoLedger {
    class Entry(val ptsUs: Long, val draw: VideoPacer.Draw, val drawnAt: Long)

    private val waiting = ArrayDeque<Entry>()
    private var lastOutAt = Long.MIN_VALUE

    @Synchronized fun drawn(ptsUs: Long, draw: VideoPacer.Draw, now: Long) {
        waiting.addLast(Entry(ptsUs, draw, now))
        // An encoder that swallows pictures must not make this grow for ever.
        while (waiting.size > MAX_WAITING) waiting.removeFirst()
    }

    /** A picture stamped [ptsUs] came out. Returns what was drawn with that stamp, if it is known. */
    @Synchronized fun out(ptsUs: Long, now: Long): Entry? {
        lastOutAt = now
        var match: Entry? = null
        // Pictures come out in the order they went in; one the encoder skipped never comes.
        while (waiting.isNotEmpty() && waiting.first().ptsUs <= ptsUs) {
            val entry = waiting.removeFirst()
            if (entry.ptsUs == ptsUs) match = entry
        }
        return match
    }

    /** Whether anything that was drawn has yet to come out. */
    @Synchronized fun owed(): Boolean = waiting.isNotEmpty()

    /**
     * Whether the encoder has stopped answering: [atLeast] pictures have
     * gone in and not come out, the oldest of them [afterMs] ago or more,
     * and nothing at all has come out since that one went in.
     *
     * Not stalled: an encoder that keeps its newest picture until the next
     * one comes, since something came out after that picture went in. Nor
     * one that passes over a picture or two to keep to its bitrate. A still
     * screen is drawn three times (the picture, and twice to sharpen it), so
     * an encoder that has died shows even then.
     */
    @Synchronized fun stalled(now: Long, afterMs: Long, atLeast: Int = 3): Boolean {
        val oldest = waiting.firstOrNull() ?: return false
        return waiting.size >= atLeast && now - oldest.drawnAt >= afterMs && lastOutAt <= oldest.drawnAt
    }

    private companion object {
        const val MAX_WAITING = 120
    }
}

/**
 * The video side of smooth sharing, with everything that is decided about it
 * and nothing that touches Android: which encoder and size to try, when a
 * picture is encoded ([VideoPacer]), what an encoded picture is sent as
 * ([AnnexB]), and what counts as the encoder having failed.
 *
 * A failure of any kind ends video and is reported once, through [onFailed];
 * the caller then goes back to JPEG pictures.
 *
 * Counted as failure:
 * - drawing throws, or the encoder reports an error;
 * - the screen gives no picture within [FIRST_PICTURE_MS] of being attached,
 *   while the phone's screen is on;
 * - three pictures or more go in and none comes out for [STALL_MS];
 * - a keyframe comes out with no parameter sets known;
 * - a keyframe is asked for [KEY_TRIES] times or more over [KEY_PATIENCE_MS]
 *   and none is made. It is asked for again [KEY_RETRY_MS] after an
 *   ordinary picture came out in its place.
 *
 * **When video is tried again.** The picture is tried at full size, then at
 * three quarters, then at half ([VideoSetup]). An encoder that will not
 * start at a size is passed over at once, within one [start]. An encoder
 * that starts and then fails takes its size with it: the next [start] begins
 * at the next size down, since a size an encoder cannot really do is the
 * likeliest reason. A size that failed stays failed for as long as this
 * object lives, which is one run of the capture service: a phone whose
 * encoder cannot do full size should not stumble over it again every few
 * minutes. When no size is left, or nothing will start at all, video is
 * given up ([gaveUp]) and pictures stay JPEGs. But not for good: [FORGET_MS]
 * after the failure that did it, everything is forgotten and full size is
 * tried again. A phone may have been short of encoders for a while (its
 * camera in use), and the PC forgets its own failures after the same time.
 *
 * Every method is for the capture thread, which [timer] runs things on. The
 * encoder's callbacks come in on its own thread ([VideoPipe.Events]).
 */
internal class PhoneVideo(
    private val encoders: () -> List<VideoSetup.Encoder>,
    /** Makes and starts one encoder, drawing a source of the given size. Throws if it cannot. */
    private val open: (attempt: VideoSetup.Attempt, sourceWidth: Int, sourceHeight: Int,
                       events: VideoPipe.Events) -> VideoPipe,
    private val timer: Timer,
    private val clock: () -> Long,
    private val hasRoom: () -> Boolean,
    private val send: (Frame) -> VideoSent,
    /** Video has ended by a failure, for the reason given. Capture thread. */
    private val onFailed: (reason: String, detail: String?) -> Unit,
    /** Whether the phone's own screen is on: a screen that is off gives no pictures, and that is no failure. */
    private val screenOn: () -> Boolean = { true },
) {
    interface Timer {
        fun post(task: Runnable)
        fun postDelayed(task: Runnable, delayMs: Long)
        fun remove(task: Runnable)
    }

    /** One encoded picture, as it is to be sent. */
    class Frame(val bytes: ByteArray, val keyframe: Boolean, val repeat: Boolean, val resend: Boolean,
                val width: Int, val height: Int, val capturedAtMs: Long)

    /**
     * One encoder, from the moment it starts until it is closed. What a closed one still says is ignored.
     * @param step which of the sizes it encodes at (see [VideoSetup.Attempt.step]).
     */
    private inner class Session(val step: Int) : VideoPipe.Events {
        lateinit var pipe: VideoPipe
        val ledger = VideoLedger()
        @Volatile var parameterSets: ByteArray? = null
        /** Taken to send a picture and to end the session, so that nothing is sent once it has ended. */
        val gate = Any()
        var ended = false
        var sawPicture = false
        var lastKeyPtsUs = Long.MIN_VALUE
        /** Keyframes asked for in a row without one coming out, and when the first of them was. The encoder's thread. */
        var keyMisses = 0
        var keyMissedSince = 0L

        override fun picture(capturedAtMs: Long) {
            if (current !== this) return
            sawPicture = true
            pacer.changed(capturedAtMs)
            tick()
        }

        override fun encoded(data: ByteArray, ptsUs: Long, config: Boolean) {
            if (config) {
                AnnexB.parameterSets(data)?.let { parameterSets = it }
                return
            }
            val now = clock()
            val entry = ledger.out(ptsUs, now)
            val picture = AnnexB.picture(data, parameterSets)
            if (picture == null) {
                failed(IllegalStateException("A keyframe came with no parameter sets"))
                return
            }
            var missed = false
            if (picture.keyframe) keyMisses = 0
            else if (entry?.draw?.keyframe == true) {
                if (keyMisses++ == 0) keyMissedSince = now
                // By the clock as well as the count: a changing screen has it asked
                // for with every picture, and eight of those go by in a quarter of a second.
                if (keyMisses >= KEY_TRIES && now - keyMissedSince >= KEY_PATIENCE_MS) {
                    failed(IllegalStateException(
                        "No keyframe was made in $keyMisses tries over ${now - keyMissedSince} ms"))
                    return
                }
                missed = true
            }
            val result = synchronized(gate) {
                if (ended) return
                send(Frame(picture.bytes, picture.keyframe, repeat = entry?.draw?.repeat ?: false,
                    resend = entry?.draw?.resend ?: false, width = pipe.width, height = pipe.height,
                    capturedAtMs = ptsUs / 1000))
            }
            // Asked for and not made: it is asked for again, a little later.
            if (missed) timer.post { keyframeMissed(this) }
            // Made and not sent: asked for again at once.
            else if (result == VideoSent.NEEDS_KEYFRAME) timer.post { keyframeLost(this, ptsUs) }
            else if (result == VideoSent.NOT_SENT) timer.post { pictureUnsent(this, ptsUs) }
        }

        override fun failed(error: Throwable) {
            timer.post { fail(this, "The video encoder stopped working.", error.toString()) }
        }
    }

    @Volatile private var current: Session? = null
    private val pacer = VideoPacer()
    private var sending = false
    private var shutDown = false
    private var lastPtsUs = 0L
    /** The first size still to be tried ([VideoSetup.Attempt.step]), and when video last failed. */
    @Volatile private var step = 0
    @Volatile private var failedAt = 0L
    /** Whether [watchTask] is due to run. */
    private var watching = false

    val running: Boolean get() = current != null

    /** The encoded picture's size, while video is running. Any thread. */
    val size: Pair<Int, Int>? get() = current?.pipe?.let { it.width to it.height }

    /** Bits a second the encoder aims at, while video is running. Any thread. */
    val bitrate: Int? get() = current?.pipe?.bitrate

    /** The encoder that is running, for the caller to point the screen at. */
    val pipe: VideoPipe? get() = current?.pipe

    /**
     * Video has failed at every size, or would not start at all: pictures
     * stay JPEGs, until [FORGET_MS] after the last failure. Any thread.
     */
    val gaveUp: Boolean get() = step >= VideoSetup.STEPS && clock() - failedAt < FORGET_MS

    /** Whether video may be started: it has not been given up, and the service is not ending. Capture thread. */
    val usable: Boolean get() = !shutDown && !gaveUp

    /** Why the last [start] did not start, in words for the connection history, and the raw error. */
    var lastFailure: Pair<String, String?> = "Video could not be started." to null
        private set

    private val tickTask = Runnable { tick() }
    private val watchTask = Runnable { watch() }

    /**
     * Starts encoding a screen mirrored at [sourceWidth] x [sourceHeight]:
     * with the first encoder and size that will start, from the largest size
     * that has not failed. The caller then points the screen at [pipe].
     *
     * @return false when nothing started. Video is then given up for a
     * while ([gaveUp]); [lastFailure] says why.
     */
    fun start(sourceWidth: Int, sourceHeight: Int): Boolean {
        stop()
        // Given up long enough ago: everything is forgotten, and full size is tried again.
        if (step >= VideoSetup.STEPS && clock() - failedAt >= FORGET_MS) step = 0
        if (!usable) return false
        var error: Throwable? = null
        val attempts = try {
            VideoSetup.attempts(sourceWidth, sourceHeight, encoders(), fromStep = step)
        } catch (e: RuntimeException) {
            error = e
            emptyList()
        }
        for (attempt in attempts) {
            val session = Session(attempt.step)
            try {
                session.pipe = open(attempt, sourceWidth, sourceHeight, session)
            } catch (e: Exception) {
                error = e
                continue
            }
            current = session
            pacer.reset()
            lastPtsUs = 0
            watching = true
            timer.postDelayed(watchTask, FIRST_PICTURE_MS)
            return true
        }
        lastFailure = (if (attempts.isEmpty() && error == null) "This phone has no video encoder for its screen's size."
            else "The video encoder could not be started.") to error?.toString()
        // Nothing smaller is left to try.
        failedAt(VideoSetup.STEPS)
        return false
    }

    /** Whether pictures are being sent. While they are not the encoder is kept, and nothing is drawn into it. */
    fun setSending(on: Boolean) {
        if (sending == on) return
        sending = on
        timer.remove(tickTask)
        // What follows a pause is a new stream, and a stream starts with a keyframe.
        if (on) {
            pacer.resumed(clock())
            tick()
        }
    }

    /** A keyframe is wanted: [byViewer] when a viewer asked for it, and then at most one a second is made. */
    fun keyframe(byViewer: Boolean) {
        if (current == null) return
        pacer.wantKeyframe(byViewer)
        tick()
    }

    /** The last picture went unacknowledged: the screen as it is now goes again, as a keyframe. */
    fun resend() {
        if (current == null) return
        pacer.wantResend()
        tick()
    }

    /** Ends video and lets go of the encoder. Nothing of it is sent after this returns. */
    fun stop() {
        val session = current ?: return
        current = null
        timer.remove(tickTask)
        timer.remove(watchTask)
        watching = false
        synchronized(session.gate) { session.ended = true }
        try {
            session.pipe.close()
        } catch (_: Exception) {
            // It is gone either way.
        }
    }

    /** The capture service is ending: as [stop], and nothing starts again. */
    fun shutDown() {
        shutDown = true
        stop()
    }

    private fun tick() {
        val session = current ?: return
        timer.remove(tickTask)
        if (!sending) return
        val now = clock()
        val room = hasRoom()
        pacer.next(now, room)?.let { draw ->
            // Stamps only ever go up, whatever the clock the screen's pictures are stamped with does.
            val ptsUs = maxOf(draw.capturedAt * 1000, lastPtsUs + 1)
            lastPtsUs = ptsUs
            if (draw.keyframe) session.lastKeyPtsUs = ptsUs
            session.ledger.drawn(ptsUs, draw, now)
            try {
                session.pipe.draw(ptsUs, draw.keyframe)
            } catch (e: Exception) {
                fail(session, "The video encoder stopped working.", e.toString())
                return
            }
            if (!watching) {
                watching = true
                timer.postDelayed(watchTask, STALL_MS)
            }
        }
        pacer.delay(clock(), room)?.let { timer.postDelayed(tickTask, it) }
    }

    /** A keyframe was asked for and an ordinary picture came out: it is asked for again, a little later. */
    private fun keyframeMissed(session: Session) {
        if (current !== session) return
        pacer.wantKeyframeFrom(clock() + KEY_RETRY_MS)
        tick()
    }

    /** A picture could not be sent, so the next one has to be a keyframe, unless one was drawn after it. */
    private fun keyframeLost(session: Session, ptsUs: Long) {
        if (current !== session || ptsUs < session.lastKeyPtsUs) return
        pacer.wantKeyframe()
        tick()
    }

    /**
     * A picture went unsent because nobody was being sent video at that
     * moment. Whatever is drawn next was built on it, so that is drawn as a
     * keyframe, unless a keyframe was drawn after the unsent picture anyway.
     */
    private fun pictureUnsent(session: Session, ptsUs: Long) {
        if (current !== session || ptsUs < session.lastKeyPtsUs) return
        pacer.keyframeNext()
    }

    /** Looks whether the screen and the encoder are still giving what they should. */
    private fun watch() {
        watching = false
        val session = current ?: return
        val again = if (!session.sawPicture) {
            // A phone whose screen is off draws nothing, and that is as it should be.
            if (screenOn()) return fail(session, "The screen gave the video encoder no picture.", null)
            FIRST_PICTURE_MS
        } else {
            if (session.ledger.stalled(clock(), STALL_MS))
                return fail(session, "The video encoder stopped giving pictures.", null)
            // Looked at again for as long as a picture that went in has not come out.
            if (!session.ledger.owed()) return
            STALL_MS
        }
        watching = true
        timer.postDelayed(watchTask, again)
    }

    private fun fail(session: Session, reason: String, detail: String?) {
        if (current !== session) return
        stop()
        // It started at this size and failed at it: the next try is at the next size down.
        failedAt(session.step + 1)
        onFailed(reason, detail)
    }

    /** Video has failed just now, and [next] is the first size worth trying after it. */
    private fun failedAt(next: Int) {
        step = maxOf(step, next)
        failedAt = clock()
    }

    companion object {
        const val FIRST_PICTURE_MS = 3_000L
        const val STALL_MS = 2_000L
        const val KEY_TRIES = 8
        const val KEY_PATIENCE_MS = 2_000L
        const val KEY_RETRY_MS = 250L

        /** How long after its last failure video is tried from the top again. The PC's rule is the same. */
        const val FORGET_MS = 10 * 60_000L
    }
}
