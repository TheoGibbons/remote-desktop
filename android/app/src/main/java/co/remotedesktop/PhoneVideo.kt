package co.remotedesktop

/**
 * One picture of the screen that is handed to the encoder, where the screen
 * is not mirrored into it: a screenshot taken by the Accessibility service.
 * It belongs to whoever was handed it last, who closes it.
 */
internal interface ScreenPicture {
    fun close()
}

/**
 * What turns the screen into H.264: on a phone, a texture the screen is
 * mirrored into or its screenshots are drawn into, OpenGL to draw that, and
 * a hardware encoder ([PhoneVideoEncoder]). Behind an interface so that
 * everything decided about it ([PhoneVideo]) can be tested with a made-up one.
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

    /**
     * Puts [picture] into the texture, in place of what it held, as a
     * mirrored screen puts itself there. [Events.picture] says when it has
     * got there. The picture has to stay open until the next one is shown,
     * or this is closed. Throws when it cannot be drawn.
     */
    fun show(picture: ScreenPicture)

    /**
     * Has the encoder aim at [bitsPerSecond] from here on. No keyframe is
     * needed for it. Throws when the encoder will not take it.
     */
    fun setBitrate(bitsPerSecond: Int)

    /** Lets go of everything. Nothing is heard from it afterwards. */
    fun close()

    /**
     * Called from another thread when a call into this pipe has not come
     * back: lets go of the encoder under it, which may let the call return.
     * It may itself never return, so it is called on a thread nothing waits for.
     */
    fun abort()

    interface Events {
        /** The texture holds a new picture of the screen, captured at [capturedAtMs] (uptime). The video thread. */
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
 * The video side of showing this phone's screen, with everything that is
 * decided about it and nothing that touches Android: which encoder and size
 * to try, when a picture is encoded ([VideoPacer]), what an encoded picture
 * is sent as ([AnnexB]), and what counts as the encoder having failed.
 *
 * The screen reaches the encoder's texture in one of two ways. In smooth
 * sharing Android mirrors it there, and says when ([VideoPipe.Events.picture]).
 * In Accessibility viewing each screenshot is handed over ([show]) and drawn
 * there ([fed]). Everything after the texture is the same for both.
 *
 * A failure of any kind ends video and is reported once, through [onFailed];
 * the caller then goes back to JPEG pictures.
 *
 * Counted as failure:
 * - drawing throws, or the encoder reports an error;
 * - the screen gives no picture within [FIRST_PICTURE_MS] of being attached,
 *   while the phone's screen is on; or, where pictures are handed over, one
 *   that was handed over has not reached the texture after that long;
 * - three pictures or more go in and none comes out for [STALL_MS];
 * - a keyframe comes out with no parameter sets known;
 * - a keyframe is asked for [KEY_TRIES] times or more over [KEY_PATIENCE_MS]
 *   and none is made. It is asked for again [KEY_RETRY_MS] after an
 *   ordinary picture came out in its place;
 * - the encoder throws when it is given a new bitrate.
 *
 * **The bitrate**, for a mirrored screen and where [VideoSetup.BITRATE_STEPS]
 * has it so, is moved by [BitrateSteps]: down
 * when pictures are kept waiting for room second after second, and up again
 * when they are not. It goes by what this class sees: how long a picture
 * that was due waited for a viewer's acknowledgements, when a keyframe was
 * made, and how large the pictures were that came out. Waiting for the
 * app's own queue to empty does not count: that is a file going out, not a
 * slow link. A viewer's request for a keyframe counts
 * there too. The rate outlives an encoder: one started straight after
 * another (a turn, a failure) carries on where that one was. Screenshots
 * that are handed over keep the rate their encoder starts with: at 2.5 a
 * second there is too little to go by ([BitrateSteps] says why).
 *
 * **When video is tried again.** The picture is tried at full size, then at
 * three quarters, then at half ([VideoSetup]). An encoder that will not
 * start at a size is passed over at once, within one [start]. An encoder
 * that starts and then fails takes its size with it: the next [start] begins
 * at the next size down, since a size an encoder cannot really do is the
 * likeliest reason. A size that failed stays failed for as long as this
 * object lives, which is one run of the capture service: a phone whose
 * encoder cannot do full size should not stumble over it again every few
 * minutes. (The Accessibility service's object lives for weeks, so there a
 * failed size is tried again when video next starts [sizesForgottenAfterMs]
 * after the last failure: one bad moment should not leave the picture small
 * until the phone is restarted.) When no size is left, or nothing will start
 * at all, video is given up ([gaveUp]) and pictures stay JPEGs. But not for
 * good: [FORGET_MS] after the failure that did it, everything is forgotten
 * and full size is tried again. A phone may have been short of encoders for
 * a while (its camera in use), and the PC forgets its own failures after the
 * same time.
 *
 * **Threads.** This lives on a thread of its own, the video thread, which
 * [timer] runs things on, and every method is for that thread unless it says
 * otherwise. The encoder's callbacks come in on the encoder's thread
 * ([VideoPipe.Events]). The capture service talks to it through [VideoLane].
 *
 * It has its own thread because an encoder can stop answering without
 * saying so, and a call into it then never comes back. On the thread that
 * also makes the JPEGs and hears Android end the sharing, that would freeze
 * everything, with nothing left to fall back with. So every call into the
 * encoder is timed ([stuckFor]), and whoever finds one stuck gives this
 * object up for good ([abandon]) from its own thread, and carries on in JPEG.
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
    /** Video has ended by a failure, for the reason given. Called on the video thread. */
    private val onFailed: (reason: String, detail: String?) -> Unit,
    /** Whether the phone's own screen is on: a screen that is off gives no pictures, and that is no failure. */
    private val screenOn: () -> Boolean = { true },
    /** Runs something that may never return, on a thread nothing waits for. */
    private val spare: (() -> Unit) -> Unit = { task -> Thread({ task() }, "video-abort").apply { isDaemon = true }.start() },
    /**
     * Whether the screen's pictures are handed over one at a time ([show]),
     * and not mirrored into the texture by Android.
     */
    private val fed: Boolean = false,
    /** How long after the last failure a size that failed is tried again; by default never. */
    private val sizesForgottenAfterMs: Long = Long.MAX_VALUE,
    /**
     * Whether a viewer's acknowledgements are late ([InFlight]). It is one
     * of the two things [hasRoom] goes by, and the only one the bitrate goes
     * by: the other is the app's own queue, which a file fills.
     */
    private val viewersBehind: () -> Boolean = { !hasRoom() },
    /** Whether the app's own send queue is over its limit for pictures. */
    private val ownQueueFull: () -> Boolean = { false },
    /**
     * The clock [sizesForgottenAfterMs] is measured on. Where that is a day,
     * it has to be one that goes on while the phone sleeps.
     */
    private val longClock: () -> Long = clock,
    /** Whether the bitrate follows the link ([BitrateSteps]). Never for pictures that are handed over. */
    private val bitrateSteps: Boolean = VideoSetup.BITRATE_STEPS,
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
     * @param bitrate bits a second it is aiming at.
     */
    private inner class Session(val step: Int, @Volatile var bitrate: Int) : VideoPipe.Events {
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

        // Where pictures are handed over. The video thread.
        /** The picture the texture was last given, kept open for as long as it may be drawn from. */
        var shown: ScreenPicture? = null
        /** When the screen in that picture was captured. */
        var shownCapturedAt = 0L
        /** Since when a picture that was handed over has been on its way to the texture; 0 while none is. */
        var unseenSince = 0L

        override fun picture(capturedAtMs: Long) {
            if (current !== this) return
            sawPicture = true
            unseenSince = 0
            // A picture that was handed over is stamped by the drawing of it. When
            // the screen looked like that is what came with it.
            pacer.changed(if (fed) shownCapturedAt else capturedAtMs)
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
            steps?.encoded(picture.bytes.size)
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
    private val pacer = VideoPacer(
        safetyKeyMs = if (fed) VideoPacer.SAFETY_KEY_SCREENSHOTS_MS else VideoPacer.SAFETY_KEY_MS)
    private val steps: BitrateSteps? = if (fed || !bitrateSteps) null else BitrateSteps()
    private var sending = false
    @Volatile private var shutDown = false

    /** When the call into the encoder that is being made now began; 0 while none is. */
    @Volatile private var busySince = 0L
    @Volatile private var abandoned = false

    /** Makes one call into the encoder, timed so that another thread can see if it never comes back. */
    private inline fun <T> timed(call: () -> T): T {
        busySince = clock().coerceAtLeast(1)
        try {
            return call()
        } finally {
            busySince = 0
        }
    }

    /** How long the call into the encoder that is being made now has taken; 0 while none is. Any thread. */
    fun stuckFor(now: Long): Long = busySince.let { if (it == 0L) 0 else now - it }

    /**
     * A call into the encoder has not come back, and this object's thread
     * with it. Any thread but that one. Nothing more is sent, and nothing is
     * ever started again. The encoder is let go of from a spare thread, which
     * frees the stuck call if anything will; if the video thread does come
     * back it closes what it had open and does nothing else.
     */
    fun abandon() {
        if (abandoned) return
        abandoned = true
        current?.let { session ->
            synchronized(session.gate) { session.ended = true }
            spare {
                try {
                    session.pipe.abort()
                } catch (_: Exception) {
                    // It was worth trying.
                }
            }
        }
        timer.post { stop() }
    }
    private var lastPtsUs = 0L
    /** The first size still to be tried ([VideoSetup.Attempt.step]), and when video last failed. */
    @Volatile private var step = 0
    @Volatile private var failedAt = 0L
    @Volatile private var failedAtLong = 0L
    /** Whether [watchTask] is due to run. */
    private var watching = false

    /** Any thread. */
    val running: Boolean get() = current != null && !abandoned

    /** Whether this object was given up because its thread never came back from the encoder ([abandon]). Any thread. */
    val lost: Boolean get() = abandoned

    /** The encoded picture's size, while video is running. Any thread. */
    val size: Pair<Int, Int>? get() = current?.pipe?.let { it.width to it.height }

    /** Bits a second the encoder aims at now, while video is running. Any thread. */
    val bitrate: Int? get() = current?.bitrate

    /** The encoder that is running, for the caller to point the screen at. */
    val pipe: VideoPipe? get() = current?.pipe

    /**
     * Video has failed at every size, or would not start at all: pictures
     * stay JPEGs, until [FORGET_MS] after the last failure. Any thread.
     */
    val gaveUp: Boolean get() = step >= VideoSetup.STEPS && clock() - failedAt < FORGET_MS

    /** Whether video may be started: it has not been given up or abandoned, and the service is not ending. Any thread. */
    val usable: Boolean get() = !shutDown && !abandoned && !gaveUp

    /** Why the last [start] did not start, in words for the connection history, and the raw error. */
    @Volatile var lastFailure: Pair<String, String?> = "Video could not be started." to null
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
        // A size failed long ago: it may have been that moment's trouble and not the size's.
        else if (step > 0 && longClock() - failedAtLong >= sizesForgottenAfterMs) step = 0
        if (!usable) return false
        var error: Throwable? = null
        val attempts = try {
            VideoSetup.attempts(sourceWidth, sourceHeight, encoders(), fromStep = step)
        } catch (e: RuntimeException) {
            error = e
            emptyList()
        }
        for (attempt in attempts) {
            // Where the last encoder left the rate, if it ended a moment ago; else what the size starts at.
            val rate = steps?.begin(attempt.bitrate, clock()) ?: attempt.bitrate
            val session = Session(attempt.step, rate)
            try {
                session.pipe = timed { open(attempt.copy(bitrate = rate), sourceWidth, sourceHeight, session) }
            } catch (e: Exception) {
                error = e
                continue
            }
            if (abandoned) {
                // It took so long to start that it was given up meanwhile.
                closeQuietly(session)
                return false
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
        if (!on) steps?.paused()
        // What follows a pause is a new stream, and a stream starts with a keyframe.
        if (on && !abandoned) {
            // A mirrored screen went on reaching the texture through the pause. Screenshots
            // did not: none was taken, and the next one is on its way.
            if (fed) pacer.resumedStale() else pacer.resumed(clock())
            tick()
        }
    }

    /** A keyframe is wanted: [byViewer] when a viewer asked for it, and then at most one a second is made. */
    fun keyframe(byViewer: Boolean) {
        if (current == null || abandoned) return
        // Asked twice in a few seconds, frames are being lost on the way: the rate comes down.
        if (byViewer) steps?.keyframeAsked(clock())
        pacer.wantKeyframe(byViewer)
        tick()
    }

    /** The last picture went unacknowledged: the screen as it is now goes again, as a keyframe. */
    fun resend() {
        if (current == null || abandoned) return
        pacer.wantResend()
        tick()
    }

    /**
     * A picture of the screen, captured at [capturedAtMs] (uptime), for the
     * texture: it is encoded as a mirrored screen's change would be, when
     * its turn comes and there is room. The picture is this object's from
     * here on, and is closed when the next one has taken its place, or with
     * the encoder, or at once if no encoder is running.
     */
    fun show(picture: ScreenPicture, capturedAtMs: Long) {
        val session = current
        if (session == null || abandoned) {
            closeQuietly(picture)
            return
        }
        // Known before the texture can say it has the picture.
        session.shownCapturedAt = capturedAtMs
        if (session.unseenSince == 0L) session.unseenSince = clock()
        try {
            timed { session.pipe.show(picture) }
        } catch (e: Exception) {
            closeQuietly(picture)
            fail(session, "The video encoder stopped working.", e.toString())
            return
        }
        // The one before has been drawn over.
        session.shown?.let(::closeQuietly)
        session.shown = picture
        if (!watching) {
            watching = true
            timer.postDelayed(watchTask, FIRST_PICTURE_MS)
        }
    }

    /** Ends video and lets go of the encoder. Nothing of it is sent after this returns. */
    fun stop() {
        val session = current ?: return
        current = null
        timer.remove(tickTask)
        timer.remove(watchTask)
        watching = false
        synchronized(session.gate) { session.ended = true }
        closeQuietly(session)
        session.shown?.let(::closeQuietly)
        session.shown = null
        steps?.ended(clock())
    }

    private fun closeQuietly(picture: ScreenPicture) {
        try {
            picture.close()
        } catch (_: Exception) {
            // It is gone either way.
        }
    }

    private fun closeQuietly(session: Session) {
        try {
            timed { session.pipe.close() }
        } catch (_: Exception) {
            // It is gone either way.
        }
    }

    /**
     * The encoder started, and the caller could not put it to use: the
     * display would not be pointed at it. It is let go of, and that counts
     * as a failure like any other, so that it is not tried without end.
     */
    fun refused() {
        val session = current ?: return
        stop()
        failedAt(session.step + 1)
    }

    /** The capture service is ending: as [stop], and nothing starts again. */
    fun shutDown() {
        shutDown = true
        stop()
    }

    private fun tick() {
        val session = current ?: return
        timer.remove(tickTask)
        if (!sending || abandoned) return
        val now = clock()
        val room = hasRoom()
        if (steps != null) {
            steps.look(now, waiting = !room && pacer.due(now) && viewersBehind(), elsewhere = ownQueueFull())
            steps.step(now)
            if (!rate(session, steps.bitrate)) return
        }
        pacer.next(now, room)?.let { draw ->
            // Whatever it is made for, what waits behind it for a while is no sign of a slow link.
            if (draw.keyframe) steps?.keyframe(now)
            // Stamps only ever go up, whatever the clock the screen's pictures are stamped with does.
            val ptsUs = maxOf(draw.capturedAt * 1000, lastPtsUs + 1)
            lastPtsUs = ptsUs
            if (draw.keyframe) session.lastKeyPtsUs = ptsUs
            session.ledger.drawn(ptsUs, draw, now)
            try {
                timed { session.pipe.draw(ptsUs, draw.keyframe) }
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

    /**
     * Gives the encoder the rate [steps] has come to, if it is not the one
     * it has.
     * @return false when the encoder would not take it, which has ended video.
     */
    private fun rate(session: Session, wanted: Int): Boolean {
        if (wanted == session.bitrate) return true
        try {
            timed { session.pipe.setBitrate(wanted) }
        } catch (e: Exception) {
            fail(session, "The video encoder stopped working.", e.toString())
            return false
        }
        session.bitrate = wanted
        return true
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
        val now = clock()
        var again: Long? = null
        if (fed) {
            // Nothing is expected until a picture has been handed over; then it is.
            val since = session.unseenSince
            if (since != 0L) {
                if (now - since >= FIRST_PICTURE_MS)
                    return fail(session, "A picture of the screen did not reach the video encoder.", null)
                again = since + FIRST_PICTURE_MS - now
            }
        } else if (!session.sawPicture) {
            // A phone whose screen is off draws nothing, and that is as it should be.
            if (screenOn()) return fail(session, "The screen gave the video encoder no picture.", null)
            again = FIRST_PICTURE_MS
        }
        if (session.sawPicture) {
            if (session.ledger.stalled(now, STALL_MS))
                return fail(session, "The video encoder stopped giving pictures.", null)
            // Looked at again for as long as a picture that went in has not come out.
            if (session.ledger.owed()) again = minOf(again ?: STALL_MS, STALL_MS)
        }
        val wait = again ?: return
        watching = true
        timer.postDelayed(watchTask, wait.coerceAtLeast(1))
    }

    private fun fail(session: Session, reason: String, detail: String?) {
        if (current !== session) return
        stop()
        // Given up already, by whoever found it stuck, and reported by them.
        if (abandoned) return
        // It started at this size and failed at it: the next try is at the next size down.
        failedAt(session.step + 1)
        onFailed(reason, detail)
    }

    /** Video has failed just now, and [next] is the first size worth trying after it. */
    private fun failedAt(next: Int) {
        step = maxOf(step, next)
        failedAt = clock()
        failedAtLong = longClock()
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
