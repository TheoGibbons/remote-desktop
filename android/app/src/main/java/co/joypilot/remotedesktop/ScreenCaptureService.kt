package co.joypilot.remotedesktop

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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Foreground service holding the MediaProjection. The projection consent is
 * granted once per service start (an Android platform requirement); after
 * that, streaming starts/stops instantly on start-view/stop-view messages.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1

        @Volatile private var instance: ScreenCaptureService? = null

        val isRunning: Boolean get() = instance != null

        /** Fired on the main thread when the service starts/stops (the service
         *  starts asynchronously, after the consent dialog's activity result). */
        val stateListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        private fun notifyStateChanged(context: Context) {
            Handler(context.mainLooper).post { stateListeners.forEach { it() } }
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

        /** A peer asked to view this phone's screen. */
        fun onViewRequested(context: Context) {
            val svc = instance
            Handler(context.mainLooper).post {
                android.widget.Toast.makeText(
                    context,
                    if (svc != null) "The desktop started viewing this phone"
                    else "The desktop tried to view this phone — enable screen sharing in the app first",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            svc?.setStreaming(true)
        }

        fun onViewStopped() {
            instance?.setStreaming(false)
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    @Volatile private var streaming = false
    @Volatile private var lastFrameAt = 0L
    private var reusableBitmap: Bitmap? = null

    private val maxFps = 10
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

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, null)

        setupCapture()
        instance = this
        notifyStateChanged(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        notifyStateChanged(this)
        streaming = false
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        captureThread?.quitSafely()
        super.onDestroy()
    }

    fun setStreaming(on: Boolean) {
        streaming = on
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
        val scale = if (w > maxWidth) maxWidth.toDouble() / w else 1.0
        return Pair((w * scale).toInt(), (h * scale).toInt())
    }

    private fun sendScreenInfo() {
        val (w, h) = outSize()
        ConnectionManager.sendJson(
            JSONObject().put("type", "screen-info").put("width", w).put("height", h)
        )
    }

    private fun setupCapture() {
        val (w, h) = outSize()
        val (_, _, dpi) = screenSize()

        captureThread = HandlerThread("capture").also { it.start() }
        val handler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!streaming) return@setOnImageAvailableListener
                // Frames are broadcast to the whole session and any key holder
                // can decrypt them — never send while an unapproved device is
                // present. Resumes automatically once it is approved or leaves.
                if (!PeerAuth.allPeersTrusted) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < 1000L / maxFps) return@setOnImageAvailableListener
                lastFrameAt = now

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
                // ConnectionManager adds the frame-type byte and encrypts the JPEG.
                ConnectionManager.sendBinary(Crypto.CH_VIDEO, out.toByteArray())
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection!!.createVirtualDisplay(
            "remote-desktop",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )
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
