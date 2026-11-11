package co.remotedesktop

/**
 * Where the shared screen is mirrored to, which is one place at a time:
 * - the reader that JPEGs are made from ([CaptureTarget]);
 * - the video encoder ([PhoneVideo]), while every viewer can draw H.264;
 * - nowhere, once nobody has been viewing for a few seconds. The encoder is
 *   let go of then too: the phone has few of them, and its camera may want one.
 *
 * Video that will not start leaves the screen mirrored into the reader, so
 * a viewer is never left without pictures.
 *
 * Generic over the reader so a JVM test can drive it. Call on one thread,
 * the capture thread.
 */
internal class ScreenOutputs<R : Any>(
    private val target: CaptureTarget<R>,
    private val video: PhoneVideo,
    private val display: Display<R>,
    /** Pictures waiting to be made into JPEGs are not wanted any more. */
    private val dropWaiting: () -> Unit,
) {
    interface Display<R> {
        /** Mirrors the screen into [reader], or nowhere for null. */
        fun toReader(reader: R?)

        /** Mirrors the screen into the encoder behind [pipe]. */
        fun toVideo(pipe: VideoPipe)
    }

    /** The size of the mirror that video was started for. */
    private var videoSource: Pair<Int, Int>? = null

    /**
     * Mirrors the screen where it belongs now.
     *
     * @param mirror whether it is to be mirrored at all.
     * @param wantVideo whether H.264 is what the viewers are to get. It only
     * counts while [sending]: who is viewing is not looked at in a pause.
     * @param sending whether pictures are going out. In a pause the screen
     * stays mirrored where it was, so an encoder is kept, with nothing drawn
     * into it. Pauses are short and frequent (any device joining the session
     * makes one), and nothing costly is torn down for them.
     * @param width the size the screen is mirrored at.
     * @return null, or why video was wanted and could not be started. The
     * screen is then mirrored into the reader.
     */
    fun point(mirror: Boolean, wantVideo: Boolean, sending: Boolean, width: Int, height: Int): Pair<String, String?>? {
        var failure: Pair<String, String?>? = null
        val videoNow = if (sending) wantVideo else video.running
        if (mirror && videoNow && video.usable) {
            if (!video.running) failure = startVideo(width, height)
            if (video.running) {
                video.setSending(sending)
                return null
            }
        }
        stopVideo()
        if (target.draw(mirror) { display.toReader(it) } && !mirror) dropWaiting()
        return failure
    }

    private fun startVideo(width: Int, height: Int): Pair<String, String?>? {
        // The reader gives way first: the screen is mirrored into one thing at a time.
        if (target.draw(false) { display.toReader(null) }) dropWaiting()
        if (!video.start(width, height)) return video.lastFailure
        try {
            display.toVideo(checkNotNull(video.pipe))
        } catch (e: RuntimeException) {
            video.stop()
            return "The screen could not be shown to the video encoder." to e.toString()
        }
        videoSource = width to height
        return null
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

    /** Sharing has stopped: the encoder is let go of, and none is started again. */
    fun close() {
        video.shutDown()
        videoSource = null
    }
}
