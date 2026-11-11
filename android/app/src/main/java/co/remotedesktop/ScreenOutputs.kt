package co.remotedesktop

/**
 * Where the shared screen is mirrored to, which is one place at a time:
 * - the reader that JPEGs are made from ([CaptureTarget]);
 * - the video encoder ([PhoneVideo], through [VideoLane]), while every
 *   viewer can draw H.264;
 * - nowhere, once nobody has been viewing for a few seconds. The encoder is
 *   let go of then too: the phone has few of them, and its camera may want one.
 *
 * And what follows from that: what the viewers are told ([announce]), and
 * what happens when video fails. However it fails (it would not start, it
 * stopped working, the display would not take it, its thread never came back
 * from the encoder), the same things happen, here, in this order:
 * 1. this try at video is marked as failed ([VideoWish]), so it is not made again;
 * 2. the connection history gets its line;
 * 3. the screen is mirrored into the reader, which makes Android draw it at once;
 * 4. the viewers are told of the JPEG stream that follows.
 *
 * So a viewer is never left without pictures. None of this waits on the
 * video's own thread for more than a moment.
 *
 * Generic over the reader so a JVM test can drive it. Call on one thread,
 * the capture thread, except where it says otherwise.
 */
internal class ScreenOutputs<R : Any>(
    private val target: CaptureTarget<R>,
    private val video: VideoLane,
    private val wish: VideoWish,
    private val display: Display<R>,
    private val host: Host,
) {
    interface Display<R> {
        /** Mirrors the screen into [reader], or nowhere for null. */
        fun toReader(reader: R?)

        /** Mirrors the screen into the encoder behind [pipe]. */
        fun toVideo(pipe: VideoPipe)
    }

    /** What the capture service does for this class. */
    interface Host {
        /** Whether pictures are going out now. Any thread. */
        val sending: Boolean

        /** The size the screen is mirrored at now. Any thread. */
        fun size(): Pair<Int, Int>

        /** Pictures waiting to be made into JPEGs are not wanted any more. */
        fun dropWaiting()

        /**
         * Tells the viewers the picture's size and whether it comes as video
         * ([PhoneScreenCapture.sendScreenInfo]). With [always] false, only
         * those it is news to.
         */
        fun announce(width: Int, height: Int, video: Boolean, always: Boolean)

        /** Video has failed: a line for the phone's connection history. */
        fun videoFailed(reason: String, detail: String?)

        /** Runs [task] on the capture thread after [delayMs]. */
        fun later(task: Runnable, delayMs: Long)
    }

    /** Whether the screen is mirrored at all. It is, from the start, until nobody has viewed for a while. */
    private var mirrorOn = true

    /** The try at video ([VideoWish.tried]) that the encoder was last started for. */
    private var tried = 0

    /** The size of the mirror that video was started for. */
    private var videoSource: Pair<Int, Int>? = null
    private var watching = false
    @Volatile private var closed = false

    /**
     * Whether pictures are to be H.264 now: every viewer can draw it, this
     * try at it has not failed, and video has not been given up. Any thread.
     */
    val asVideo: Boolean get() = wish.wanted && video.usable

    /** Starts or stops the screen being mirrored at all. */
    fun mirror(on: Boolean) {
        mirrorOn = on
        point()
    }

    /**
     * Mirrors the screen where it belongs now: into the encoder while
     * pictures are to be H.264, into the reader otherwise, nowhere while
     * nobody views. If video was wanted and cannot be had, [failed] happens.
     *
     * In a pause the screen stays mirrored where it was, so an encoder is
     * kept, with nothing drawn into it. Pauses are short and frequent (any
     * device joining the session makes one), and nothing costly is torn
     * down for them. Who is viewing is not looked at in a pause either.
     */
    fun point() {
        if (closed) return
        val (width, height) = host.size()
        tried = wish.tried()
        val wasVideo = video.running
        var failure = place(width, height)
        // Found out while asking the video's thread for something.
        if (failure == null && video.wedgedJustNow()) failure = WEDGED to null
        if (failure != null) failed(failure.first, failure.second)
        // An encoder that would only start at a smaller size: the viewers were told the full one.
        else if (video.running && !wasVideo && video.size != evenSize(width, height)) announce()
        watch()
    }

    private fun place(width: Int, height: Int): Pair<String, String?>? {
        var failure: Pair<String, String?>? = null
        val sending = host.sending
        val videoNow = if (sending) asVideo else video.running
        if (mirrorOn && videoNow && video.usable) {
            if (!video.running) failure = startVideo(width, height)
            if (video.running) {
                video.setSending(sending)
                return null
            }
        }
        stopVideo()
        if (target.draw(mirrorOn) { display.toReader(it) } && !mirrorOn) host.dropWaiting()
        return failure
    }

    private fun startVideo(width: Int, height: Int): Pair<String, String?>? {
        // The reader gives way first: the screen is mirrored into one thing at a time.
        if (target.draw(false) { display.toReader(null) }) host.dropWaiting()
        if (!video.start(width, height)) return if (video.wedgedJustNow()) WEDGED to null else video.lastFailure
        try {
            display.toVideo(checkNotNull(video.pipe))
        } catch (e: RuntimeException) {
            video.refused()
            return "The screen could not be shown to the video encoder." to e.toString()
        }
        videoSource = width to height
        return null
    }

    /**
     * Video has failed, for the reason given: it reported it itself
     * ([PhoneVideo]'s `onFailed`, passed on to this thread), or [point]
     * found it could not be had. Pictures go on as JPEGs.
     */
    fun failed(reason: String, detail: String?) {
        if (closed) return
        wish.failed(tried)
        host.videoFailed(reason, detail)
        // The reader takes over. Should a new try at video have begun meanwhile, that is made, and may fail too.
        point()
        announce()
    }

    /**
     * Tells the viewers how pictures are sent now, if any are being sent.
     * The capture thread says it when the format or the size changes, the
     * main thread when pictures start and when a viewer asks. Any thread.
     */
    fun announce(always: Boolean = true) {
        if (closed || !host.sending) return
        val (width, height) = host.size()
        val asVideo = asVideo
        val (sentWidth, sentHeight) = if (asVideo) video.size ?: evenSize(width, height) else width to height
        host.announce(sentWidth, sentHeight, asVideo, always)
    }

    /**
     * The screen is about to be mirrored at a new size, after a turn. An
     * encoder has one size for life, so video at the old size ends here; the
     * next [point] starts it again at the new one, and its first picture is a keyframe.
     *
     * @return whether video was running at another size, and has been stopped.
     */
    fun turning(width: Int, height: Int): Boolean {
        if (!video.running || videoSource == (width to height)) return false
        stopVideo()
        return true
    }

    private fun stopVideo() {
        if (!video.running) return
        try {
            display.toReader(null)
        } catch (_: RuntimeException) {
            // The display has gone already, with the sharing.
        }
        video.stop()
        videoSource = null
    }

    /**
     * Looks, for as long as an encoder is running, whether its thread is
     * still coming back from it. A draw that never returns is found here,
     * on this thread, which it does not hold up.
     */
    private fun watch() {
        if (watching || closed || !video.running) return
        watching = true
        host.later(watchTask, WATCH_MS)
    }

    private val watchTask = Runnable {
        watching = false
        if (closed) return@Runnable
        if (video.wedgedJustNow()) failed(WEDGED, null) else watch()
    }

    /** Sharing has stopped: the encoder is let go of, and none is started again. */
    fun close() {
        closed = true
        video.shutDown()
        videoSource = null
    }

    companion object {
        const val WEDGED = "The video encoder stopped answering."
        const val WATCH_MS = 500L

        /** The size H.264 is sent at for a screen mirrored at this size, unless the encoder wants it smaller. */
        fun evenSize(width: Int, height: Int) = VideoSetup.even(width) to VideoSetup.even(height)
    }
}
