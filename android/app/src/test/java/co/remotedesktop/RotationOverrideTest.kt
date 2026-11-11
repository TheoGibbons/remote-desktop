package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** A service whose windows Android refuses, as when it is being switched off. */
class RefusedWindowService : AccessibilityService() {
    var asked = 0
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun getSystemService(name: String): Any? {
        if (name != Context.WINDOW_SERVICE) return super.getSystemService(name)
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WindowManager::class.java), InvocationHandler { _, method, _ ->
            if (method.name == "addView") {
                asked++
                throw WindowManager.BadTokenException("Unable to add window: the service is gone")
            }
            null
        })
    }
}

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

    private fun can(state: JSONObject) = state.getJSONArray("can").let { list -> List(list.length()) { list.getString(it) } }

    @Test
    fun aSleepingPhoneIsNeitherTurnedNorWoken() {
        receive("pc", message("start-view"))
        rotate("pc", "landscape")
        assertEquals(1, windows.size)

        // The screen goes off, and the phone lets go.
        val power = shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        power.setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle()
        assertEquals(emptyList<View>(), windows)
        ShadowPowerManager.clearWakeLocks()
        socket.messages.clear()

        // The viewer's window is still open, and asks again. Nothing would ever tell
        // the phone to let go of this one: the screen is off already.
        rotate("pc", "landscape")
        assertEquals(emptyList<View>(), windows)
        assertNull("Turning the screen is not input", ShadowPowerManager.getLatestWakeLock())
        states().single().let {
            assertEquals("auto", it.getString("rotation"))
            assertFalse("It can't right now, and says so", "rotate" in can(it))
            assertTrue("The rest is unaffected", "lock" in can(it))
        }
        rotate("pc", "auto")
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun aLockedPhoneKeepsItsOwnOrientation() {
        receive("pc", message("start-view"))
        // Awake again, on its lock screen.
        val keyguard = shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        keyguard.setKeyguardLocked(true)
        rotate("pc", "landscape")
        assertEquals(emptyList<View>(), windows)
        states().last().let {
            assertEquals("auto", it.getString("rotation"))
            assertFalse("rotate" in can(it))
        }

        // Unlocked: the phone can turn again, and says so.
        keyguard.setKeyguardLocked(false)
        receive("pc", message("get-phone-state"))
        assertTrue("rotate" in can(states().last()))
        rotate("pc", "landscape")
        assertEquals(1, windows.size)
        assertEquals("landscape", states().last().getString("rotation"))
    }

    @Test
    fun aTurnIsNotHeldForAViewerWhoHasStoppedByTheTimeItIsApplied() {
        receive("pc", message("start-view"))
        // The message was let in while the PC was viewing, on the connection's thread...
        handleJson.invoke(ConnectionManager, message("rotate").put("orientation", "landscape").put("from", "pc"))
        // ...and before the main thread gets to it, the view is over.
        PhoneScreenCapture.onViewStopped("pc")
        idle()
        assertEquals(emptyList<View>(), windows)
        assertEquals("auto", InputAccessibilityService.rotation)
        assertEquals("auto", states().last().getString("rotation"))

        // The other order: the phone has already let go, finding nothing held, when
        // the turn arrives from a check made a moment earlier.
        PhoneControls.handle(context, "pc", message("rotate").put("orientation", "landscape"))
        idle()
        assertEquals(emptyList<View>(), windows)
        assertEquals("auto", states().last().getString("rotation"))
    }

    @Test
    fun theAnswerSaysWhatIsReallyHeld() {
        // Asked and answered in that order: the answer comes after the window.
        receive("pc", message("start-view"))
        handleJson.invoke(ConnectionManager, message("rotate").put("orientation", "landscape").put("from", "pc"))
        assertEquals("Nothing is said before the window is there", emptyList<JSONObject>(), states())
        idle()
        assertEquals(1, windows.size)
        assertEquals("landscape", states().single().getString("rotation"))

        // Android refuses the window: nothing is held, and nothing claims to be.
        val refused = Robolectric.buildService(RefusedWindowService::class.java).create().get()
        val override = RotationOverride(refused)
        var told: String? = null
        assertTrue(override.set("landscape", then = { told = override.orientation }))
        idle()
        assertEquals(1, refused.asked)
        assertEquals("auto", told)
        assertEquals("auto", override.orientation)

        // An orientation that isn't one runs nothing: the caller answers for itself.
        assertFalse(override.set("sideways", then = { fail("nothing was asked of the window") }))
        idle()

        // What the viewer may no longer ask for is not held either.
        val working = RotationOverride(service)
        assertTrue(working.set("portrait", allowed = { false }, then = { told = working.orientation }))
        idle()
        assertEquals("auto", told)
        assertEquals(1, windows.size) // Only the PC's, from above.
    }

    @Test
    fun nothingIsAddedOnceTheServiceHasGone() {
        receive("pc", message("start-view"))
        // A turn is on its way to the main thread when Android stops the service.
        handleJson.invoke(ConnectionManager, message("rotate").put("orientation", "landscape").put("from", "pc"))
        controller.destroy()
        idle()
        assertEquals(emptyList<View>(), windows)
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
