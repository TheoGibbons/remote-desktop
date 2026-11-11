package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Build
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
import org.robolectric.util.ReflectionHelpers

/** The rows of a viewer's menu that are not navigation: media volume, what
 *  the phone says about itself, Quick settings, Screenshot and Lock screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneControlsTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private val instanceField = InputAccessibilityService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }
    private lateinit var service: InputAccessibilityService

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = PeerAuth::class.java.getDeclaredField("trustedPeers")
        .apply { isAccessible = true }.get(PeerAuth) as MutableSet<String>

    private val handleJson = ConnectionManager::class.java.getDeclaredMethod("handleJson", JSONObject::class.java)
        .apply { isAccessible = true }

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val volume get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("appContext", "encKey", "ws", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("state").set(ConnectionManager, "connected")
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("phone", "android", "Test phone"))
        ConnectionManager.peers.add(ConnectionManager.Peer("stranger", "windows", "Stranger"))
        trustedPeers.addAll(listOf("pc", "phone"))
        service = Robolectric.buildService(InputAccessibilityService::class.java).create().get()
        instanceField.set(null, service)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
        ShadowPowerManager.clearWakeLocks()
    }

    @After
    fun cleanup() {
        instanceField.set(null, null)
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun receive(from: String, msg: JSONObject) {
        handleJson.invoke(ConnectionManager, msg.put("from", from))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun message(type: String) = JSONObject().put("type", type)

    private fun states() = socket.messages.filter { it.getString("type") == "phone-state" }

    private fun can(state: JSONObject) = state.getJSONArray("can").let { list -> List(list.length()) { list.getString(it) } }

    private val actions get() = shadowOf(service as AccessibilityService).globalActionsPerformed

    @Test
    fun volumeFromAnApprovedPcChangesTheStreamAndIsAnswered() {
        receive("pc", message("volume").put("level", 11))
        assertEquals(11, volume)
        states().single().let {
            assertEquals("pc", it.getString("to"))
            assertEquals(11, it.getInt("volume"))
            assertEquals(15, it.getInt("volumeMax"))
            assertFalse(it.getBoolean("muted"))
        }
    }

    @Test
    fun aLevelOutOfRangeIsBroughtIntoItAndNonsenseChangesNothing() {
        receive("pc", message("volume").put("level", 99))
        assertEquals(15, volume)
        receive("pc", message("volume").put("level", -3))
        assertEquals(0, volume)
        receive("pc", message("volume").put("level", 6.0))
        assertEquals(6, volume)
        receive("pc", message("volume").put("level", "loud").put("mute", "yes"))
        receive("pc", message("volume"))
        assertEquals(6, volume)
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        // Every one of them is still answered, so the viewer's slider goes back to the truth.
        assertEquals(5, states().size)
        assertEquals(6, states().last().getInt("volume"))
    }

    @Test
    fun muteAndUnmute() {
        receive("pc", message("volume").put("mute", true))
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertTrue(states().last().getBoolean("muted"))
        receive("pc", message("volume").put("mute", false))
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertFalse(states().last().getBoolean("muted"))
    }

    @Test
    fun volumeIsIgnoredWithoutConsentOrApproval() {
        // Not approved by this phone.
        receive("stranger", message("volume").put("level", 2))
        receive("stranger", message("get-phone-state"))
        // Another phone, with Allow other phones off.
        Prefs(context).allowPhones = false
        receive("phone", message("volume").put("level", 2))
        receive("phone", message("get-phone-state"))
        // An approved PC, with the control switch off.
        Prefs(context).allowControl = false
        receive("pc", message("volume").put("level", 2))
        receive("pc", message("volume").put("mute", true))
        receive("pc", message("get-phone-state"))
        assertEquals(7, volume)
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertEquals(emptyList<JSONObject>(), socket.messages)

        // With both switches back on, the other phone may.
        Prefs(context).allowControl = true
        Prefs(context).allowPhones = true
        receive("phone", message("volume").put("level", 2))
        assertEquals(2, volume)
        assertEquals("phone", states().single().getString("to"))
    }

    @Test
    fun thePhoneSaysWhatItsMenuCanOffer() {
        receive("pc", message("get-phone-state"))
        states().single().let {
            assertEquals("pc", it.getString("to"))
            assertEquals(7, it.getInt("volume"))
            assertEquals(15, it.getInt("volumeMax"))
            assertEquals("auto", it.getString("rotation"))
            assertEquals(listOf("volume", "rotate", "quick-settings", "lock", "screenshot"), can(it))
        }

        // Android 8 can open Quick settings but not lock or take a screenshot.
        val sdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 27)
        try {
            receive("pc", message("get-phone-state"))
            assertEquals(listOf("volume", "rotate", "quick-settings"), can(states().last()))
            receive("pc", message("lock"))
            receive("pc", message("screenshot"))
            assertEquals(emptyList<Int>(), actions)
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }

        // With the accessibility service off there is only the volume.
        instanceField.set(null, null)
        receive("pc", message("get-phone-state"))
        assertEquals(listOf("volume"), can(states().last()))
    }

    /** The same text is read by RemoteDesktopWin.PhoneViewTests (CheckPhoneState), so the two
     *  apps can't come to disagree about a field's name or type. Change both together. */
    @Test
    fun phoneStateIsBuiltAsTheWindowsAppReadsIt() {
        val shared = JSONObject(
            """{"type":"phone-state","to":"pc","volume":7,"volumeMax":15,"muted":false,"rotation":"auto",""" +
                """"can":["volume","rotate","quick-settings","lock","screenshot"]}""")
        receive("pc", message("get-phone-state"))
        // As it went over the wire: the socket read it back from text.
        val built = states().single()
        assertEquals(shared.keys().asSequence().toSet(), built.keys().asSequence().toSet())
        for (key in shared.keys()) {
            assertEquals(key, shared.get(key).javaClass, built.get(key).javaClass)
            assertEquals(key, shared.get(key).toString(), built.get(key).toString())
        }
    }

    @Test
    fun aPhoneWithNoVolumeToSetOffersNoSlider() {
        assertEquals(listOf("lock"), can(PhoneControls.state(5, 15, false, volumeFixed = true, rotation = "auto",
            actions = listOf("lock"))))
        assertEquals(emptyList<String>(), can(PhoneControls.state(0, 0, false, volumeFixed = false, rotation = "auto",
            actions = emptyList())))
    }

    @Test
    fun eachMenuActionIsPerformed() {
        receive("pc", message("quick-settings"))
        receive("pc", message("screenshot"))
        receive("pc", message("lock"))
        assertEquals(listOf(
            AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
            AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
        ), actions)
        // They answer nothing: the viewer sees what happened in the picture.
        assertEquals(emptyList<JSONObject>(), socket.messages)
    }

    @Test
    fun menuActionsAreIgnoredWithoutConsentOrApproval() {
        receive("stranger", message("lock"))
        Prefs(context).allowPhones = false
        receive("phone", message("screenshot"))
        Prefs(context).allowControl = false
        receive("pc", message("quick-settings"))
        receive("pc", message("lock"))
        assertEquals(emptyList<Int>(), actions)
    }

    @Test
    fun onlyRealInputWakesASleepingPhone() {
        val power = shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        power.setIsInteractive(false)

        for (quiet in listOf(message("lock"), message("volume").put("level", 3), message("volume").put("mute", true),
            message("get-phone-state"))) {
            receive("pc", quiet)
            assertNull("${quiet.getString("type")} must not turn the screen on", ShadowPowerManager.getLatestWakeLock())
        }
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN), actions)

        for (input in listOf("quick-settings", "screenshot", "back", "notifications")) {
            ShadowPowerManager.clearWakeLocks()
            receive("pc", message(input))
            assertNotNull("$input wakes the phone", ShadowPowerManager.getLatestWakeLock())
        }
    }

    /** Ruling 9 in PHONE-PLANS-ORDER.md, word for word. */
    @Test
    fun whichMessagesCountAsInput() {
        fun wakes(type: String, action: String? = null) =
            InputAccessibilityService.wakesPhone(message(type).apply { action?.let { put("action", it) } })

        for (type in listOf("tap", "swipe", "pinch", "text", "key", "back", "homebtn", "recents", "notifications",
            "quick-settings", "screenshot")) assertTrue(type, wakes(type))
        assertTrue(wakes("touch", "down"))
        assertTrue(wakes("touch", "up"))
        assertFalse(wakes("touch", "move"))
        for (type in listOf("lock", "volume", "rotate", "get-phone-state", "frame-ack", "diagnostic-ping",
            "request-keyframe", "request-smooth", "stop-smooth", "start-view", "something-new")) assertFalse(type, wakes(type))
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
