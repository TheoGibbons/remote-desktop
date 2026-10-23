package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One screenshot at a time; stays below Android's 333 ms screenshot rate limit. */
@RequiresApi(30)
internal class AccessibilityScreenCapture(private val service: AccessibilityService) {
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
    private val infoRequested = AtomicBoolean(true)

    fun resendScreenInfo() { infoRequested.set(true) }

    private fun isCurrent(token: Long): Boolean =
        streaming && generation == token && !ScreenCaptureService.isRunning &&
            PhoneScreenCapture.maySendFrames(service)

    fun setStreaming(on: Boolean) {
        if (closed || streaming == on) return
        streaming = on
        generation++
        main.removeCallbacks(request)
        if (on) schedule()
    }

    fun close() {
        setStreaming(false)
        closed = true
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
                    try {
                        if (!isCurrent(token)) return
                        hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        bitmap?.let { send(it, token) }
                    } catch (e: RuntimeException) {
                        Log.w("AccessibilityCapture", "Could not encode screenshot", e)
                    } finally {
                        bitmap?.recycle()
                        hardware?.recycle()
                        buffer.close()
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

    private fun sendUnavailable(token: Long) {
        val metrics = service.resources.displayMetrics
        val bitmap = Bitmap.createBitmap(metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, metrics)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Screen unavailable", bitmap.width / 2f, bitmap.height / 2f, paint)
            send(bitmap, token)
        } finally {
            bitmap.recycle()
        }
    }

    private var sentGeneration = -1L

    private fun send(bitmap: Bitmap, token: Long) {
        val scale = minOf(1.0, 1080.0 / bitmap.width)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val out = ByteArrayOutputStream(200_000)
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 55, out) || !isCurrent(token)) return
            val size = width to height
            // Rotation and source/session switches need fresh dimensions before the JPEG.
            if (infoRequested.getAndSet(false) || sentSize != size || sentGeneration != token) {
                ConnectionManager.sendJson(JSONObject().put("type", "screen-info")
                    .put("width", width).put("height", height)
                    .put("typing", InputAccessibilityService.canType))
                sentSize = size
                sentGeneration = token
            }
            if (isCurrent(token)) ConnectionManager.sendBinary(Crypto.CH_VIDEO, out.toByteArray())
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
