package co.remotedesktop

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private lateinit var service: InputAccessibilityService

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
        service = Robolectric.buildService(InputAccessibilityService::class.java).create().get()
        instanceField.set(null, service)
        // Left behind by a test that ended with a wake still to be looked at,
        // or with a viewer: the queue they waited in is gone with that test.
        (ScreenWaker::class.java.getDeclaredField("checking").apply { isAccessible = true }.get(ScreenWaker) as AtomicBoolean).set(false)
        lockField("listening").set(LockState, false)
        lockField("wakeFailed").set(LockState, null)
        (lockField("told").get(LockState) as MutableMap<*, *>).clear()
        lockField("lockScreenService").set(LockState, null)
        lockField("lockScreen").set(LockState, LockScreenReader.Found.NOTHING)
        lockField("lockScreenStale").set(LockState, false)
        lockField("rereadPending").set(LockState, false)
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

    private fun receive(from: String, type: String) = receive(from, JSONObject().put("type", type))

    private fun receive(from: String, msg: JSONObject) {
        handleJson.invoke(ConnectionManager, msg.put("from", from))
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

    // ---------- the unlock screen ----------

    @Test
    fun aTapMeantForTheUnlockScreenIsDroppedOnceThePhoneHasUnlocked() {
        val shadow = shadowOf(service as android.accessibilityservice.AccessibilityService)
        fun tap(onlyIfLocked: Boolean?) = JSONObject().put("type", "tap").put("x", 0.5).put("y", 0.7)
            .apply { onlyIfLocked?.let { put("onlyIfLocked", it) } }
        /** How many taps were made, each let finish so the next may play. */
        fun made(): Int {
            shadow.gesturesDispatched.lastOrNull()?.let { it.callback().onCompleted(it.description()) }
            idle()
            return shadow.gesturesDispatched.size
        }

        // On the PIN pad: the key is tapped.
        screen(on = true, locked = true, Intent.ACTION_SCREEN_ON)
        receive("pc", tap(onlyIfLocked = true))
        assertEquals(1, made())

        // The fourth digit unlocked the phone; a fifth is on its way. It is dropped.
        screen(on = true, locked = false, Intent.ACTION_USER_PRESENT)
        receive("pc", tap(onlyIfLocked = true))
        assertEquals(1, made())
        // An ordinary tap, and one that says false, are made as ever.
        receive("pc", tap(onlyIfLocked = null))
        assertEquals(2, made())
        receive("pc", tap(onlyIfLocked = false))
        assertEquals(3, made())

        // Locked when the tap arrived, unlocked by the time it would be made: dropped.
        keyguard.setKeyguardLocked(true)
        handleJson.invoke(ConnectionManager, tap(onlyIfLocked = true).put("from", "pc"))
        keyguard.setKeyguardLocked(false)
        idle()
        assertEquals(3, made())
    }

    private val ui = "com.android.systemui"

    private fun view(pkg: String, id: String?, bounds: Rect, text: String? = null, clickable: Boolean = false,
                     vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().also { node ->
            node.packageName = pkg
            node.viewIdResourceName = id?.let { "$pkg:id/$it" }
            node.setBoundsInScreen(bounds)
            node.text = text
            node.isClickable = clickable
            node.isVisibleToUser = true
            children.forEach { shadowOf(node).addChild(it) }
        }

    /** The pattern screen, on Robolectric's 320 x 470 phone. */
    private fun patternScreen(message: String = "Draw your pattern", pkg: String = ui) = view(pkg, "keyguard_host_view", Rect(0, 0, 320, 470),
        children = arrayOf(
            view(pkg, "sec_bouncer_message_area", Rect(20, 100, 300, 130), text = message),
            view(pkg, "lockPatternView", Rect(40, 188, 280, 428), clickable = true),
        ))

    /** The lock screen before the swipe: a clock and a notification. */
    private fun lockScreen() = view(ui, "notification_panel", Rect(0, 0, 320, 470),
        children = arrayOf(view(ui, "clock_view", Rect(80, 60, 240, 120), text = "12:30"),
            view(ui, "notification_row", Rect(10, 200, 310, 260), text = "Dinner at 7?", clickable = true)))

    /** What is in front changes, and Android tells the service, as it would. */
    private fun inFront(root: AccessibilityNodeInfo?, from: String = ui) {
        shadowOf(service as android.accessibilityservice.AccessibilityService).setRootInActiveWindow(root)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = from })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }

    private fun area(state: JSONObject) = state.getJSONObject("entry").getJSONArray("area").let { a -> List(a.length()) { a.getDouble(it) } }

    @Test
    fun aViewerIsToldWhereThePatternIsDrawnOnceTheUnlockScreenIsUp() {
        screen(on = true, locked = true, Intent.ACTION_SCREEN_ON)
        shadowOf(service as android.accessibilityservice.AccessibilityService).setRootInActiveWindow(lockScreen())
        receive("pc", "start-view")
        // On the lock screen there is nothing to enter, and nothing is said of what it shows.
        lockStates().single().let {
            assertTrue(it.getBoolean("locked"))
            assertFalse(it.has("entry"))
            assertFalse(it.has("message"))
        }
        assertTrue(service.listeningToLockScreen)

        // The swipe: the unlock screen comes up.
        inFront(patternScreen())
        lockStates().last().let {
            assertEquals("pattern", it.getJSONObject("entry").getString("kind"))
            // 40/320, 188/470, 240/320, 240/470.
            assertEquals(listOf(0.125, 0.4, 0.75, 0.5106), area(it))
            assertEquals("Draw your pattern", it.getString("message"))
        }
        assertEquals(2, lockStates().size)

        // A wrong try: only the message changes, and the viewer is told.
        inFront(patternScreen("Incorrect pattern drawn"))
        assertEquals("Incorrect pattern drawn", lockStates().last().getString("message"))
        assertEquals(3, lockStates().size)
        // Something changes that is none of this: nothing new to tell.
        inFront(patternScreen("Incorrect pattern drawn"))
        assertEquals(3, lockStates().size)

        // Back: the lock screen again.
        inFront(lockScreen())
        assertFalse(lockStates().last().has("entry"))
        assertFalse(lockStates().last().has("message"))
        assertEquals(4, lockStates().size)
    }

    /** The same texts are read by RemoteDesktopWin.PhoneViewTests (CheckLockEntry) and by
     *  LockCaptionTest. Change them together. */
    @Test
    fun theUnlockScreenIsDescribedAsTheViewersReadIt() {
        val reading = LockState.Reading(screenOn = true, locked = true)
        fun built(found: LockScreenReader.Found) = JSONObject(LockState.message(reading, null, found).put("to", "pc").toString())
        fun assertSame(shared: String, built: JSONObject) = assertEquals(JSONObject(shared).toString(), built.toString())

        assertSame("""{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pattern","area":[0.1454,0.5141,0.7083,0.3269]},"message":"For your security, draw your pattern.","to":"pc"}""",
            built(LockScreenReader.Found(LockScreenReader.Entry.Pattern(LockScreenReader.Area(0.1454, 0.5141, 0.7083, 0.3269)),
                "For your security, draw your pattern.")))
        assertSame("""{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pin","keys":[{"label":"1","rect":[0.1389,0.375,0.2037,0.0917]},{"label":"0","rect":[0.3981,0.7,0.2037,0.0917]},{"label":"delete","rect":[0.1389,0.7,0.2037,0.0917]},{"label":"ok","rect":[0.6574,0.7,0.2037,0.0917]}]},"message":"Enter PIN","to":"pc"}""",
            built(LockScreenReader.Found(LockScreenReader.Entry.Pin(listOf(
                LockScreenReader.Key("1", LockScreenReader.Area(0.1389, 0.375, 0.2037, 0.0917)),
                LockScreenReader.Key("0", LockScreenReader.Area(0.3981, 0.7, 0.2037, 0.0917)),
                LockScreenReader.Key("delete", LockScreenReader.Area(0.1389, 0.7, 0.2037, 0.0917)),
                LockScreenReader.Key("ok", LockScreenReader.Area(0.6574, 0.7, 0.2037, 0.0917)),
            )), "Enter PIN")))
        assertSame("""{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"password"},"to":"pc"}""",
            built(LockScreenReader.Found(LockScreenReader.Entry.Password, null)))

        // Neither is said of a phone that is unlocked or asleep, whatever was read a moment before.
        val found = LockScreenReader.Found(LockScreenReader.Entry.Password, "Enter password")
        for (other in listOf(LockState.Reading(true, false), LockState.Reading(false, true), LockState.Reading(false, false))) {
            val msg = LockState.message(other, null, found)
            assertFalse(msg.has("entry"))
            assertFalse(msg.has("message"))
        }
    }

    @Test
    fun nothingIsReadUnlessThePhoneIsLockedWithItsScreenOnAndSomeoneIsViewing() {
        val shadow = shadowOf(service as android.accessibilityservice.AccessibilityService)
        // The grid is right there in every case below; whether it is looked at is the question.
        shadow.setRootInActiveWindow(patternScreen())

        // Locked, screen on, nobody viewing.
        screen(on = true, locked = true, Intent.ACTION_SCREEN_ON)
        inFront(patternScreen())
        assertFalse(service.listeningToLockScreen)
        assertEquals(LockScreenReader.Found.NOTHING, lockField("lockScreen").get(LockState))

        // Viewed, but unlocked: the system's own windows (the notification shade, say) are not read.
        screen(on = true, locked = false, Intent.ACTION_USER_PRESENT)
        receive("pc", "start-view")
        inFront(patternScreen())
        assertFalse(service.listeningToLockScreen)
        assertFalse(lockStates().last().has("entry"))
        assertEquals(LockScreenReader.Found.NOTHING, lockField("lockScreen").get(LockState))

        // Viewed and locked, but asleep.
        screen(on = false, locked = true, Intent.ACTION_SCREEN_OFF)
        inFront(patternScreen())
        assertFalse(service.listeningToLockScreen)
        assertFalse(lockStates().last().has("entry"))

        // All three: it is read, at once, without waiting for a change.
        screen(on = true, locked = true, Intent.ACTION_SCREEN_ON)
        assertTrue(service.listeningToLockScreen)
        assertEquals("pattern", lockStates().last().getJSONObject("entry").getString("kind"))

        // Unlocked: reading stops, and what was read is gone.
        screen(on = true, locked = false, Intent.ACTION_USER_PRESENT)
        assertFalse(service.listeningToLockScreen)
        assertFalse(lockStates().last().has("entry"))
        assertEquals(LockScreenReader.Found.NOTHING, lockField("lockScreen").get(LockState))

        // Locked again and read again; then the viewer stops.
        screen(on = true, locked = true, Intent.ACTION_SCREEN_ON)
        assertTrue(service.listeningToLockScreen)
        receive("pc", "stop-view")
        assertFalse(service.listeningToLockScreen)
        assertEquals(LockScreenReader.Found.NOTHING, lockField("lockScreen").get(LockState))
        val told = lockStates().size
        inFront(patternScreen("Incorrect pattern drawn"))
        assertEquals(told, lockStates().size)
    }

    @Test
    fun onlyTheSystemsOwnWindowIsWalked() {
        screen(on = true, locked = true, Intent.ACTION_SCREEN_ON)
        receive("pc", "start-view")
        // An app shown over the lock screen (a call, the camera) with a view of the same name.
        inFront(patternScreen(pkg = "com.example.dialer"))
        assertFalse(lockStates().last().has("entry"))
        assertFalse(lockStates().last().has("message"))
        // And a change in some other app is not a reason to look at all.
        shadowOf(service as android.accessibilityservice.AccessibilityService).setRootInActiveWindow(patternScreen())
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = "com.example.dialer" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertFalse(lockStates().last().has("entry"))
        // Nothing in front at all.
        inFront(null)
        assertFalse(lockStates().last().has("entry"))
    }

    @Test
    fun aLockScreenThatKeepsChangingIsReadOnceForManyChanges() {
        screen(on = true, locked = true, Intent.ACTION_SCREEN_ON)
        receive("pc", "start-view")
        val shadow = shadowOf(service as android.accessibilityservice.AccessibilityService)
        fun changed() = service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = ui })
        shadow.setRootInActiveWindow(patternScreen("Try again in 30 seconds"))
        changed()
        shadow.setRootInActiveWindow(patternScreen("Try again in 29 seconds"))
        changed()
        changed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        // One message, with where it had got to.
        assertEquals(2, lockStates().size)
        assertEquals("Try again in 29 seconds", lockStates().last().getString("message"))
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
