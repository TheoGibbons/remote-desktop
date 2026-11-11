package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration
import java.util.concurrent.Executor

@Implements(value = PeerAuth::class, isInAndroidSdk = false)
class ShadowPhoneCaptureAuth {
    companion object {
        val trusted = mutableSetOf("pc", "other-pc")
        var allTrusted = true
    }
    @Implementation fun init(context: Context) = Unit
    @Implementation fun isTrusted(peerId: String?) = peerId in trusted
    @Implementation fun getAllPeersTrusted() = allTrusted
    @Implementation fun getOwnFingerprint() = "a3f29c41a3f29c41"
}

@Implements(value = ConnectionManager::class, isInAndroidSdk = false)
class ShadowPhoneCaptureConnection {
    companion object {
        var connected = true
        /** The frame type every picture is expected to go out as. */
        var channel = Crypto.CH_VIDEO
        val info = mutableListOf<JSONObject>()
        val frames = mutableListOf<ByteArray>()
        val recipients = mutableListOf<Collection<String>?>()
    }
    @Implementation fun getState() = if (connected) "connected" else "disconnected"
    // Only the picture's size is counted here; lock-state has LockStateTest.
    @Implementation fun sendJson(message: JSONObject) {
        if (message.optString("type") == "screen-info") info.add(message)
    }
    @Implementation fun sendBinary(channel: Byte, bytes: ByteArray, to: Collection<String>?): Boolean {
        assertEquals(Companion.channel, channel)
        frames.add(bytes)
        recipients.add(to)
        return true
    }
}

