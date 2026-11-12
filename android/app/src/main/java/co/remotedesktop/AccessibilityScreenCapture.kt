package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the video encoder needs of the phone it runs on: the phone's
 * encoders, a way to make one, and a thread of its own with a clock. A test
 * brings made-up ones.
 */
internal class VideoParts(
    val encoders: () -> List<VideoSetup.Encoder>,
    val open: (attempt: VideoSetup.Attempt, sourceWidth: Int, sourceHeight: Int, events: VideoPipe.Events) -> VideoPipe,
    /** Runs things on the video's thread. */
    val timer: PhoneVideo.Timer,
    /** Runs one thing on the video's thread, for a caller on another thread that may wait for it ([VideoLane]). */
    val post: (Runnable) -> Unit = timer::post,
    val clock: () -> Long,
    /** A clock that goes on while the phone sleeps, for what is measured in days. */
    val longClock: () -> Long = clock,
    /** Ends the video's thread. What is asked of it afterwards is done there and then. */
    val quit: () -> Unit,
) {
    companion object {
        /**
         * The real ones. The thread is started when something is first
         * asked of it, so a phone nobody views as video never has one.
         */
        fun onThisPhone(): VideoParts {
            val own = object {
                private var thread: HandlerThread? = null
                private var handler: Handler? = null
                private var ended = false

                @Synchronized fun handler(start: Boolean = true): Handler? {
                    if (ended) return null
                    if (handler == null && start) {
                        val made = HandlerThread("video").also { it.start() }
                        thread = made
                        handler = Handler(made.looper)
                    }
                    return handler
                }

                @Synchronized fun quit() {
                    ended = true
                    thread?.quitSafely()
                }
            }
            return VideoParts(
                encoders = PhoneVideoEncoder::encoders,
                open = { attempt, width, height, events ->
                    PhoneVideoEncoder(attempt, width, height, checkNotNull(own.handler()), events)
                },
                timer = object : PhoneVideo.Timer {
                    override fun post(task: Runnable) {
                        if (own.handler()?.post(task) != true) task.run()
                    }

                    override fun postDelayed(task: Runnable, delayMs: Long) {
                        own.handler()?.postDelayed(task, delayMs)
                    }

                    override fun remove(task: Runnable) {
                        own.handler(start = false)?.removeCallbacks(task)
                    }
                },
                clock = SystemClock::uptimeMillis,
                longClock = SystemClock::elapsedRealtime,
                quit = own::quit)
        }
    }
}

/**
 * One screenshot at a time; stays below Android's 333 ms screenshot rate limit.
 *
 * A screenshot leaves as a JPEG, or, while every viewer can draw H.264, goes
 * through the video encoder ([PhoneVideo], as in smooth sharing): it is drawn
 * onto the encoder's texture and encoded from there, which costs the phone
 * less than compressing a JPEG and sends a fraction of the data. The rate is
 * the same, since Android sets it.
 *
 * [ScreenOutputs] decides which of the two it is, and does everything that
 * follows when the encoder cannot be started or fails: pictures go on as
 * JPEGs, and the connection history says why. The rules for trying video
 * again are smooth sharing's ([PhoneVideo]). Nothing is mirrored here, so
 * what it is told to point the screen at comes to nothing; the screenshots
 * are handed to the encoder one by one ([VideoLane.show]).
 *
 * Threads: requests are made and timed on the main thread. Screenshots
 * arrive on the worker thread, which compares each with the last, makes the
 * JPEGs, and is the "capture thread" of [ScreenOutputs]. The encoder has a
 * third thread to itself, which is not started until it is first wanted.
 *
 * While nobody views, no screenshot is taken and nothing is kept: the
 * encoder is let go of five seconds after pictures stop, and at once when
 * smooth sharing takes over.
 */
