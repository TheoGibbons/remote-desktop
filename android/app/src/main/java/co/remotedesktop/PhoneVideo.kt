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
    /** Not sent, and nothing is owed: nobody is being sent video just now. */
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
 * the caller then goes back to JPEG pictures. After [MAX_FAILURES] of them it
 * stays there ([gaveUp]) for as long as this object lives, which is one run
 * of the capture service.
 *
 * Counted as failure:
 * - no encoder starts, at any size;
 * - drawing throws, or the encoder reports an error;
 * - the screen gives no picture within [FIRST_PICTURE_MS] of being attached,
 *   while the phone's screen is on;
 * - three pictures or more go in and none comes out for [STALL_MS];
 * - a keyframe comes out with no parameter sets known;
 * - [KEY_TRIES] keyframes in a row are asked for and none is made.
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

    /** One encoder, from the moment it starts until it is closed. What a closed one still says is ignored. */
    private inner class Session : VideoPipe.Events {
        lateinit var pipe: VideoPipe
        val ledger = VideoLedger()
        @Volatile var parameterSets: ByteArray? = null
        /** Taken to send a picture and to end the session, so that nothing is sent once it has ended. */
        val gate = Any()
        var ended = false
        var sawPicture = false
        var lastKeyPtsUs = Long.MIN_VALUE
        /** Keyframes asked for in a row without one coming out. The encoder's thread. */
        var keyMisses = 0

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
            val entry = ledger.out(ptsUs, clock())
            val picture = AnnexB.picture(data, parameterSets)
            if (picture == null) {
                failed(IllegalStateException("A keyframe came with no parameter sets"))
                return
            }
            var missed = false
            if (picture.keyframe) keyMisses = 0
            else if (entry?.draw?.keyframe == true) {
                if (++keyMisses >= KEY_TRIES) {
                    failed(IllegalStateException("No keyframe was made in $KEY_TRIES tries"))
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
            // Asked for and not made, or made and not sent: it is asked for again.
            if (missed || result == VideoSent.NEEDS_KEYFRAME) timer.post { keyframeLost(this, ptsUs) }
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
    private var failures = 0
    /** Whether [watchTask] is due to run. */
    private var watching = false

    val running: Boolean get() = current != null

    /** The encoded picture's size, while video is running. Any thread. */
    val size: Pair<Int, Int>? get() = current?.pipe?.let { it.width to it.height }

    /** Bits a second the encoder aims at, while video is running. Any thread. */
    val bitrate: Int? get() = current?.pipe?.bitrate

    /** The encoder that is running, for the caller to point the screen at. */
    val pipe: VideoPipe? get() = current?.pipe

    /** Video has failed too often: pictures stay JPEGs from now on. Any thread. */
    @Volatile var gaveUp = false
        private set

    /** Whether video may be started: it has not failed too often, and the service is not ending. Capture thread. */
    val usable: Boolean get() = !shutDown && !gaveUp

    /** Why the last [start] did not start, in words for the connection history, and the raw error. */
    var lastFailure: Pair<String, String?> = "Video could not be started." to null
        private set

    private val tickTask = Runnable { tick() }
    private val watchTask = Runnable { watch() }

    /**
     * Starts encoding a screen mirrored at [sourceWidth] x [sourceHeight]:
     * with the first encoder and size that will start. The caller then points
     * the screen at [pipe].
     *
     * @return false when nothing started, which counts as a failure; [lastFailure] says why.
     */
    fun start(sourceWidth: Int, sourceHeight: Int): Boolean {
        stop()
        if (!usable) return false
        var error: Throwable? = null
        val attempts = try {
            VideoSetup.attempts(sourceWidth, sourceHeight, encoders())
        } catch (e: RuntimeException) {
            error = e
            emptyList()
        }
        for (attempt in attempts) {
            val session = Session()
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
        failed()
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

    /** A picture could not be sent, so the next one has to be a keyframe, unless one was drawn after it. */
    private fun keyframeLost(session: Session, ptsUs: Long) {
        if (current !== session || ptsUs < session.lastKeyPtsUs) return
        pacer.wantKeyframe()
        tick()
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
        failed()
        onFailed(reason, detail)
    }

    private fun failed() {
        if (++failures >= MAX_FAILURES) gaveUp = true
    }

    companion object {
        const val MAX_FAILURES = 2
        const val FIRST_PICTURE_MS = 3_000L
        const val STALL_MS = 2_000L
        const val KEY_TRIES = 8
    }
}