class CaptureTestService : AccessibilityService() {
    var requests = 0
    lateinit var callback: TakeScreenshotCallback
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun takeScreenshot(displayId: Int, executor: Executor, callback: TakeScreenshotCallback) {
        requests++
        this.callback = callback
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowPhoneCaptureAuth::class, ShadowPhoneCaptureConnection::class,
    ShadowSessionEnvironment::class],
    instrumentedPackages = ["co.remotedesktop"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneScreenCaptureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private var capture: AccessibilityScreenCapture? = null

    @Before fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).autoConnect = false
        ShadowPhoneCaptureAuth.trusted.clear()
        ShadowPhoneCaptureAuth.trusted.addAll(listOf("pc", "other-pc"))
        ShadowPhoneCaptureAuth.allTrusted = true
        ShadowPhoneCaptureConnection.connected = true
        ShadowPhoneCaptureConnection.channel = Crypto.CH_VIDEO
        ShadowPhoneCaptureConnection.info.clear()
        ShadowPhoneCaptureConnection.frames.clear()
        ShadowPhoneCaptureConnection.recipients.clear()
        PhoneScreenCapture.init(context)
        PhoneScreenCapture.stateListeners.clear()
        PhoneScreenCapture.onViewStopped()
        main.idle()
    }

    @After fun cleanup() {
        capture?.close()
        PhoneScreenCapture.onViewStopped()
        main.idle()
    }

    @Test fun viewingRequiresAnActiveTrustedRequestAndPausesForUnapprovedPeers() {
        assertFalse(PhoneScreenCapture.maySendFrames(context))
        PhoneScreenCapture.onViewRequested(context, "stranger")
        assertFalse(PhoneScreenCapture.maySendFrames(context))
        PhoneScreenCapture.onViewRequested(context, "pc")
        assertTrue(PhoneScreenCapture.maySendFrames(context))
        ShadowPhoneCaptureAuth.allTrusted = false
        assertFalse(PhoneScreenCapture.maySendFrames(context))
        ShadowPhoneCaptureAuth.allTrusted = true
        assertTrue(PhoneScreenCapture.maySendFrames(context))
        ShadowPhoneCaptureAuth.trusted.remove("pc")
        PhoneScreenCapture.refresh()
        main.idle()
        ShadowPhoneCaptureAuth.trusted.add("pc")
        assertFalse(PhoneScreenCapture.maySendFrames(context))
    }

    @Test fun anApprovedViewerWakesASleepingScreenAndNobodyElseDoes() {
        val power = shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        ShadowPowerManager.clearWakeLocks()
        power.setIsInteractive(false)
        PhoneScreenCapture.onViewRequested(context, "stranger")
        assertNull(ShadowPowerManager.getLatestWakeLock())
        PhoneScreenCapture.onViewRequested(context, "pc")
        assertNotNull(ShadowPowerManager.getLatestWakeLock())
        ShadowPowerManager.clearWakeLocks()
        power.setIsInteractive(true) // Already on: nothing to do.
        PhoneScreenCapture.onViewRequested(context, "other-pc")
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test fun stoppingOneViewerKeepsTheOtherAndDisablingControlClearsAllRequests() {
        PhoneScreenCapture.onViewRequested(context, "pc")
        PhoneScreenCapture.onViewRequested(context, "other-pc")
        PhoneScreenCapture.onViewRequested(context, "stranger")
        assertEquals(setOf("pc", "other-pc"), PhoneScreenCapture.recipients().toSet())
        PhoneScreenCapture.onViewStopped("pc")
        assertTrue(PhoneScreenCapture.maySendFrames(context))
        assertEquals(listOf("other-pc"), PhoneScreenCapture.recipients())
        Prefs(context).allowControl = false
        assertFalse(PhoneScreenCapture.maySendFrames(context))
        Prefs(context).allowControl = true
        assertFalse(PhoneScreenCapture.maySendFrames(context))
        PhoneScreenCapture.onViewRequested(context, "pc")
        ShadowPhoneCaptureConnection.connected = false
        assertFalse(PhoneScreenCapture.maySendFrames(context))
        PhoneScreenCapture.onViewStopped()
        ShadowPhoneCaptureConnection.connected = true
        assertFalse(PhoneScreenCapture.maySendFrames(context))
    }

    @Test fun screenshotRequestsAreSerialAndRateLimitedAndStopCancelsRetries() {
        val service = Robolectric.buildService(CaptureTestService::class.java).create().get()
        val stream = AccessibilityScreenCapture(service).also { capture = it }
        PhoneScreenCapture.onViewRequested(context, "pc")
        main.idle()
        stream.setStreaming(true)
        main.idle()
        assertEquals(1, service.requests)
        main.idleFor(Duration.ofSeconds(2)) // No overlapping requests while a callback is pending.
        assertEquals(1, service.requests)
        service.callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
        main.idle()
        assertTrue(ShadowPhoneCaptureConnection.frames.isEmpty())
        main.idleFor(Duration.ofMillis(999))
        assertEquals(1, service.requests)
        main.idleFor(Duration.ofMillis(1))
        assertEquals(2, service.requests)
        service.callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        assertEquals(1, ShadowPhoneCaptureConnection.frames.size) // Replace the stale screen.
        // Only the viewer gets it: anyone else in the session may be watching another host.
        assertEquals(listOf(listOf("pc")), ShadowPhoneCaptureConnection.recipients)
        main.idleFor(Duration.ofSeconds(1))
        assertEquals(3, service.requests)
        stream.setStreaming(false)
        service.callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        main.idleFor(Duration.ofSeconds(2))
        assertEquals(3, service.requests)
        assertEquals(1, ShadowPhoneCaptureConnection.frames.size)
    }

    @Test fun accessibilityIsAvailableWithoutProjectionAndSmoothSharingHandsBackToIt() {
        val inputController = Robolectric.buildService(InputAccessibilityService::class.java).create()
        val smoothController = Robolectric.buildService(ScreenCaptureService::class.java).create()
        val fake = Robolectric.buildService(CaptureTestService::class.java).create().get()
        val stream = AccessibilityScreenCapture(fake).also { capture = it }
        val inputInstance = InputAccessibilityService::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
        val smoothInstance = ScreenCaptureService::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
        InputAccessibilityService::class.java.getDeclaredField("screenCapture")
            .apply { isAccessible = true }.set(inputController.get(), stream)
        inputInstance.set(null, inputController.get())
        fun streaming(instance: Any) = instance.javaClass.getDeclaredField("streaming")
            .apply { isAccessible = true }.getBoolean(instance)
        try {
            assertFalse(ScreenCaptureService.isRunning)
            assertTrue(PhoneScreenCapture.isAvailable)
            PhoneScreenCapture.onViewRequested(context, "pc")
            main.idle()
            assertTrue(streaming(stream))
            assertEquals(1, fake.requests)
            smoothInstance.set(null, smoothController.get())
            PhoneScreenCapture.refresh()
            main.idle()
            assertFalse(streaming(stream))
            assertTrue(streaming(smoothController.get()))
            smoothInstance.set(null, null) // User stops smooth sharing; retain the active viewer.
            PhoneScreenCapture.refresh()
            main.idle()
            assertTrue(streaming(stream))
            fake.callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            main.idle() // Late callback from before the switch must not send a frame.
            assertTrue(ShadowPhoneCaptureConnection.frames.isEmpty())
            PhoneScreenCapture.onViewStopped("pc")
            main.idle()
            assertFalse(streaming(stream))
        } finally {
            inputInstance.set(null, null)
            smoothInstance.set(null, null)
            inputController.destroy()
            smoothController.destroy()
        }
    }

    @Test fun lateFramesAreDroppedAndRotationResendsDimensionsBeforeJpeg() {
        val service = Robolectric.buildService(CaptureTestService::class.java).create().get()
        val stream = AccessibilityScreenCapture(service).also { capture = it }
        PhoneScreenCapture.onViewRequested(context, "pc")
        main.idle()
        stream.setStreaming(true)
        main.idle()
        fun token() = token(stream)
        fun frame(w: Int, h: Int, generation: Long = token()) = frame(stream, w, h, generation)
        frame(1440, 2560)
        assertEquals(1080, ShadowPhoneCaptureConnection.info.last().getInt("width"))
        assertEquals(1920, ShadowPhoneCaptureConnection.info.last().getInt("height"))
        val jpeg = ShadowPhoneCaptureConnection.frames.last()
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(1080, decoded.width)
        assertEquals(1920, decoded.height)
        decoded.recycle()
        frame(1440, 2560)
        assertEquals(1, ShadowPhoneCaptureConnection.info.size)
        // Turned on its side it is sent as sharp as upright: the same pixels, turned.
        frame(2560, 1440)
        assertEquals(2, ShadowPhoneCaptureConnection.info.size)
        assertEquals(1920, ShadowPhoneCaptureConnection.info.last().getInt("width"))
        assertEquals(1080, ShadowPhoneCaptureConnection.info.last().getInt("height"))
        ShadowPhoneCaptureConnection.frames.last().let { turned ->
            val picture = BitmapFactory.decodeByteArray(turned, 0, turned.size)
            assertEquals(1920 to 1080, picture.width to picture.height)
            picture.recycle()
        }
        val oldToken = token()
        stream.setStreaming(false)
        stream.setStreaming(true)
        frame(2560, 1440, oldToken)
        assertEquals(3, ShadowPhoneCaptureConnection.frames.size)
        frame(2560, 1440)
        assertEquals(3, ShadowPhoneCaptureConnection.info.size) // Same size, new session.
        Prefs(context).allowControl = false
        frame(2560, 1440)
        assertEquals(4, ShadowPhoneCaptureConnection.frames.size)
    }

    private fun token(stream: AccessibilityScreenCapture) = AccessibilityScreenCapture::class.java
        .getDeclaredField("generation").apply { isAccessible = true }.getLong(stream)

    /** A screenshot of that size reaches the capture, as Android would hand it over. */
    private fun frame(stream: AccessibilityScreenCapture, w: Int, h: Int, generation: Long = token(stream),
                      capturedAt: Long = 0) {
        val send = AccessibilityScreenCapture::class.java.getDeclaredMethod("send", Bitmap::class.java,
            java.lang.Long.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try { send.invoke(stream, bitmap, generation, capturedAt) } finally { bitmap.recycle() }
    }

    /** The same screenshots, to a viewer that said it can draw type 5 frames. */
    @Test fun aViewerOnACurrentAppGetsEachScreenshotBehindAHeader() {
        val service = Robolectric.buildService(CaptureTestService::class.java).create().get()
        val stream = AccessibilityScreenCapture(service).also { capture = it }
        PhoneScreenCapture.onViewRequested(context, "pc", setOf("jpeg"))
        main.idle()
        stream.setStreaming(true)
        main.idle()
        ShadowPhoneCaptureConnection.channel = Crypto.CH_PHONE

        frame(stream, 1440, 2560, capturedAt = 4321)
        val told = ShadowPhoneCaptureConnection.info.single()
        assertEquals("pc", told.getString("to"))
        assertEquals(1080 to 1920, told.getInt("width") to told.getInt("height"))
        assertEquals("accessibility", told.getString("capture"))
        assertEquals("jpeg", told.getString("codec"))
        val id = PhoneFrame.parseStream(told.getString("stream"))!!
        assertNotEquals(0L, id)
        ShadowPhoneCaptureConnection.frames.single().let { sent ->
            assertEquals(PhoneFrame.Header(id, 0, keyframe = true, format = PhoneFrame.JPEG, width = 1080, height = 1920,
                captureMs = 4321), PhoneFrame.parse(sent))
            val picture = BitmapFactory.decodeByteArray(sent, PhoneFrame.HEADER_SIZE, sent.size - PhoneFrame.HEADER_SIZE)
            assertEquals(1080 to 1920, picture.width to picture.height)
            picture.recycle()
        }
        assertEquals(listOf(listOf("pc")), ShadowPhoneCaptureConnection.recipients)

        // The phone turns: the new size is said first, and the stream goes on.
        frame(stream, 2560, 1440, capturedAt = 4721)
        assertEquals(2, ShadowPhoneCaptureConnection.info.size)
        ShadowPhoneCaptureConnection.info.last().let {
            assertEquals(1920 to 1080, it.getInt("width") to it.getInt("height"))
            assertEquals(told.getString("stream"), it.getString("stream"))
        }
        assertEquals(PhoneFrame.Header(id, 1, keyframe = true, format = PhoneFrame.JPEG, width = 1920, height = 1080,
            captureMs = 4721), PhoneFrame.parse(ShadowPhoneCaptureConnection.frames.last()))

        // A capture from before a pause is dropped, header and all.
        val oldToken = token(stream)
        stream.setStreaming(false)
        stream.setStreaming(true)
        frame(stream, 2560, 1440, oldToken)
        assertEquals(2, ShadowPhoneCaptureConnection.frames.size)
    }

    @Test fun smoothSharingIsOptionalInTheModernPermissionsScreen() {
        val controller = Robolectric.buildActivity(PermissionsActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals("Optional", activity.findViewById<TextView>(R.id.screenShareStatus).text.toString())
            assertEquals(context.getString(R.string.screen_view_and_control),
                activity.findViewById<TextView>(R.id.a11yTitle).text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.bannerText).text.toString()
                .contains("of 5 required permissions"))
        } finally { controller.pause().stop().destroy() }
    }

    /** A Samsung phone turns its screen on for a viewer only while they are allowed. */
    @Test fun notificationsAreCountedAndTheirRowSaysWhenTheyAreOff() {
        val notifications = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        fun required() = PermissionsActivity.required(context)
        assertEquals(5, required().size)
        val before = required().count { it }
        notifications.setNotificationsEnabled(false)
        assertEquals(before - 1, required().count { it })

        val controller = Robolectric.buildActivity(PermissionsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val pill = activity.findViewById<TextView>(R.id.notificationsStatus)
            assertEquals("Tap to enable", pill.text.toString())
            assertEquals("Action needed", activity.findViewById<TextView>(R.id.bannerTitle).text.toString())

            // The row opens this app's notification settings, where Android keeps the switch.
            activity.findViewById<android.view.View>(R.id.notificationsRow).performClick()
            val opened = shadowOf(activity).nextStartedActivity
            assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
            assertEquals(context.packageName, opened.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))

            // Allowed there, and back.
            notifications.setNotificationsEnabled(true)
            controller.pause().resume()
            assertEquals("Enabled", pill.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(sdk = [28])
    fun olderPhonesStillRequireScreenSharing() {
        val controller = Robolectric.buildActivity(PermissionsActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals("Tap to enable", activity.findViewById<TextView>(R.id.screenShareStatus).text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.bannerText).text.toString()
                .contains("of 6 required permissions"))
        } finally { controller.pause().stop().destroy() }
    }
}