@RequiresApi(30)
internal class AccessibilityScreenCapture(
    private val service: AccessibilityService,
    /** Makes what the encoder needs of the phone. Called again if a video has to be made anew. */
    private val makeParts: () -> VideoParts = VideoParts::onThisPhone,
    /**
     * Makes a bitmap over the graphics memory a screenshot comes in, or
     * null where Android will not. A test, which has no graphics memory,
     * brings its own.
     */
    private val wrap: (HardwareBuffer, ColorSpace) -> Bitmap? = { buffer, colours ->
        Bitmap.wrapHardwareBuffer(buffer, colours)
    },
) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("accessibility-capture").also { it.start() }
    private val worker = Handler(thread.looper)
    // Late callbacks must still run their cleanup after the service has been destroyed.
    private val executor = Executor { command -> if (!worker.post(command)) command.run() }
    @Volatile private var streaming = false
    @Volatile private var generation = 0L
    private var closed = false
    private var inFlight = false // main thread only
    private var lastRequestAt = -400L
    private var sentSize: Pair<Int, Int>? = null // worker thread only
    // Worker thread only: the picture last sent and when, to tell a screen that has not changed.
    private var lastSent: Bitmap? = null
    private var lastSentAt = 0L
    private val infoRequested = AtomicBoolean(true)

    // Whether pictures are to be H.264, as [setStreaming] last found the viewers.
    private val wish = VideoWish()

    /** The size screenshots are sent at, as the last one was: what the encoder is started for. */
    @Volatile private var source: Pair<Int, Int>? = null

    /** Whether the last `screen-info` asked of [outputs] was said. Worker thread only. */
    private var said = false

    /**
     * The video encoder's side of things, made as one: the video, this
     * thread's hold on it, and what decides between it and JPEGs. It is made
     * anew, thread and all, when the one before was lost to an encoder that
     * stopped answering and that has since worn off ([VideoWedge]).
     */
    private inner class Encoding(val parts: VideoParts) {
        val video = PhoneVideo(
            encoders = parts.encoders,
            open = parts.open,
            timer = parts.timer,
            clock = parts.clock,
            hasRoom = PhoneScreenCapture::hasRoom,
            send = { frame ->
                // Smooth sharing may have taken over since this picture went in. Its stream is
                // the one that is running then, and a picture of this one would end it.
                if (!sending) VideoSent.NOT_SENT
                else PhoneScreenCapture.sendVideo(service, frame.bytes, frame.keyframe, frame.repeat, frame.resend,
                    frame.width, frame.height, frame.capturedAtMs, PhoneScreenCapture.ACCESSIBILITY)
            },
            // It says so on its own thread; what follows is the worker's to do.
            onFailed = { reason, detail -> executor.execute { if (encoding === this@Encoding) outputs.failed(reason, detail) } },
            fed = true,
            sizesForgottenAfterMs = SIZES_FORGOTTEN_MS,
            longClock = parts.longClock,
        )
        val lane = VideoLane(video, post = parts.post, clock = parts.clock,
            wedge = wedged)
        val outputs: ScreenOutputs<Unit> = ScreenOutputs(
            // There is no reader and no mirror: a screenshot is handed to whichever of the two is in use.
            CaptureTarget({ _, _ -> }, { }), lane, wish,
            object : ScreenOutputs.Display<Unit> {
                override fun toReader(reader: Unit?) = Unit
                override fun toVideo(pipe: VideoPipe) = Unit
            },
            object : ScreenOutputs.Host {
                override val sending: Boolean get() = this@AccessibilityScreenCapture.sending
                override fun size(): Pair<Int, Int> = source ?: (0 to 0)
                override fun dropWaiting() = Unit

                override fun announce(width: Int, height: Int, video: Boolean, always: Boolean) {
                    said = PhoneScreenCapture.sendScreenInfo(service, width, height, PhoneScreenCapture.ACCESSIBILITY,
                        video, always)
                }

                override fun videoFailed(reason: String, detail: String?) {
                    Log.w("AccessibilityCapture", "Video failed: $reason ${detail.orEmpty()}")
                    ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.VIDEO, System.currentTimeMillis(), reason, detail))
                    // The viewers' last picture was video, and may never have arrived. Nothing here
                    // makes Android draw the screen again, so the next screenshot goes as a JPEG
                    // whatever it shows.
                    forgetSent()
                }

                override fun later(task: Runnable, delayMs: Long) {
                    worker.postDelayed(task, delayMs)
                }
            })
    }

    @Volatile private var encoding = Encoding(makeParts())

    /** Whether this is the phone's source of pictures now: it is asked for them, and smooth sharing is not on. */
    private val sending: Boolean get() = streaming && !ScreenCaptureService.isRunning

    // Worker thread. A pause in the pictures: the encoder, if there is one, is kept, with nothing drawn into it.
    private val pause = Runnable {
        // Not when smooth sharing has taken over. It has an encoder of its own, and a phone has few.
        if (ScreenCaptureService.isRunning) encoding.outputs.mirror(false) else encoding.outputs.point()
    }
    // Worker thread. Nobody has been viewing for a while: the encoder is let go of.
    private val idle = Runnable { if (!streaming) encoding.outputs.mirror(false) }

    fun resendScreenInfo() { infoRequested.set(true) }

    /** The next H.264 picture should be a keyframe: [byViewer] when a viewer asked. Any thread. */
    fun keyframeWanted(byViewer: Boolean) = encoding.lane.keyframe(byViewer)

    /** The last H.264 picture went unacknowledged: the screen goes again, as a keyframe. Any thread. */
    fun resendWanted() = encoding.lane.resend()

    /** Bits a second the video encoder is aiming at, while it runs. */
    val videoBitrate: Int? get() = encoding.lane.bitrate

    /**
     * Smooth sharing is about to start an encoder: this one is let go of
     * first, and is gone when this returns. A phone may have only one to
     * give. Any thread but the main one, since it waits for the encoder.
     */
    fun endVideo() {
        val lane = encoding.lane
        if (lane.running) lane.stop()
    }

    private fun isCurrent(token: Long): Boolean =
        streaming && generation == token && !ScreenCaptureService.isRunning &&
            PhoneScreenCapture.maySendFrames(service)

    /**
     * Pictures are to go out, or to stop. Called again whenever the viewers
     * change, since that is also when H.264 may become what they all can
     * draw, or stop being it.
     */
    fun setStreaming(on: Boolean) {
        if (closed) return
        val change = wish.update(on, on && PhoneScreenCapture.videoWanted())
        if (!change.any) return
        if (change.started || change.stopped) {
            streaming = on
            generation++
            main.removeCallbacks(request)
        }
        worker.removeCallbacks(idle)
        if (on) {
            if (change.started) schedule()
            // Only the format has changed, under a screen that may not have. The
            // next screenshot goes in the new one whatever it shows.
            else worker.post(::forgetSent)
        } else {
            // The next picture goes whatever it shows, and this one need not be kept meanwhile.
            worker.post(::forgetSent)
            worker.post(pause)
            worker.postDelayed(idle, IDLE_MS)
        }
    }

    private fun forgetSent() {
        lastSent?.recycle()
        lastSent = null
    }

    fun close() {
        setStreaming(false)
        worker.post(::forgetSent)
        closed = true
        // The encoder is let go of from the thread that asks things of it, and its own thread then ends.
        worker.post {
            encoding.outputs.close()
            encoding.parts.quit()
        }
        thread.quitSafely()
    }

    private fun schedule(delayMs: Long = 0) {
        if (!streaming || closed || inFlight) return
        main.removeCallbacks(request)
        val rateDelay = (400L - (SystemClock.uptimeMillis() - lastRequestAt)).coerceAtLeast(0)
        main.postDelayed(request, maxOf(delayMs, rateDelay))
    }

    private val request = Runnable {
        val token = generation
        if (isCurrent(token) && !inFlight) {
            inFlight = true
            lastRequestAt = SystemClock.uptimeMillis()
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    var hardware: Bitmap? = null
                    var bitmap: Bitmap? = null
                    // Whether the screenshot itself went to the video encoder, which then closes it.
                    var taken = false
                    try {
                        if (!isCurrent(token)) return
                        hardware = wrap(buffer, result.colorSpace)
                        bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        // Android stamps the screenshot on the uptime clock.
                        if (hardware != null && bitmap != null)
                            taken = send(bitmap, token, PhoneFrame.captureTime(result.timestamp, SystemClock.uptimeMillis()),
                                BitmapPicture(hardware, buffer))
                    } catch (e: RuntimeException) {
                        Log.w("AccessibilityCapture", "Could not encode screenshot", e)
                    } finally {
                        // Not the one that is kept to compare the next with.
                        if (bitmap !== lastSent) bitmap?.recycle()
                        if (!taken) {
                            hardware?.recycle()
                            buffer.close()
                        }
                        complete()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    try {
                        // Replace a stale picture when the display is off or capture is blocked.
                        if (isCurrent(token) && errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            sendUnavailable(token)
                        }
                    } catch (e: RuntimeException) {
                        Log.w("AccessibilityCapture", "Could not send capture status", e)
                    } finally {
                        complete(1_000)
                    }
                }
            }
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
            } catch (_: RuntimeException) {
                // E.g. Accessibility was disabled between the check and the platform call.
                complete(1_000)
            }
        }
    }

    private fun complete(delayMs: Long = 0) {
        main.post {
            inFlight = false
            schedule(delayMs)
        }
    }

    /** The size of the last screenshot, which is the screen's own. Worker thread only. */
    private var screenSize: Pair<Int, Int>? = null

    private fun sendUnavailable(token: Long) {
        val metrics = service.resources.displayMetrics
        // The size screenshots come at, where one has been seen. What the service is told of
        // its display can leave out the navigation bar, and a picture of another shape would
        // have the viewer's window change shape, and the video encoder be made anew, for
        // as long as the screen is off.
        val (width, height) = screenSize ?: (metrics.widthPixels to metrics.heightPixels)
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, metrics)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Screen unavailable", bitmap.width / 2f, bitmap.height / 2f, paint)
            send(bitmap, token, SystemClock.uptimeMillis())
        } finally {
            if (bitmap !== lastSent) bitmap.recycle()
        }
    }

    private var sentGeneration = -1L

    /**
     * Sends a picture this app holds in its own memory: one it drew itself,
     * or, in a test, a screenshot. The caller must not recycle [lastSent].
     */
    private fun send(bitmap: Bitmap, token: Long, capturedAt: Long) {
        send(bitmap, token, capturedAt, shot = null)
    }

    /**
     * Sends a screenshot, as video or as a JPEG, unless it shows nothing
     * new. [capturedAt] is when the screen was captured, on the uptime
     * clock. A bitmap that is sent is kept, to compare the next one with:
     * the caller must not recycle [lastSent].
     *
     * @param bitmap the screenshot in the app's own memory, which is what is
     * compared and what a JPEG is made from.
     * @param shot the same screenshot as Android handed it over, in graphics
     * memory, which is what the video encoder draws. Null for a picture
     * that exists only as [bitmap].
     * @return whether [shot] went to the encoder. It is the encoder's to
     * close then, and the caller's otherwise.
     */
    private fun send(bitmap: Bitmap, token: Long, capturedAt: Long, shot: BitmapPicture?): Boolean {
        if (!isCurrent(token)) return false
        screenSize = bitmap.width to bitmap.height
        val (width, height) = StreamSize.of(bitmap.width, bitmap.height)
        if (videoFor(width to height)) return sendVideo(bitmap, token, capturedAt, width to height, shot)
        sendJpeg(bitmap, token, capturedAt, width, height)
        return false
    }

    /**
     * Has the encoder running if pictures of [size] are to be H.264 now,
     * and not running if they are not. Each screenshot asks, so whatever
     * has changed since the last one (the viewers, the phone's shape, an
     * encoder that failed or was let go of while nobody viewed) is put
     * right before this one goes.
     *
     * @return whether this screenshot goes to the encoder. If video was
     * wanted and could not be had, the viewers have been told of the JPEG
     * stream by now ([ScreenOutputs.failed]).
     */
    private fun videoFor(size: Pair<Int, Int>): Boolean {
        if (encoding.video.lost && !wedged.get()) {
            // An encoder stopped answering, long enough ago for video to be tried again. Its
            // thread went with it, so everything is made anew. If that thread ever does
            // come back, it closes what it had and ends.
            val gone = encoding
            encoding = Encoding(makeParts())
            gone.parts.quit()
        }
        if (size != source) {
            // An encoder has one size for life: after a turn the old one ends here, and another is started.
            encoding.outputs.turning(size.first, size.second)
            source = size
        }
        // Smooth sharing may have ended a moment ago, with its encoder still being let go
        // of. That one goes before this one is started.
        if (!encoding.lane.running) ScreenCaptureService.letGoOfVideo()
        encoding.outputs.mirror(true)
        return encoding.outputs.asVideo && encoding.lane.running
    }

    /**
     * Hands a screenshot to the video encoder, which encodes it when there
     * is room ([VideoPacer]): so, unlike a JPEG, it is not passed over here
     * for lack of room. The encoder always holds the newest, and that is
     * the one that goes.
     */
    private fun sendVideo(bitmap: Bitmap, token: Long, capturedAt: Long, size: Pair<Int, Int>,
                          shot: BitmapPicture?): Boolean {
        // What a viewer is owed whatever the screen shows, as for JPEGs: the size after a
        // turn, an answer when it has just asked, and a picture when pictures start again.
        val owed = infoRequested.get() || sentSize != size || sentGeneration != token
        // A screen that has not changed sends nothing. Every viewer of video acknowledges
        // what it gets, so a picture that was lost is sent again without this (PhoneScreenCapture).
        if (!owed && lastSent?.sameAs(bitmap) == true) return false
        if (owed) {
            infoRequested.set(false)
            // The size said is the encoder's, which may be smaller than the screenshot's.
            said = false
            encoding.outputs.announce()
            if (said) {
                sentSize = size
                sentGeneration = token
            } else {
                // The gate closed under it. It is still owed, with the next picture that goes.
                infoRequested.set(true)
            }
        }
        if (!isCurrent(token)) return false
        // A picture with no graphics memory behind it is handed over as a copy: this one is kept to compare with.
        val picture = shot ?: BitmapPicture(bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false)
        encoding.lane.show(picture, capturedAt)
        keep(bitmap, SystemClock.uptimeMillis())
        return shot != null
    }

    /** [bitmap] has gone out: it is what the next screenshot is compared with. */
    private fun keep(bitmap: Bitmap, now: Long) {
        val before = lastSent
        lastSent = bitmap
        lastSentAt = now
        if (before !== bitmap) before?.recycle()
    }

    /** Sends a screenshot as a JPEG, unless there is no room for it or it shows nothing new. */
    private fun sendJpeg(bitmap: Bitmap, token: Long, capturedAt: Long, width: Int, height: Int) {
        // No room: this one is passed over before the work of compressing it. The next is 400 ms away.
        if (!PhoneScreenCapture.hasRoom()) return
        val size = width to height
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            // What a viewer is owed whatever the screen shows: a picture and
            // its size after a turn, for a viewer that has just asked, and
            // when pictures start again.
            val owed = infoRequested.get() || sentSize != size || sentGeneration != token
            val now = SystemClock.uptimeMillis()
            // A viewer that acknowledges is sent a lost picture again
            // (PhoneScreenCapture). One that cannot is sent an unchanged
            // screen now and then instead, or a picture the relay dropped
            // for it would stay wrong until the screen next changed.
            val overdue = !PhoneScreenCapture.acknowledged && now - lastSentAt >= UNCHANGED_EVERY_MS
            // A screen that has not changed sends nothing: it was 100 KB two
            // and a half times a second, and on a slow upload a picture that
            // had changed then waited behind ones that had not.
            if (!owed && !overdue && lastSent?.sameAs(scaled) == true) return
            val out = ByteArrayOutputStream(200_000)
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 55, out) || !isCurrent(token)) return
            // Rotation and source/session switches need fresh dimensions before the JPEG.
            if (infoRequested.getAndSet(false) || sentSize != size || sentGeneration != token) {
                if (PhoneScreenCapture.sendScreenInfo(service, width, height, PhoneScreenCapture.ACCESSIBILITY)) {
                    sentSize = size
                    sentGeneration = token
                } else {
                    // The gate closed under it. It is still owed, with the next picture that goes.
                    infoRequested.set(true)
                }
            }
            if (isCurrent(token) &&
                PhoneScreenCapture.send(service, out.toByteArray(), width, height, capturedAt, PhoneScreenCapture.ACCESSIBILITY)) {
                // Kept only once it has really gone: one the queue refused is tried again.
                keep(scaled, now)
            }
        } finally {
            if (scaled !== bitmap && scaled !== lastSent) scaled.recycle()
        }
    }

    companion object {
        /** How often an unchanged screen is still sent to viewers that cannot acknowledge. */
        private const val UNCHANGED_EVERY_MS = 3_000L

        /** How long nobody has to have been viewing before the encoder is let go of. Smooth sharing's figure. */
        private const val IDLE_MS = 5_000L

        /** How long after the encoder last failed a size that failed is tried again. */
        private const val SIZES_FORGOTTEN_MS = 24 * 60 * 60_000L

        /**
         * An encoder stopped answering while screenshots were going through
         * it. Video is not tried for them again for a day, whichever capture
         * object asks (the Accessibility service makes a new one whenever it
         * is switched on). Smooth sharing has a flag of its own
         * ([ScreenCaptureService.videoWedged]): the two reach the encoder by
         * different ways, and what stalls one need not stall the other.
         */
        @Volatile internal var wedged = VideoWedge(SystemClock::elapsedRealtime)
    }
}
