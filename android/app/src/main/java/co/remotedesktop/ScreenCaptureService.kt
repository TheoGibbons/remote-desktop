package co.remotedesktop

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service holding the MediaProjection. The projection consent is
 * granted once per service start (an Android platform requirement); after
 * that, streaming starts/stops instantly on start-view/stop-view messages.
 *
 * Pictures leave as JPEGs, or as H.264 while every viewer can draw it
 * ([PhoneScreenCapture.videoWanted]). The screen is mirrored into an image
 * reader for the one and into a video encoder for the other. [ScreenOutputs]
 * decides which, and what happens when the encoder cannot be started or
 * fails: pictures go on as JPEGs and the connection history says why. This
 * class only hands it the display, the threads and the way to the viewers.
 *
 * Threads: the service's own calls and [updateStreaming] run on the main
 * thread. Pictures arrive, wait their turn and are made into JPEGs on the
 * capture thread, which is also where the stream is given a new shape when
 * the screen turns ([followScreen]), where the mirror is pointed at the
 * reader or the encoder and where mirroring is stopped while nobody views
 * ([ScreenOutputs]); the pacer and the bitmap belong to it. The image
 * reader is swapped there and closed on the main thread when sharing stops,
 * so [CaptureTarget] makes the two take turns.
 *
 * The video ([PhoneVideo], with its OpenGL context and its encoder) has a
 * third thread to itself, and is spoken to through [VideoLane]. An encoder
 * that stops answering takes that thread with it and nothing else: the
 * capture thread, which also hears Android end the sharing, goes on.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** How long nobody has to have been viewing before the screen stops being mirrored. */
        private const val IDLE_MS = 5_000L
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1

        @Volatile private var instance: ScreenCaptureService? = null

        val isRunning: Boolean get() = instance != null

        private fun notifyStateChanged(context: Context) {
            PhoneScreenCapture.init(context)
            PhoneScreenCapture.refresh()
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        fun setStreaming(on: Boolean) { instance?.updateStreaming(on) }

        private val main = Handler(Looper.getMainLooper())

        /**
         * When the screen is held on for smooth sharing. It outlives the
         * service: which devices are viewing, and which of them are only
         * back after a lost connection, is known before sharing starts.
         */
        internal var awake = KeepAwake()

        /** A device has begun to view this phone. [device] is its identity, the same after it reconnects. Any thread. */
        fun viewerStarted(device: String) {
            awake.started(device, SystemClock.uptimeMillis())
            holdChanged()
        }

        /** A device is no longer viewing; [byItself] when it said so with `stop-view`. Any thread. */
        fun viewerStopped(device: String, byItself: Boolean) {
            awake.stopped(device, byItself)
            holdChanged()
        }

        /** A message from a device that is viewing, as it is passed on to be acted on. Any thread. */
        fun viewerActed(msg: JSONObject) {
            val lock = msg.optString("type") == "lock"
            if (!lock && !InputAccessibilityService.wakesPhone(msg)) return
            if (lock) {
                awake.locked()
                // Let go before the screen is turned off, not a moment after.
                instance?.releaseScreen()
            } else awake.input(SystemClock.uptimeMillis())
            holdChanged()
        }

        /** The wake lock is taken and let go on the main thread, by the service that is running then. */
        private fun holdChanged() {
            main.post { instance?.holdScreen() }
        }

        fun resendScreenInfo() {
            instance?.outputs?.announce()
        }

        /**
         * The next H.264 picture should be a keyframe: [byViewer] when a
         * viewer asked, and then at most one a second is made. Any thread.
         */
        fun keyframeWanted(byViewer: Boolean) {
            instance?.lane?.keyframe(byViewer)
        }

        /** The last H.264 picture went unacknowledged: the screen goes again, as a keyframe. Any thread. */
        fun resendWanted() {
            instance?.lane?.resend()
        }

        /** Bits a second the video encoder is aiming at, while it runs. */
        val videoBitrate: Int? get() = instance?.lane?.bitrate

        /**
         * An encoder stopped answering and took the video's thread with it.
         * Video is not tried again while this process lives: whatever stuck
         * is still in it. The Accessibility service's video goes by it too.
         */
        internal val videoWedged = AtomicBoolean()

        /**
         * Where encoded pictures go in place of the viewers, for a check on
         * a real phone that writes the stream to a file. Null in the app.
         */
        @Volatile internal var videoSink: ((PhoneVideo.Frame) -> VideoSent)? = null
    }

    private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    private val target = CaptureTarget(::newReader) { it.close() }
    private var captureThread: HandlerThread? = null
    @Volatile private var streaming = false
    @Volatile private var streamGeneration = 0L
    private var reusableBitmap: Bitmap? = null

    // Whether pictures are to be H.264, as [updateStreaming] last found the viewers.
    private val wish = VideoWish()

    // The video's own thread: its OpenGL context is made, drawn with and let go of there.
    private var videoThread: HandlerThread? = null
    @Volatile private var videoHandler: Handler? = null

    private val video = PhoneVideo(
        encoders = PhoneVideoEncoder::encoders,
        open = { attempt, width, height, events ->
            PhoneVideoEncoder(attempt, width, height, checkNotNull(videoHandler), events)
        },
        timer = object : PhoneVideo.Timer {
            override fun post(task: Runnable) { videoHandler?.post(task) }
            override fun postDelayed(task: Runnable, delayMs: Long) { videoHandler?.postDelayed(task, delayMs) }
            override fun remove(task: Runnable) { videoHandler?.removeCallbacks(task) }
        },
        clock = SystemClock::uptimeMillis,
        hasRoom = PhoneScreenCapture::hasRoom,
        viewersBehind = PhoneScreenCapture::viewersBehind,
        ownQueueFull = { !ConnectionManager.hasRoomForVideo() },
        send = { frame ->
            videoSink?.invoke(frame)
                // Sharing has ended, and the encoder has not been let go of yet. The Accessibility
                // service's pictures may be going by now, and one of these would end their stream.
                ?: if (instance !== this) VideoSent.NOT_SENT
                else PhoneScreenCapture.sendVideo(this, frame.bytes, frame.keyframe,
                    frame.repeat, frame.resend, frame.width, frame.height, frame.capturedAtMs)
        },
        // It says so on its own thread; what follows is the capture thread's to do.
        onFailed = { reason, detail -> captureHandler?.post { outputs.failed(reason, detail) } },
        screenOn = { (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive },
    )
    private val lane = VideoLane(video,
        // With no thread for it, which is before sharing has started and after it has ended, there and then.
        post = { task -> if (videoHandler?.post(task) != true) task.run() },
        clock = SystemClock::uptimeMillis, wedge = videoWedged)
    private val outputs: ScreenOutputs<ImageReader> = ScreenOutputs(target, lane, wish,
        object : ScreenOutputs.Display<ImageReader> {
            override fun toReader(reader: ImageReader?) {
                try {
                    virtualDisplay?.surface = reader?.surface
                } catch (e: RuntimeException) {
                    // E.g. the projection stopped at that moment.
                    Log.w("ScreenCaptureService", "Could not point the mirror", e)
                }
            }

            // If this throws, video is taken to have failed.
            override fun toVideo(pipe: VideoPipe) {
                checkNotNull(virtualDisplay).surface = (pipe as PhoneVideoEncoder).surface
            }
        },
        object : ScreenOutputs.Host {
            override val sending: Boolean get() = streaming
            override fun size(): Pair<Int, Int> = outSize()
            override fun dropWaiting() = pacer.clear()

            override fun announce(width: Int, height: Int, video: Boolean, always: Boolean) {
                PhoneScreenCapture.sendScreenInfo(this@ScreenCaptureService, width, height,
                    PhoneScreenCapture.SCREEN_SHARE, video, always)
            }

            override fun videoFailed(reason: String, detail: String?) {
                Log.w("ScreenCaptureService", "Video failed: $reason ${detail.orEmpty()}")
                ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.VIDEO, System.currentTimeMillis(), reason, detail))
            }

            override fun later(task: Runnable, delayMs: Long) {
                captureHandler?.postDelayed(task, delayMs)
            }
        })

    // The screen is held on while the phone is viewed in smooth sharing,
    // because a phone that sleeps and locks ends it (see KeepAwake).
    private var screenLock: PowerManager.WakeLock? = null

    private val maxFps = 10
    // Capture thread only. The screen produces a picture only when it changes,
    // so one skipped for the frame rate, a full send queue or a viewer that is
    // behind is kept and sent later; otherwise the last change of an animation
    // could be lost.
    private val pacer = FramePacer<Image>(1000L / maxFps, retryMs = 100L) { it.close() }
    @Volatile private var captureHandler: Handler? = null
    private val catchUp = Runnable {
        if (!streaming || outputs.asVideo) pacer.clear()
        else pacer.retry(SystemClock.uptimeMillis(), PhoneScreenCapture.hasRoom())?.let(::encodeAndSend)
        scheduleCatchUp()
    }
    private val jpegQuality = 55

    // Capture thread. While nobody is viewing, Android would go on drawing
    // every change of the screen into the reader, each picture to be thrown
    // away. So the mirror is let go of, but not at once: pictures pause for a
    // moment whenever any device joins the session.
    private val idle = Runnable { if (!streaming) outputs.mirror(false) }
    private val resume = Runnable { outputs.mirror(true) }
    // A pause in the pictures: the encoder, if there is one, is kept and nothing is drawn into it.
    private val pause = Runnable { outputs.point() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotif()
        ConnectionManager.start(applicationContext)
        // Already sharing. Android's prompt can be up twice, once asked for by a
        // viewer and once from the Permissions screen, and both can be approved.
        // A second capture beside the first would end them both.
        if (instance === this) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        captureThread = HandlerThread("capture").also { it.start() }
        val handler = Handler(captureThread!!.looper)
        captureHandler = handler
        videoThread = HandlerThread("video").also { it.start() }
        videoHandler = Handler(videoThread!!.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }

            // Android 14 and later say when what is captured changes size, which a turn does.
            override fun onCapturedContentResize(width: Int, height: Int) = followScreen()
        }, handler)

        setupCapture()
        // Nobody may be viewing at all yet.
        handler.postDelayed(idle, IDLE_MS)
        // Every version says when the display changes; a turn is one of the changes.
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, handler)
        instance = this
        awake.sharing(true, SystemClock.uptimeMillis())
        holdScreen()
        notifyStateChanged(this)
        SmoothPrompt.sharingStarted()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val wasRunning = instance === this
        instance = null
        if (wasRunning) awake.sharing(false, SystemClock.uptimeMillis())
        releaseScreen()
        notifyStateChanged(this)
        // Viewers are told it is over, and why, as far as that can be known.
        SmoothPrompt.sharingEnded(this, wasRunning)
        streaming = false
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        // Waits for a change of shape that is under way; none starts after it.
        target.close { virtualDisplay?.release() }
        // The encoder and its OpenGL context are let go of on the thread they belong to,
        // which then ends; nothing of the video's is started after this. A thread that
        // never came back from its encoder cannot be ended, and is all that is left behind.
        captureHandler?.post {
            outputs.close()
            videoThread?.quitSafely()
        }
        projection?.stop()
        captureThread?.quitSafely()
        super.onDestroy()
    }

    /**
     * Takes, keeps or lets go of the screen as [awake] says. The lock times
     * out by itself when a viewer has been idle long enough, so nothing has
     * to come back here for that. Dimmed is enough: a viewer's touch
     * brightens it as a finger would. Main thread.
     */
    private fun holdScreen() {
        // A viewer's input can be on its way here as sharing stops. Taken then, the
        // screen would be held with nothing left to let go of it but the time limit.
        val ms = if (instance === this) awake.holdFor(SystemClock.uptimeMillis()) else null
        if (ms == null) {
            releaseScreen()
            return
        }
        try {
            val lock = screenLock ?: run {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "remotedesktop:smooth").also {
                    // Each take replaces the one before, and its time limit with it.
                    it.setReferenceCounted(false)
                    screenLock = it
                }
            }
            lock.acquire(ms)
        } catch (_: RuntimeException) {
            // The phone sleeps on its own timeout, as it did before this existed.
        }
    }

    private fun releaseScreen() {
        try {
            screenLock?.let { if (it.isHeld) it.release() }
        } catch (_: RuntimeException) {
            // Already let go by its time limit.
        }
    }

    /**
     * Pictures are to go out, or to stop. Called again whenever the viewers
     * change, since that is also when H.264 may become what they all can
     * draw, or stop being it.
     */
    private fun updateStreaming(on: Boolean) {
        val change = wish.update(on, on && PhoneScreenCapture.videoWanted())
        if (!change.any) return
        if (change.started || change.stopped) streamGeneration++
        streaming = on
        val handler = captureHandler
        handler?.removeCallbacks(idle)
        if (on) {
            // When only the format changes, viewers on older apps have nothing new to hear.
            outputs.announce(always = change.started)
            // Attaching makes Android draw the screen once straight away, so a
            // viewer has a picture without waiting for something to change.
            handler?.post(resume)
        } else {
            handler?.post(pause)
            handler?.postDelayed(idle, IDLE_MS)
        }
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun outSize(): Pair<Int, Int> {
        val (w, h, _) = screenSize()
        return StreamSize.of(w, h)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) followScreen()
        }
    }

    /**
     * The screen may have turned. If its shape is no longer the stream's, the
     * stream takes the new one: otherwise the picture would sit letterboxed
     * inside the old shape, and taps, which go by the screen as it now is,
     * would land in the wrong place. Capture thread only.
     */
    private fun followScreen() {
        val display = virtualDisplay ?: return
        val (w, h) = outSize()
        val (_, _, dpi) = screenSize()
        // An encoder cannot change size: video at the old shape ends before the mirror takes the new one.
        val videoStopped = outputs.turning(w, h)
        val changed = try {
            target.replace(w, h,
                attach = { reader ->
                    display.resize(w, h, dpi)
                    // Not while nobody views: it is attached when someone does.
                    if (target.drawing) display.surface = reader.surface
                },
                // A picture still waiting its turn is the old shape, and the old reader's.
                retire = { pacer.clear() })
        } catch (e: RuntimeException) {
            // E.g. the projection stopped while the screen was turning.
            Log.w("ScreenCaptureService", "Could not follow the screen's new shape", e)
            false
        }
        if (!changed && !videoStopped) return
        reusableBitmap = null
        // Video starts again at the new shape, and its first picture is a keyframe.
        outputs.point()
        // The viewer needs the new shape before the first picture in it.
        outputs.announce()
    }

    private fun setupCapture() {
        val (w, h) = outSize()
        val (_, _, dpi) = screenSize()

        val reader = target.open(w, h) ?: return
        virtualDisplay = projection!!.createVirtualDisplay(
            "remote-desktop",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, captureHandler
        )
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        // Three buffers: one kept by the pacer, two for acquireLatestImage.
        val made = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        made.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (_: IllegalStateException) { null }
                ?: return@setOnImageAvailableListener
            // Any key holder can decrypt frames, and older relays broadcast
            // them to the whole session — never send while an unapproved
            // device is present. Resumes automatically once it is approved or leaves.
            // Nor while pictures are to be H.264: the reader is about to give way to the encoder.
            if (!streaming || outputs.asVideo || !PhoneScreenCapture.maySendFrames(this)) {
                image.close()
                pacer.clear()
                return@setOnImageAvailableListener
            }
            pacer.offer(image, SystemClock.uptimeMillis(), PhoneScreenCapture.hasRoom())?.let(::encodeAndSend)
            scheduleCatchUp()
        }, captureHandler)
        return made
    }

    private fun scheduleCatchUp() {
        val handler = captureHandler ?: return
        handler.removeCallbacks(catchUp)
        pacer.retryDelay(SystemClock.uptimeMillis(), PhoneScreenCapture.hasRoom())
            ?.let { handler.postDelayed(catchUp, it) }
    }

    /** Sends one picture as a JPEG frame, then closes it. Capture thread only. */
    private fun encodeAndSend(image: Image) {
        try {
            val token = streamGeneration
            if (!streaming || outputs.asVideo || !PhoneScreenCapture.maySendFrames(this)) return
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val bmpW = image.width + rowPadding / plane.pixelStride
            var bmp = reusableBitmap
            if (bmp == null || bmp.width != bmpW || bmp.height != image.height) {
                bmp = Bitmap.createBitmap(bmpW, image.height, Bitmap.Config.ARGB_8888)
                reusableBitmap = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (bmpW != image.width)
                Bitmap.createBitmap(bmp, 0, 0, image.width, image.height) else bmp

            val out = ByteArrayOutputStream(200_000)
            cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            if (cropped !== bmp) cropped.recycle()
            // Android stamps the picture in nanoseconds, on the uptime clock where it keeps to it.
            val capturedAt = PhoneFrame.captureTime(image.timestamp / 1_000_000, SystemClock.uptimeMillis())
            if (streaming && token == streamGeneration)
                PhoneScreenCapture.send(this, out.toByteArray(), image.width, image.height, capturedAt,
                    PhoneScreenCapture.SCREEN_SHARE)
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun startForegroundWithNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Desktop")
            .setContentText("Screen sharing is available to paired devices")
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
