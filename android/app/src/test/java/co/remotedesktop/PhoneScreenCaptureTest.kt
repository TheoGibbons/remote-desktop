package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
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
        val info = mutableListOf<JSONObject>()
        val frames = mutableListOf<ByteArray>()
    }
    @Implementation fun getState() = if (connected) "connected" else "disconnected"
    @Implementation fun sendJson(message: JSONObject) { info.add(message) }
    @Implementation fun sendBinary(channel: Byte, bytes: ByteArray): Boolean {
        assertEquals(Crypto.CH_VIDEO, channel)
        frames.add(bytes)
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
        ShadowPhoneCaptureConnection.info.clear()
        ShadowPhoneCaptureConnection.frames.clear()
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

    @Test fun stoppingOneViewerKeepsTheOtherAndDisablingControlClearsAllRequests() {
        PhoneScreenCapture.onViewRequested(context, "pc")
        PhoneScreenCapture.onViewRequested(context, "other-pc")
        PhoneScreenCapture.onViewStopped("pc")
        assertTrue(PhoneScreenCapture.maySendFrames(context))
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
        fun token() = AccessibilityScreenCapture::class.java.getDeclaredField("generation")
            .apply { isAccessible = true }.getLong(stream)
        val send = AccessibilityScreenCapture::class.java.getDeclaredMethod("send", Bitmap::class.java,
            java.lang.Long.TYPE).apply { isAccessible = true }
        fun frame(w: Int, h: Int, generation: Long = token()) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try { send.invoke(stream, bitmap, generation) } finally { bitmap.recycle() }
        }
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
        frame(2560, 1440)
        assertEquals(2, ShadowPhoneCaptureConnection.info.size)
        assertEquals(1080, ShadowPhoneCaptureConnection.info.last().getInt("width"))
        assertEquals(607, ShadowPhoneCaptureConnection.info.last().getInt("height"))
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

    @Test fun smoothSharingIsOptionalInTheModernPermissionsScreen() {
        val controller = Robolectric.buildActivity(PermissionsActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals("Optional", activity.findViewById<TextView>(R.id.screenShareStatus).text.toString())
            assertEquals(context.getString(R.string.screen_view_and_control),
                activity.findViewById<TextView>(R.id.a11yTitle).text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.bannerText).text.toString()
                .contains("of 4 required permissions"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(sdk = [28])
    fun olderPhonesStillRequireScreenSharing() {
        val controller = Robolectric.buildActivity(PermissionsActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals("Tap to enable", activity.findViewById<TextView>(R.id.screenShareStatus).text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.bannerText).text.toString()
                .contains("of 5 required permissions"))
        } finally { controller.pause().stop().destroy() }
    }
}
