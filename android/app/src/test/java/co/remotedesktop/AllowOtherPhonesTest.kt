package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Looper
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

/** Another phone may view, control and browse this one only while Allow
 *  other phones is on; PCs never depend on it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AllowOtherPhonesTest {
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

    private val captureContext = PhoneScreenCapture::class.java.getDeclaredField("appContext")
        .apply { isAccessible = true }

    private val handleJson = ConnectionManager::class.java.getDeclaredMethod("handleJson", JSONObject::class.java)
        .apply { isAccessible = true }

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).deviceName = "Pixel"
        for (name in listOf("appContext", "encKey", "ws", "fs", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("fs").set(ConnectionManager, FsHandler(context))
        connectionField("state").set(ConnectionManager, "connected")
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("phone", "android", "Test phone"))
        trustedPeers.addAll(listOf("pc", "phone"))
        // It keeps the context of whichever test first set it up; this test's switch lives in this one's.
        previous["capture"] = captureContext.get(PhoneScreenCapture)
        captureContext.set(PhoneScreenCapture, null)
        PhoneScreenCapture.init(context)
        service = Robolectric.buildService(InputAccessibilityService::class.java).create().get()
        instanceField.set(null, service)
    }

    @After
    fun cleanup() {
        instanceField.set(null, null)
        PhoneScreenCapture.onViewStopped()
        idle()
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

    private fun reply(type: String) = socket.messages.last { it.getString("type") == type }

    private val actions get() = shadowOf(service as AccessibilityService).globalActionsPerformed

    @Test
    fun itIsOnByDefault() {
        assertTrue(Prefs(context).allowPhones)
        receive("phone", JSONObject().put("type", "notifications"))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS), actions)
    }

    @Test
    fun theFileAccessReplyTellsAPhoneWhetherItMayOpenThisOne() {
        receive("phone", JSONObject().put("type", "get-file-access"))
        reply("file-access").let {
            assertEquals("phone", it.getString("to"))
            assertTrue(it.getBoolean("allowed"))
            assertTrue(it.getBoolean("phones"))
        }

        Prefs(context).allowPhones = false
        receive("phone", JSONObject().put("type", "get-file-access"))
        reply("file-access").let {
            assertFalse(it.getBoolean("allowed"))
            assertFalse(it.getBoolean("phones"))
        }
        // A PC may still upload.
        receive("pc", JSONObject().put("type", "get-file-access"))
        reply("file-access").let {
            assertEquals("pc", it.getString("to"))
            assertTrue(it.getBoolean("allowed"))
        }

        // Nothing for a device this phone hasn't approved.
        ConnectionManager.peers.add(ConnectionManager.Peer("stranger", "android", "Stranger"))
        socket.messages.clear()
        receive("stranger", JSONObject().put("type", "get-file-access"))
        assertEquals(emptyList<JSONObject>(), socket.messages)
    }

    @Test
    fun withTheSwitchOffAPhoneIsRefusedAndAPcIsNot() {
        Prefs(context).allowPhones = false

        receive("phone", JSONObject().put("type", "back"))
        receive("phone", JSONObject().put("type", "notifications"))
        assertEquals(emptyList<Int>(), actions)
        receive("pc", JSONObject().put("type", "back"))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), actions)

        receive("phone", JSONObject().put("type", "start-view"))
        assertFalse(PhoneScreenCapture.isViewing("phone"))
        receive("pc", JSONObject().put("type", "start-view"))
        assertTrue(PhoneScreenCapture.isViewing("pc"))

        receive("phone", JSONObject().put("type", "fs-list").put("path", "").put("reqId", "1"))
        assertEquals("Allow other phones is off on Pixel", reply("fs-list-result").getString("error"))
        receive("phone", JSONObject().put("type", "fs-get").put("path", "/sdcard/a.txt").put("xferId", 7))
        reply("fs-end").let {
            assertFalse(it.getBoolean("ok"))
            assertEquals("Allow other phones is off on Pixel", it.getString("error"))
        }
        receive("pc", JSONObject().put("type", "fs-list").put("path", "").put("reqId", "2"))
        assertNotEquals("Allow other phones is off on Pixel", reply("fs-list-result").opt("error"))
    }

    @Test
    fun turningTheSwitchOffEndsAPhonesViewAtOnce() {
        receive("phone", JSONObject().put("type", "start-view"))
        receive("pc", JSONObject().put("type", "start-view"))
        assertEquals(setOf("phone", "pc"), PhoneScreenCapture.recipients().toSet())

        Prefs(context).allowPhones = false
        idle()
        assertFalse(PhoneScreenCapture.isViewing("phone"))
        assertEquals(listOf("pc"), PhoneScreenCapture.recipients())
    }

    @Test
    fun videoIsSkippedLongBeforeFileChunks() {
        socket.queued = 400_000
        assertFalse(ConnectionManager.hasRoomForVideo())
        assertFalse(ConnectionManager.sendBinary(Crypto.CH_VIDEO, ByteArray(10)))
        // The same picture behind a header, for a viewer on a current app.
        assertFalse(ConnectionManager.sendBinary(Crypto.CH_PHONE, ByteArray(10)))
        assertTrue(ConnectionManager.sendBinary(Crypto.CH_FILE, ByteArray(10)))
        socket.queued = 200_000
        assertTrue(ConnectionManager.hasRoomForVideo())
        assertTrue(ConnectionManager.sendBinary(Crypto.CH_VIDEO, ByteArray(10)))
        assertTrue(ConnectionManager.sendBinary(Crypto.CH_PHONE, ByteArray(10)))
        socket.queued = 7_000_000
        assertFalse(ConnectionManager.sendBinary(Crypto.CH_FILE, ByteArray(10)))
    }

    private class RecordingSocket : WebSocket {
        val key = ByteArray(32) { 7 }
        val messages = mutableListOf<JSONObject>()
        var queued = 0L
        override fun request() = Request.Builder().url("https://example.test/").build()
        override fun queueSize() = queued
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
