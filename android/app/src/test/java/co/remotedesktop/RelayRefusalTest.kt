package co.remotedesktop

import android.content.Context
import android.os.Looper
import android.text.format.DateFormat
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystem
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Date

/** These tests talk to a fake relay, so skip the hardware-backed identity key. */
@Implements(value = PeerAuth::class, isInAndroidSdk = false)
class ShadowRelayRefusalAuth {
    @Implementation fun init(context: Context) = Unit
    @Implementation fun onSessionStart(newPairId: String) = Unit
    @Implementation fun getOwnFingerprint() = "a3f29c41a3f29c41"
}

/** Just enough of a WebSocket server (RFC 6455) to answer the app the way
 *  server.js does, e.g. refusing it with an `error` message and a close frame. */
private class FakeRelay : Closeable {
    private val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    val url = "ws://127.0.0.1:${server.localPort}/ws"

    /** The next connection, upgraded, or null if none arrives in time. */
    fun accept(timeoutMs: Int): Connection? {
        server.soTimeout = timeoutMs
        val socket = try { server.accept() } catch (_: SocketTimeoutException) { return null }
        socket.soTimeout = 5_000
        val input = DataInputStream(socket.getInputStream())
        val request = StringBuilder()
        while (!request.endsWith("\r\n\r\n")) request.append(input.readByte().toInt().toChar())
        val key = request.lines().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
            .substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        return Connection(socket, input)
    }

    override fun close() = server.close()

    class Connection(private val socket: Socket, private val input: DataInputStream) : Closeable {
        /** One frame from the app: its opcode and unmasked payload. */
        fun read(): Pair<Int, ByteArray> {
            val opcode = input.readUnsignedByte() and 0x0f
            val second = input.readUnsignedByte()
            val length = when (val n = second and 0x7f) {
                126 -> input.readUnsignedShort()
                127 -> input.readLong().toInt()
                else -> n
            }
            val mask = ByteArray(4).also { if (second and 0x80 != 0) input.readFully(it) }
            val payload = ByteArray(length).also { input.readFully(it) }
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return opcode to payload
        }

        fun sendText(text: String) = send(0x1, text.toByteArray())

        fun sendClose(code: Int, reason: String) =
            send(0x8, byteArrayOf((code shr 8).toByte(), code.toByte()) + reason.toByteArray())

        private fun send(opcode: Int, payload: ByteArray) {
            require(payload.size < 126)
            socket.getOutputStream().write(byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte()) + payload)
        }

        override fun close() = socket.close()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowRelayRefusalAuth::class, ShadowSessionEnvironment::class],
    instrumentedPackages = ["co.remotedesktop"])
class RelayRefusalTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).autoConnect = false
        ConnectionManager.stop()
        // Re-initialised by start() against this test's application.
        connectionField("appContext").set(ConnectionManager, null)
    }

    @After
    fun cleanup() {
        ConnectionManager.stop()
        connectionField("appContext").set(ConnectionManager, null)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun banShowsAcrossRetriesUntilTheRelayAdmitsThePhone() {
        FakeRelay().use { relay ->
            Prefs(context).apply {
                serverUrl = relay.url
                sessionKey = "relay-refusal-test-key-0123456789"
            }
            ConnectionManager.start(context)

            relay.accept(5_000)!!.use { refused ->
                assertEquals("hello", JSONObject(String(refused.read().second)).getString("type"))
                refused.sendText("""{"type":"error","code":"banned",""" +
                    """"message":"too many join attempts from this network","retryAfter":600}""")
                refused.sendClose(4001, "banned")
                // Answered at once. It used to sit until a ping went unanswered (~40 s).
                assertEquals(0x8, refused.read().first)
            }
            // The clock app code sees under Robolectric, not the real one.
            val now = ShadowSystem.currentTimeMillis()
            assertEquals("blocked", ConnectionManager.state)
            assertTrue(ConnectionManager.blockedUntilMs in now + 590_000..now + 600_000)

            // The retry goes out on the usual backoff and keeps the ban showing.
            acceptRetry(relay).use { retry ->
                assertEquals("blocked", ConnectionManager.state)
                assertEquals("hello", JSONObject(String(retry.read().second)).getString("type"))
                // The ban lifted (or the relay restarted): the phone is admitted.
                retry.sendText("""{"type":"welcome","id":"7","peers":[]}""")
                waitFor { ConnectionManager.state == "connected" }
                assertEquals(0L, ConnectionManager.blockedUntilMs)
            }
        }
    }

    @Test
    fun mainCardShowsTheBanAndTheRelaysReason() {
        val until = System.currentTimeMillis() + 600_000
        connectionField("blockedUntilMs").set(ConnectionManager, until)
        connectionField("state").set(ConnectionManager, "blocked")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val title = activity.findViewById<TextView>(R.id.statusTitle)
            val subtitle = activity.findViewById<TextView>(R.id.statusSubtitle)
            assertEquals("Blocked until " + DateFormat.getTimeFormat(activity).format(Date(until)),
                title.text.toString())
            assertEquals("Too many join attempts from this network. Retrying automatically.",
                subtitle.text.toString())

            connectionField("state").set(ConnectionManager, "error: session key must be at least 16 characters")
            controller.pause().resume()
            assertEquals("Connection error", title.text.toString())
            assertEquals("Session key must be at least 16 characters", subtitle.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Runs the main looper until the delayed reconnect reaches the relay. */
    private fun acceptRetry(relay: FakeRelay): FakeRelay.Connection {
        repeat(40) {
            main.idleFor(Duration.ofSeconds(5))
            relay.accept(250)?.let { return it }
        }
        throw AssertionError("the phone never retried")
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }
}
