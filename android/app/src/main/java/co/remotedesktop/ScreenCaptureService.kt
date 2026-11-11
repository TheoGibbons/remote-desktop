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
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Foreground service holding the MediaProjection. The projection consent is
 * granted once per service start (an Android platform requirement); after
 * that, streaming starts/stops instantly on start-view/stop-view messages.
 *
 * Threads: the service's own calls and [updateStreaming] run on the main
 * thread. Pictures arrive, wait their turn and are encoded on the capture
 * thread, which is also where the stream is given a new shape when the screen
 * turns ([followScreen]); the image reader, the pacer and the bitmap belong
 * to it once capture has started.
 */
class ScreenCaptureService : Service() {

    companion object {
        /** The size a screen is streamed at: its own shape, no wider than [maxWidth]. */
        internal fun streamSize(screenWidth: Int, screenHeight: Int, maxWidth: Int): Pair<Int, Int> {
            val scale = if (screenWidth > maxWidth) maxWidth.toDouble() / screenWidth else 1.0
            return Pair((screenWidth * scale).toInt().coerceAtLeast(1), (screenHeight * scale).toInt().coerceAtLeast(1))
        }

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
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    @Volatile private var streaming = false
    @Volatile private var streamGeneration = 0L
    private var reusableBitmap: Bitmap? = null

    private val maxFps = 10
    // Capture thread only. The screen produces a picture only when it changes,
    // so one skipped for the frame rate or a full send queue is kept and sent
    // later; otherwise the last change of an animation could be lost.
    private val pacer = FramePacer<Image>(1000L / maxFps, retryMs = 100L) { it.close() }
    private var captureHandler: Handler? = null
    private val catchUp = Runnable {
        if (!streaming) pacer.clear()
        else pacer.retry(SystemClock.uptimeMillis(), ConnectionManager.hasRoomForVideo())?.let(::encodeAndSend)
        scheduleCatchUp()
    }
    private val jpegQuality = 55
    private val maxWidth = 1080

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

        setupCapture(handler)
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
        virtualDisplay?.release()
        imageReader?.close()
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
        return streamSize(w, h, maxWidth)
    }

    private fun sendScreenInfo() {
        val (w, h) = outSize()
        ConnectionManager.sendJson(
            JSONObject().put("type", "screen-info").put("width", w).put("height", h)
                .put("typing", InputAccessibilityService.canType)
        )
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
        if (instance !== this) return
        val display = virtualDisplay ?: return
        val old = imageReader ?: return
        val handler = captureHandler ?: return
        val (w, h) = outSize()
        if (old.width == w && old.height == h) return
        try {
            val (_, _, dpi) = screenSize()
            val reader = newReader(w, h, handler)
            display.resize(w, h, dpi)
            display.surface = reader.surface
            imageReader = reader
            // A picture still waiting its turn is the old shape, and the old reader's.
            pacer.clear()
            old.close()
            reusableBitmap = null
            // The viewer needs the new shape before the first picture in it.
            if (streaming && PhoneScreenCapture.maySendFrames(this)) sendScreenInfo()
        } catch (e: RuntimeException) {
            // E.g. the projection stopped while the screen was turning.
            Log.w("ScreenCaptureService", "Could not follow the screen's new shape", e)
        }
    }

    private fun setupCapture(handler: Handler) {
        val (w, h) = outSize()
        val (_, _, dpi) = screenSize()

        imageReader = newReader(w, h, handler)
        virtualDisplay = projection!!.createVirtualDisplay(
            "remote-desktop",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )
    }

    private fun newReader(w: Int, h: Int, handler: Handler): ImageReader {
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
            pacer.offer(image, SystemClock.uptimeMillis(), ConnectionManager.hasRoomForVideo())?.let(::encodeAndSend)
            scheduleCatchUp()
        }, handler)
        return made
    }

    private fun scheduleCatchUp() {
        val handler = captureHandler ?: return
        handler.removeCallbacks(catchUp)
        pacer.retryDelay(SystemClock.uptimeMillis(), ConnectionManager.hasRoomForVideo())
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
            // ConnectionManager adds the frame-type byte and encrypts the JPEG.
            if (streaming && token == streamGeneration && PhoneScreenCapture.maySendFrames(this))
                ConnectionManager.sendBinary(Crypto.CH_VIDEO, out.toByteArray(), PhoneScreenCapture.recipients())
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
