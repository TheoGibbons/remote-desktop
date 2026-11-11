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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Foreground service holding the MediaProjection. The projection consent is
 * granted once per service start (an Android platform requirement); after
 * that, streaming starts/stops instantly on start-view/stop-view messages.
 *
 * Threads: the service's own calls and [updateStreaming] run on the main
 * thread. Pictures arrive, wait their turn and are encoded on the capture
 * thread, which is also where the stream is given a new shape when the screen
 * turns ([followScreen]); the pacer and the bitmap belong to it. The image
 * reader is swapped there and closed on the main thread when sharing stops,
 * so [CaptureTarget] makes the two take turns.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
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

        fun resendScreenInfo() {
            instance?.let { if (it.streaming && PhoneScreenCapture.maySendFrames(it)) it.sendScreenInfo() }
        }
    }

    private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    private val target = CaptureTarget(::newReader) { it.close() }
    private var captureThread: HandlerThread? = null
    @Volatile private var streaming = false
    @Volatile private var streamGeneration = 0L
    private var reusableBitmap: Bitmap? = null

    private val maxFps = 10
    // Capture thread only. The screen produces a picture only when it changes,
    // so one skipped for the frame rate, a full send queue or a viewer that is
    // behind is kept and sent later; otherwise the last change of an animation
    // could be lost.
    private val pacer = FramePacer<Image>(1000L / maxFps, retryMs = 100L) { it.close() }
    @Volatile private var captureHandler: Handler? = null
    private val catchUp = Runnable {
        if (!streaming) pacer.clear()
        else pacer.retry(SystemClock.uptimeMillis(), PhoneScreenCapture.hasRoom())?.let(::encodeAndSend)
        scheduleCatchUp()
    }
    private val jpegQuality = 55

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotif()
        ConnectionManager.start(applicationContext)

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
        // Every version says when the display changes; a turn is one of the changes.
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, handler)
        instance = this
        notifyStateChanged(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        notifyStateChanged(this)
        streaming = false
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        // Waits for a change of shape that is under way; none starts after it.
        target.close { virtualDisplay?.release() }
        projection?.stop()
        captureThread?.quitSafely()
        super.onDestroy()
    }

    private fun updateStreaming(on: Boolean) {
        if (streaming == on) return
        streaming = on
        streamGeneration++
        if (on) sendScreenInfo()
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

    private fun sendScreenInfo() {
        val (w, h) = outSize()
        PhoneScreenCapture.sendScreenInfo(this, w, h, PhoneScreenCapture.SCREEN_SHARE)
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
        val changed = try {
            target.replace(w, h,
                attach = { reader ->
                    display.resize(w, h, dpi)
                    display.surface = reader.surface
                },
                // A picture still waiting its turn is the old shape, and the old reader's.
                retire = { pacer.clear() })
        } catch (e: RuntimeException) {
            // E.g. the projection stopped while the screen was turning.
            Log.w("ScreenCaptureService", "Could not follow the screen's new shape", e)
            false
        }
        if (!changed) return
        reusableBitmap = null
        // The viewer needs the new shape before the first picture in it.
        if (streaming && PhoneScreenCapture.maySendFrames(this)) sendScreenInfo()
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
            if (!streaming || !PhoneScreenCapture.maySendFrames(this)) {
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
            if (!streaming || !PhoneScreenCapture.maySendFrames(this)) return
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
