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
    // Worker thread only: the picture last sent and when, to tell a screen that has not changed.
    private var lastSent: Bitmap? = null
    private var lastSentAt = 0L
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
        // The next picture goes whatever it shows, and this one need not be kept meanwhile.
        else worker.post(::forgetSent)
    }

    private fun forgetSent() {
        lastSent?.recycle()
        lastSent = null
    }

    fun close() {
        setStreaming(false)
        worker.post(::forgetSent)
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
                        // Android stamps the screenshot on the uptime clock.
                        bitmap?.let { send(it, token, PhoneFrame.captureTime(result.timestamp, SystemClock.uptimeMillis())) }
                    } catch (e: RuntimeException) {
                        Log.w("AccessibilityCapture", "Could not encode screenshot", e)
                    } finally {
                        // Not the one that is kept to compare the next with.
                        if (bitmap !== lastSent) bitmap?.recycle()
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
            send(bitmap, token, SystemClock.uptimeMillis())
        } finally {
            if (bitmap !== lastSent) bitmap.recycle()
        }
    }

    private var sentGeneration = -1L

    /**
     * Sends a screenshot, unless there is no room for it or it shows nothing
     * new. [capturedAt] is when the screen was captured, on the uptime clock.
     * A bitmap that is sent is kept, to compare the next one with: the caller
     * must not recycle [lastSent].
     */
    private fun send(bitmap: Bitmap, token: Long, capturedAt: Long) {
        // No room: this one is passed over before the work of compressing it. The next is 400 ms away.
        if (!isCurrent(token) || !PhoneScreenCapture.hasRoom()) return
        val (width, height) = StreamSize.of(bitmap.width, bitmap.height)
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
                val before = lastSent
                lastSent = scaled
                lastSentAt = now
                if (before !== scaled) before?.recycle()
            }
        } finally {
            if (scaled !== bitmap && scaled !== lastSent) scaled.recycle()
        }
    }

    private companion object {
        /** How often an unchanged screen is still sent to viewers that cannot acknowledge. */
        const val UNCHANGED_EVERY_MS = 3_000L
    }
}
