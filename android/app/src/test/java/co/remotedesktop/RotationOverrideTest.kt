package co.remotedesktop

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.view.View
import android.view.WindowManager
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowWindowManagerImpl

/** A viewer may hold the phone's screen upright or on its side, and the
 *  phone takes rotation back when the viewers have gone or the screen turns off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RotationOverrideTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private val instanceField = InputAccessibilityService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }
    private lateinit var controller: ServiceController<InputAccessibilityService>
    private lateinit var service: InputAccessibilityService

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = PeerAuth::class.java.getDeclaredField("trustedPeers")
        .apply { isAccessible = true }.get(PeerAuth) as MutableSet<String>

    private val captureContext = PhoneScreenCapture::class.java.getDeclaredField("appContext")
        .apply { isAccessible = true }

    private val handleJson = ConnectionManager::class.java.getDeclaredMethod("handleJson", JSONObject::class.java)
        .apply { isAccessible = true }

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("appContext", "encKey", "ws", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("state").set(ConnectionManager, "connected")
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("other-pc", "windows", "Other PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("phone", "android", "Test phone"))
        trustedPeers.addAll(listOf("pc", "other-pc", "phone"))
        // It keeps the context of whichever test first set it up; this test's switches live in this one's.
        previous["capture"] = captureContext.get(PhoneScreenCapture)
        captureContext.set(PhoneScreenCapture, null)
        PhoneScreenCapture.init(context)
        controller = Robolectric.buildService(InputAccessibilityService::class.java).create()
        service = controller.get()
        instanceField.set(null, service)
        ShadowPowerManager.clearWakeLocks()
    }

    @After
    fun cleanup() {
        PhoneScreenCapture.onViewStopped()
        idle()
        instanceField.set(null, null)
        captureContext.set(PhoneScreenCapture, previous.remove("capture"))
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun receive(from: String, msg: JSONObject) {
        handleJson.invoke(ConnectionManager, msg.put("from", from))
        idle()
    }

    private fun message(type: String) = JSONObject().put("type", type)

    private fun rotate(from: String, orientation: String) = receive(from, message("rotate").put("orientation", orientation))

    private fun states() = socket.messages.filter { it.getString("type") == "phone-state" }

    /** The windows the service has added: the one that turns the screen, or none. */
    private val windows: List<View>
        get() = (shadowOf(service.getSystemService(Context.WINDOW_SERVICE) as WindowManager) as ShadowWindowManagerImpl).views

    private fun asked(view: View) = (view.layoutParams as WindowManager.LayoutParams).screenOrientation

    private fun reported(): String {
        receive("pc", message("get-phone-state"))
        return states().last().getString("rotation")
    }

    @Test
    fun aViewingPcTurnsTheScreenAndIsToldSo() {
        receive("pc", message("start-view"))
        assertEquals("auto", reported())

        rotate("pc", "landscape")
        val window = windows.single()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, asked(window))
        (window.layoutParams as WindowManager.LayoutParams).let {
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, it.type)
            // It must never take a touch or the keyboard from the app underneath.
            assertTrue(it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        }
        states().last().let {
            assertEquals("pc", it.getString("to"))
            assertEquals("landscape", it.getString("rotation"))
        }

        // The other way: the same window asks for something else.
        rotate("pc", "portrait")
        assertSame(window, windows.single())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, asked(window))
        assertEquals("portrait", states().last().getString("rotation"))

        rotate("pc", "auto")
        assertEquals(emptyList<View>(), windows)
        assertEquals("auto", states().last().getString("rotation"))
    }

    @Test
    fun thePhoneSaysItCanRotate() {
        receive("pc", message("get-phone-state"))
        val can = states().single().getJSONArray("can")
        assertTrue((0 until can.length()).any { can.getString(it) == "rotate" })
    }

    @Test
    fun onlyADeviceThatIsViewingMayTurnTheScreen() {
        // Approved, but not looking at the phone.
        rotate("pc", "landscape")
        assertEquals(emptyList<View>(), windows)
        assertEquals(emptyList<JSONObject>(), socket.messages)

        // Another phone that is viewing may, until Allow other phones goes off.
        receive("phone", message("start-view"))
        rotate("phone", "landscape")
        assertEquals(1, windows.size)
        Prefs(context).allowPhones = false
        idle()
        assertEquals("That phone was the only viewer", emptyList<View>(), windows)
        rotate("phone", "landscape")
        assertEquals(emptyList<View>(), windows)

        // Nor with the control switch off, which ends every view.
        receive("pc", message("start-view"))
        rotate("pc", "portrait")
        assertEquals(1, windows.size)
        Prefs(context).allowControl = false
        idle()
        assertEquals(emptyList<View>(), windows)
        rotate("pc", "portrait")
        assertEquals(emptyList<View>(), windows)
    }

    @Test
    fun anOrientationThatIsNotOneOfTheThreeChangesNothing() {
        receive("pc", message("start-view"))
        rotate("pc", "upside-down")
        receive("pc", message("rotate"))
        assertEquals(emptyList<View>(), windows)
        rotate("pc", "landscape")
        rotate("pc", "sideways")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, asked(windows.single()))
        // Each is still answered with how things stand.
        assertEquals("landscape", states().last().getString("rotation"))
    }

    @Test
    fun rotationGoesBackToThePhoneWhenTheViewerStops() {
        receive("pc", message("start-view"))
        rotate("pc", "landscape")
        assertEquals(1, windows.size)
        receive("pc", message("stop-view"))
        assertEquals(emptyList<View>(), windows)
        assertEquals("auto", InputAccessibilityService.rotation)
    }

    @Test
    fun rotationIsHeldWhileAnyViewerIsLeft() {
        receive("pc", message("start-view"))
        receive("other-pc", message("start-view"))
        rotate("pc", "landscape")
        receive("pc", message("stop-view"))
        assertEquals("Someone is still looking at it", 1, windows.size)
        receive("other-pc", message("stop-view"))
        assertEquals(emptyList<View>(), windows)
    }

    @Test
    fun rotationGoesBackWhenTheViewerLeavesTheSession() {
        receive("pc", message("start-view"))
        rotate("pc", "landscape")
        // The relay says the PC has gone: its app was closed, or its connection dropped.
        handleJson.invoke(ConnectionManager, message("peer-left").put("id", "pc"))
        idle()
        assertEquals(emptyList<View>(), windows)
    }

    @Test
    fun rotationGoesBackWhenTheScreenTurnsOffAndIsNotPutBack() {
        receive("pc", message("start-view"))
        rotate("pc", "landscape")
        assertEquals(1, windows.size)

        // The screen turns off, which is also when the phone locks.
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle()
        assertEquals(emptyList<View>(), windows)
        assertEquals("auto", reported())

        // Still viewing when it wakes: the lock screen keeps the phone's own way.
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        idle()
        assertEquals(emptyList<View>(), windows)
        assertTrue(PhoneScreenCapture.isViewing("pc"))

        // Nothing listens once nothing is held: a second screen-off finds nobody.
        assertFalse(shadowOf(context).registeredReceivers.any { wrapper ->
            wrapper.intentFilter.hasAction(Intent.ACTION_SCREEN_OFF) && wrapper.broadcastReceiver.javaClass.name.contains("RotationOverride")
        })

        // The viewer can ask again.
        rotate("pc", "landscape")
        assertEquals(1, windows.size)
    }

    @Test
    fun theWindowGoesWithTheService() {
        receive("pc", message("start-view"))
        rotate("pc", "portrait")
        assertEquals(1, windows.size)
        controller.destroy()
        idle()
        assertEquals(emptyList<View>(), windows)
        assertEquals("auto", InputAccessibilityService.rotation)
    }

    @Test
    fun turningTheScreenDoesNotWakeASleepingPhone() {
        receive("pc", message("start-view"))
        idle()
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(false)
        ShadowPowerManager.clearWakeLocks()
        rotate("pc", "landscape")
        rotate("pc", "auto")
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    private class RecordingSocket : WebSocket {
        val key = ByteArray(32) { 7 }
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("https://example.test/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            val blob = Base64.decode(JSONObject(text).getString("d"), Base64.NO_WRAP)
            messages.add(JSONObject(String(Crypto.decrypt(key, blob, Crypto.CH_JSON)!!)))
            return true
        }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
}
