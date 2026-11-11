package co.remotedesktop

import android.content.Context
import android.os.Looper
import android.util.Base64
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
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

/**
 * Messages as they come off the socket, good and bad. Whatever a device in
 * the session sends, the phone must stay on the relay: an exception that gets
 * out of the socket's listener makes OkHttp drop the connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageGuardTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun authField(name: String) = PeerAuth::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = authField("trustedPeers").get(PeerAuth) as MutableSet<String>

    private val captureContext = PhoneScreenCapture::class.java.getDeclaredField("appContext")
        .apply { isAccessible = true }

    /** The app's own listener, as OkHttp calls it. */
    private val listener get() = connectionField("listener").get(ConnectionManager) as WebSocketListener

    /** What the listener hands a message to, without the listener's guard round it. */
    private val handleJson = ConnectionManager::class.java.getDeclaredMethod("handleJson", JSONObject::class.java)
        .apply { isAccessible = true }

    private val instanceField = InputAccessibilityService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("appContext", "encKey", "ws", "fs", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("fs").set(ConnectionManager, FsHandler(context))
        connectionField("state").set(ConnectionManager, "connected")
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("other-pc", "windows", "Other PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("stranger", "windows", "Stranger"))
        trustedPeers.addAll(listOf("pc", "other-pc"))
        previous["allPeersTrusted"] = authField("allPeersTrusted").get(PeerAuth)
        authField("allPeersTrusted").set(PeerAuth, true)
        previous["capture"] = captureContext.get(PhoneScreenCapture)
        captureContext.set(PhoneScreenCapture, null)
        PhoneScreenCapture.init(context)
        // Accessibility is on, as on any phone that can be controlled: taps are acted on.
        instanceField.set(null, Robolectric.buildService(InputAccessibilityService::class.java).create().get())
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

    /** A text frame arrives, exactly as written. */
    private fun arrives(text: String) {
        listener.onMessage(socket, text)
        idle()
    }

    /** What the relay hands on from [from]: an envelope round a message encrypted with the session key. */
    private fun sealed(inner: String, from: String) = JSONObject().put("type", "enc").put("from", from)
        .put("d", Base64.encodeToString(Crypto.encrypt(socket.key, inner.toByteArray(), Crypto.CH_JSON), Base64.NO_WRAP))
        .toString()

    private fun frame(type: Byte, payload: ByteArray): ByteString =
        (byteArrayOf(type) + Crypto.encrypt(socket.key, payload, type)).toByteString()

    /** Whether handling this message throws, once it is past the listener: what the listener's guard is there for. */
    private fun throwsWhereItIsHandled(message: String, from: String? = null): Boolean = try {
        handleJson.invoke(ConnectionManager, JSONObject(message).apply { if (from != null) put("from", from) })
        false
    } catch (e: java.lang.reflect.InvocationTargetException) {
        true
    }

    @Test
    fun aMessageThePhoneChokesOnIsSkippedAndTheNextOneIsHeard() {
        // The relay's own word with what it should hold missing, and a tap
        // from an approved device that does not say where.
        val fromTheRelay = listOf("""{"type":"peer-joined"}""", """{"type":"peer-joined","peer":{"id":5}}""")
        val fromADevice = listOf("""{"type":"tap","x":"left"}""", """{"type":"tap"}""", """{"type":"swipe","x1":0.5}""")
        // Each of them really does throw where it is handled. If one stops
        // doing so, it proves nothing below and another has to be found.
        for (text in fromTheRelay) assertTrue(text, throwsWhereItIsHandled(text))
        for (inner in fromADevice) assertTrue(inner, throwsWhereItIsHandled(inner, from = "pc"))

        // Through the listener, as OkHttp hands them over, nothing gets out:
        // the test fails here if anything does.
        for (text in fromTheRelay) arrives(text)
        for (inner in fromADevice) arrives(sealed(inner, "pc"))

        // The same for whatever goes wrong further on, in a screen that is
        // listening, with a message or with a picture.
        var choked = 0
        val onJson: (JSONObject) -> Unit = { choked++; throw IllegalStateException("a screen choked on a message") }
        val onFrame: (ByteArray) -> Unit = { choked++; throw IllegalStateException("a screen choked on a frame") }
        ConnectionManager.networkJsonListeners.add(onJson)
        ConnectionManager.binaryListeners.add(onFrame)
        try {
            arrives(sealed("""{"type":"something-new"}""", "pc"))
            listener.onMessage(socket, frame(Crypto.CH_VIDEO, ByteArray(4)))
            listener.onMessage(socket, frame(Crypto.CH_PHONE, ByteArray(5)))
        } finally {
            ConnectionManager.networkJsonListeners.remove(onJson)
            ConnectionManager.binaryListeners.remove(onFrame)
        }
        assertEquals("each reached the screen that choked on it", 3, choked)

        // The phone is still listening.
        assertFalse(PhoneScreenCapture.isViewing("pc"))
        arrives(sealed("""{"type":"start-view"}""", "pc"))
        assertTrue(PhoneScreenCapture.isViewing("pc"))
    }

    @Test
    fun whatIsNotAMessageIsHandedToNobody() {
        val heard = mutableListOf<String>()
        val hear: (JSONObject) -> Unit = { heard.add(it.toString()) }
        ConnectionManager.networkJsonListeners.add(hear)
        try {
            // Not JSON, JSON that is no object, and an envelope with nothing readable in it.
            for (text in listOf("null", "5", "[1]", "\"x\"", "{", "", """{"type":"enc"}""", """{"type":"enc","d":5}""",
                """{"type":"enc","d":"not base64!"}""", """{"type":"enc","d":"AAAA"}"""))
                arrives(text)
            // Encrypted with the session key, as any device in the session can, and still no message.
            for (inner in listOf("null", "[1]", "7", "not json")) {
                arrives(sealed(inner, "pc"))
                arrives(sealed(inner, "stranger"))
            }
            assertEquals(emptyList<String>(), heard)
            // A list of codecs that is no list of names is a viewer that named none.
            arrives(sealed("""{"type":"start-view","codecs":[5,null,{"a":1}]}""", "pc"))
            assertEquals(1, heard.size)
            assertTrue(PhoneScreenCapture.isViewing("pc"))
        } finally {
            ConnectionManager.networkJsonListeners.remove(hear)
        }
    }

    @Test
    fun onlyTheRelaySaysWhoIsInTheSessionAndOnlyEncryptedMessagesAreADevices() {
        // A device's message that is not encrypted is not heard, though the
        // relay names an approved device as its sender. Without this, the
        // session key would not be needed to control the phone.
        arrives("""{"type":"start-view","from":"pc"}""")
        assertFalse(PhoneScreenCapture.isViewing("pc"))
        arrives(sealed("""{"type":"start-view"}""", "pc"))
        assertTrue(PhoneScreenCapture.isViewing("pc"))

        // A device posing as the relay, encrypted or not, to have a viewer dropped.
        val heard = mutableListOf<String>()
        val screens: (JSONObject) -> Unit = { heard.add(it.optString("type")) }
        ConnectionManager.jsonListeners.add(screens)
        try {
            arrives("""{"type":"peer-left","id":"pc","from":"stranger"}""")
            arrives(sealed("""{"type":"peer-left","id":"pc"}""", "stranger"))
            arrives(sealed("""{"type":"welcome","id":"9","peers":[]}""", "stranger"))
            arrives(sealed("""{"type":"error","message":"banned","retryAfter":3600}""", "stranger"))
            arrives(sealed("""{"type":"peer-joined","peer":{"id":"x","device":"windows","name":"Ghost"}}""", "stranger"))
            assertTrue(PhoneScreenCapture.isViewing("pc"))
            assertEquals(listOf("pc", "other-pc", "stranger"), ConnectionManager.peers.map { it.id })
            assertEquals("connected", ConnectionManager.state)
            // The screens are not told either: a viewer closes on peer-left.
            assertEquals(emptyList<String>(), heard)

            // The sender is who the relay says, whatever the message itself claims.
            arrives(sealed("""{"type":"stop-view","from":"pc"}""", "stranger"))
            assertTrue(PhoneScreenCapture.isViewing("pc"))

            // The relay's own word, which has no sender.
            arrives("""{"type":"peer-left","id":"pc"}""")
            assertFalse(PhoneScreenCapture.isViewing("pc"))
            assertEquals(listOf("other-pc", "stranger"), ConnectionManager.peers.map { it.id })
            // (The stop-view is passed on like any message; each screen looks at who sent it.)
            assertEquals(listOf("stop-view", "peer-left"), heard)
        } finally {
            ConnectionManager.jsonListeners.remove(screens)
        }
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
