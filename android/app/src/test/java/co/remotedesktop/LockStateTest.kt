package co.remotedesktop

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** A phone tells whoever is viewing it whether its screen is on and whether
 *  it is locked, and wakes for a viewer only when that viewer starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockStateTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private val instanceField = InputAccessibilityService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun authField(name: String) = PeerAuth::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun lockField(name: String) = LockState::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = authField("trustedPeers").get(PeerAuth) as MutableSet<String>

    private val captureContext = PhoneScreenCapture::class.java.getDeclaredField("appContext")
        .apply { isAccessible = true }

    private val handleJson = ConnectionManager::class.java.getDeclaredMethod("handleJson", JSONObject::class.java)
        .apply { isAccessible = true }

    private val power get() = shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
    private val keyguard get() = shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)

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
        previous["allPeersTrusted"] = authField("allPeersTrusted").get(PeerAuth)
        authField("allPeersTrusted").set(PeerAuth, true)
        // It keeps the context of whichever test first set it up; this test's switches live in this one's.
        previous["capture"] = captureContext.get(PhoneScreenCapture)
        captureContext.set(PhoneScreenCapture, null)
        PhoneScreenCapture.init(context)
        instanceField.set(null, Robolectric.buildService(InputAccessibilityService::class.java).create().get())
        // Left behind by a test that ended with a wake still to be looked at,
        // or with a viewer: the queue they waited in is gone with that test.
        (ScreenWaker::class.java.getDeclaredField("checking").apply { isAccessible = true }.get(ScreenWaker) as AtomicBoolean).set(false)
        lockField("listening").set(LockState, false)
        lockField("wakeFailed").set(LockState, null)
        (lockField("told").get(LockState) as MutableMap<*, *>).clear()
        ShadowPowerManager.clearWakeLocks()
    }

    @After
    fun cleanup() {
        instanceField.set(null, null)
        PhoneScreenCapture.onViewStopped()
        idle()
        captureContext.set(PhoneScreenCapture, previous.remove("capture"))
        authField("allPeersTrusted").set(PeerAuth, previous.remove("allPeersTrusted"))
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun receive(from: String, type: String) {
        handleJson.invoke(ConnectionManager, JSONObject().put("type", type).put("from", from))
        idle()
    }

    /** The phone's screen turns on or off, or its lock screen is passed, and Android says so. */
    private fun screen(on: Boolean, locked: Boolean, action: String) {
        power.setIsInteractive(on)
        keyguard.setKeyguardLocked(locked)
        context.sendBroadcast(Intent(action))
        idle()
    }

    /** A second passes: time enough for the screen to have come on. */
    private fun aSecondLater() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ScreenWaker.CHECK_MS))

    private fun lockStates(to: String? = null) = socket.messages.filter {
        it.getString("type") == "lock-state" && (to == null || it.getString("to") == to)
    }

    /** Whether the phone is listening for its screen and its lock. */
    private val listening get() = shadowOf(context as Application).registeredReceivers
        .any { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) }

    @Test
    fun aViewerIsToldWhenItStarts() {
        receive("pc", "start-view")
        lockStates().single().let {
            assertEquals("pc", it.getString("to"))
            assertTrue(it.getBoolean("screenOn"))
            assertFalse(it.getBoolean("locked"))
            assertFalse(it.has("wakeFailed"))
        }

        // A second viewer is told too, and the first is not told again.
        receive("phone", "start-view")
        assertEquals(listOf("pc", "phone"), lockStates().map { it.getString("to") })
    }

    /** The same texts are read by RemoteDesktopWin.PhoneViewTests (CheckLockState) and by
     *  LockCaptionTest, so the apps can't come to disagree about a field's name or type.
     *  Change them together. */
    @Test
    fun lockStateIsBuiltAsTheViewersReadIt() {
        fun assertSame(shared: String, built: JSONObject) {
            val expected = JSONObject(shared)
            assertEquals(expected.keys().asSequence().toSet(), built.keys().asSequence().toSet())
            for (key in expected.keys()) {
                assertEquals(key, expected.get(key).javaClass, built.get(key).javaClass)
                assertEquals(key, expected.get(key).toString(), built.get(key).toString())
            }
        }
        receive("pc", "start-view")
        // As it went over the wire: the socket read it back from text.
        assertSame("""{"type":"lock-state","to":"pc","screenOn":true,"locked":false}""", lockStates().single())

        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        screen(on = false, locked = true, Intent.ACTION_SCREEN_OFF)
        receive("pc", "back")
        aSecondLater()
        assertSame("""{"type":"lock-state","to":"pc","screenOn":false,"locked":true,"wakeFailed":"notifications"}""",
            lockStates().last())
    }

    @Test
    fun viewersAreToldWhenTheScreenTurnsOffAndOnAndWhenThePhoneIsUnlocked() {
        receive("pc", "start-view")
        receive("phone", "start-view")
        socket.messages.clear()

        screen(on = false, locked = true, Intent.ACTION_SCREEN_OFF)
        assertEquals(setOf("pc", "phone"), lockStates().map { it.getString("to") }.toSet())
        lockStates("pc").single().let {
            assertFalse(it.getBoolean("screenOn"))
            assertTrue(it.getBoolean("locked"))
            // Nobody tried to wake it.
            assertFalse(it.has("wakeFailed"))
        }

        screen(on = true, locked = true, Intent.ACTION_SCREEN_ON)
        lockStates("pc").last().let {
            assertTrue(it.getBoolean("screenOn"))
            assertTrue(it.getBoolean("locked"))
        }

        screen(on = true, locked = false, Intent.ACTION_USER_PRESENT)
        lockStates("pc").last().let {
            assertTrue(it.getBoolean("screenOn"))
            assertFalse(it.getBoolean("locked"))
        }
        assertEquals(3, lockStates("pc").size)
        assertEquals(3, lockStates("phone").size)

        // Android saying again what was already told is not news.
        screen(on = true, locked = false, Intent.ACTION_USER_PRESENT)
        assertEquals(3, lockStates("pc").size)
    }

    @Test
    fun onlyDevicesThatAreViewingAreTold() {
        receive("pc", "start-view")
        socket.messages.clear()
        screen(on = false, locked = false, Intent.ACTION_SCREEN_OFF)
        assertEquals(listOf("pc"), lockStates().map { it.getString("to") })

        // The PC stops: nobody is told, and the phone stops listening.
        receive("pc", "stop-view")
        assertFalse(listening)
        socket.messages.clear()
        screen(on = true, locked = false, Intent.ACTION_SCREEN_ON)
        assertEquals(emptyList<JSONObject>(), socket.messages)

        // A device this phone hasn't approved can't start.
        ConnectionManager.peers.add(ConnectionManager.Peer("stranger", "windows", "Stranger"))
        receive("stranger", "start-view")
        assertEquals(emptyList<JSONObject>(), socket.messages)
        assertFalse(listening)
    }

    @Test
    fun thePhoneListensOnlyWhileSomeoneIsViewing() {
        assertFalse(listening)
        receive("pc", "start-view")
        receive("phone", "start-view")
        assertTrue(listening)
        receive("pc", "stop-view")
        assertTrue(listening)
        // The last viewer leaves the session.
        handleJson.invoke(ConnectionManager, JSONObject().put("type", "peer-left").put("id", "phone"))
        idle()
        assertFalse(listening)
    }

    @Test
    fun nothingIsToldWithControlOff() {
        Prefs(context).allowControl = false
        receive("pc", "start-view")
        assertFalse(listening)
        assertEquals(emptyList<JSONObject>(), socket.messages)

        // Turned off while a PC is viewing: it is told nothing more.
        Prefs(context).allowControl = true
        receive("pc", "start-view")
        assertEquals(1, lockStates().size)
        Prefs(context).allowControl = false
        idle()
        assertFalse(listening)
        screen(on = false, locked = true, Intent.ACTION_SCREEN_OFF)
        assertEquals(1, lockStates().size)
    }

    @Test
    fun nothingIsToldWhileADeviceInTheSessionIsNotApproved() {
        receive("pc", "start-view")
        // The picture stops for everyone until the newcomer is approved or gone; so does this.
        authField("allPeersTrusted").set(PeerAuth, false)
        PhoneScreenCapture.refresh()
        idle()
        assertFalse(listening)
        power.setIsInteractive(false)

        // Once it is gone the PC is told what changed meanwhile.
        authField("allPeersTrusted").set(PeerAuth, true)
        PhoneScreenCapture.refresh()
        idle()
        assertTrue(listening)
        assertEquals(2, lockStates("pc").size)
        assertFalse(lockStates("pc").last().getBoolean("screenOn"))
    }

    @Test
    fun aViewerThatAsksAgainNeitherWakesThePhoneNorIsAnnouncedAgain() {
        receive("pc", "start-view")
        assertEquals(1, ShadowToast.shownToastCount())

        // The phone has gone back to sleep while the PC is still viewing.
        screen(on = false, locked = true, Intent.ACTION_SCREEN_OFF)
        socket.messages.clear()
        receive("pc", "start-view")
        assertNull("Asking again must not turn the screen on", ShadowPowerManager.getLatestWakeLock())
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("It has been told how things stand", emptyList<JSONObject>(), lockStates())

        // Another device starting is new, and does both.
        receive("other-pc", "start-view")
        assertNotNull(ShadowPowerManager.getLatestWakeLock())
        assertEquals(2, ShadowToast.shownToastCount())

        // So does the first one, once it has stopped and starts again.
        ShadowPowerManager.clearWakeLocks()
        receive("pc", "stop-view")
        receive("pc", "start-view")
        assertNotNull(ShadowPowerManager.getLatestWakeLock())
        assertEquals(3, ShadowToast.shownToastCount())
    }

    @Test
    fun aViewerOfASleepingPhoneIsToldOnceItIsKnownWhetherItWoke() {
        power.setIsInteractive(false)
        keyguard.setKeyguardLocked(true)
        receive("pc", "start-view")
        assertNotNull(ShadowPowerManager.getLatestWakeLock())
        assertEquals("Not told it is asleep while it is being woken", emptyList<JSONObject>(), lockStates())

        // It comes on, as Android then says.
        screen(on = true, locked = true, Intent.ACTION_SCREEN_ON)
        lockStates().single().let {
            assertTrue(it.getBoolean("screenOn"))
            assertTrue(it.getBoolean("locked"))
        }
        aSecondLater()
        assertEquals(1, lockStates().size)
    }

    @Test
    fun aViewerIsToldWhenThePhoneDidNotWake() {
        power.setIsInteractive(false)
        receive("pc", "start-view")
        assertEquals(emptyList<JSONObject>(), lockStates())
        aSecondLater()
        lockStates().single().let {
            assertFalse(it.getBoolean("screenOn"))
            assertEquals("unknown", it.getString("wakeFailed"))
        }

        // A tap tries again, fails again, and that is not news.
        receive("pc", "back")
        aSecondLater()
        assertEquals(1, lockStates().size)

        // Someone turns the screen on by hand. It sleeps again: nobody has tried to wake it since.
        screen(on = true, locked = false, Intent.ACTION_SCREEN_ON)
        assertFalse(lockStates().last().has("wakeFailed"))
        screen(on = false, locked = false, Intent.ACTION_SCREEN_OFF)
        assertFalse(lockStates().last().getBoolean("screenOn"))
        assertFalse(lockStates().last().has("wakeFailed"))
        assertEquals(3, lockStates().size)
    }

    @Test
    fun withNotificationsOffTheViewerIsToldThatIsWhy() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        power.setIsInteractive(false)
        receive("pc", "start-view")
        aSecondLater()
        assertEquals("notifications", lockStates().single().getString("wakeFailed"))

        // A viewer that starts later is told the same, after its own try.
        receive("phone", "start-view")
        assertEquals(emptyList<JSONObject>(), lockStates("phone"))
        aSecondLater()
        assertEquals("notifications", lockStates("phone").single().getString("wakeFailed"))
        assertEquals(1, lockStates("pc").size)
    }

    @Test
    fun theMessageSaysAWakeFailedOnlyWhileTheScreenIsOff() {
        val on = LockState.message(LockState.Reading(screenOn = true, locked = true), wakeFailed = "notifications")
        assertFalse(on.has("wakeFailed"))
        assertFalse(on.has("to"))
        val off = LockState.message(LockState.Reading(screenOn = false, locked = false), wakeFailed = "notifications")
        assertEquals("notifications", off.getString("wakeFailed"))
        assertTrue(LockState.Reading(screenOn = true, locked = false).inUse)
        assertFalse(LockState.Reading(screenOn = true, locked = true).inUse)
        assertFalse(LockState.Reading(screenOn = false, locked = false).inUse)
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
