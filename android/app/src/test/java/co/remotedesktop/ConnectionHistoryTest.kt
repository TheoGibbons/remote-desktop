package co.remotedesktop

import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
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
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystem
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSessionPeerAuth::class, ShadowSessionEnvironment::class],
    instrumentedPackages = ["co.remotedesktop"])
class ConnectionHistoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }
    private val key = "connection-history-test-key-0123456789"
    private val welcome = """{"type":"welcome","id":"7","peers":[]}"""

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).autoConnect = false
        ConnectionManager.stop()
        // Re-initialised by start() against this test's application and its storage.
        connectionField("appContext").set(ConnectionManager, null)
        ConnectionManager.init(context)
        ConnectionLog.clear()
    }

    @After
    fun cleanup() {
        ConnectionManager.stop()
        connectionField("appContext").set(ConnectionManager, null)
        ConnectionLog.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun entries(vararg kinds: ConnectionLog.Kind) =
        ConnectionLog.entries().filter { it.kind in kinds }

    @Test
    fun aDropIsRecordedWithThePhonesReasonAndHowLongItWasUp() {
        FakeRelay().use { relay ->
            Prefs(context).apply { serverUrl = relay.url; sessionKey = key }
            ConnectionManager.start(context)
            relay.accept(5_000)!!.use { conn ->
                conn.read() // hello
                conn.sendText(welcome)
                waitFor { ConnectionManager.state == "connected" }
                assertEquals(ConnectionLog.Kind.CONNECTED, ConnectionLog.entries().first().kind)
                main.idleFor(Duration.ofSeconds(90))
            } // The socket just ends, with no close frame, like a network that died.
            waitFor { ConnectionLog.entries().first().kind == ConnectionLog.Kind.LOST }
            val lost = ConnectionLog.entries().first()
            // The OS decides between EOF, reset and abort; reasonsSayWhichEndGaveUp covers the wording.
            assertNotNull(lost.reason)
            assertNotNull(lost.detail)
            assertTrue("${lost.durationMs}", lost.durationMs in 90_000L..95_000L)
            waitFor { ConnectionManager.state == "disconnected: retrying..." }

            acceptRetry(relay).use { again ->
                again.read()
                again.sendText(welcome)
                waitFor { ConnectionManager.state == "connected" }
                // Checked while the socket is open: closing it is the next drop.
                val back = ConnectionLog.entries().first()
                assertEquals(ConnectionLog.Kind.CONNECTED, back.kind)
                // The retry waited out the first 2 s backoff.
                assertTrue("${back.durationMs}", back.durationMs >= 2_000)
                assertTrue(ConnectionLog.title(back).startsWith("Connected after "))
                assertNull(ConnectionLog.currentProblem())
                assertEquals(lost, ConnectionLog.lastDrop())
                ConnectionManager.stop()
            }
        }
    }

    @Test
    fun repeatedRefusalsMergeIntoOneEntryWithTheRelaysReason() {
        FakeRelay().use { relay ->
            Prefs(context).apply { serverUrl = relay.url; sessionKey = key }
            ConnectionManager.start(context)
            relay.accept(5_000)!!.use { refuse(it) }
            acceptRetry(relay).use { refuse(it) }
            waitFor { entries(ConnectionLog.Kind.FAILED).firstOrNull()?.count == 2 }
            val failed = entries(ConnectionLog.Kind.FAILED).single()
            assertEquals("Refused by the relay: too many connections from this network", failed.reason)
            assertEquals("Close code 4002 (too-many-connections)", failed.detail)
            assertEquals("Couldn't connect (2 tries)", ConnectionLog.title(failed))
            assertTrue(failed.untilMs >= failed.atMs)
            assertEquals(failed, ConnectionLog.currentProblem())
        }
    }

    @Test
    fun aNetworkComingUpRetriesAtOnceButASignalChangeDoesNot() {
        FakeRelay().use { relay ->
            Prefs(context).apply { serverUrl = relay.url; sessionKey = key }
            ConnectionManager.start(context)
            relay.accept(5_000)!!.use { refuse(it) }
            // Waiting out the backoff, which only the main looper's clock ends.
            waitForRetry()

            val callback = connectionField("networkCallback").get(ConnectionManager) as ConnectivityManager.NetworkCallback
            val cm = context.getSystemService(ConnectivityManager::class.java)
            assertTrue(callback in shadowOf(cm).networkCallbacks)
            connectionField("networkName").set(ConnectionManager, null)
            val wifiNet = ShadowNetwork.newInstance(41)
            callback.onCapabilitiesChanged(wifiNet, caps(NetworkCapabilities.TRANSPORT_WIFI)) // where it is now
            callback.onCapabilitiesChanged(wifiNet, caps(NetworkCapabilities.TRANSPORT_WIFI)) // the signal moved
            assertNull(relay.accept(500))
            assertTrue(entries(ConnectionLog.Kind.NETWORK).isEmpty())

            // The VPN moves to mobile data: a different network, so try it now.
            callback.onCapabilitiesChanged(wifiNet,
                caps(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_VPN))
            relay.accept(10_000)!!.use { refuse(it) }
            val moved = entries(ConnectionLog.Kind.NETWORK).single()
            assertEquals("Mobile data via VPN", moved.reason)
            assertEquals("was Wi-Fi", moved.detail)

            // Offline, then a network comes up: try at once, not in up to 30 s.
            waitForRetry()
            callback.onLost(wifiNet)
            assertNull(relay.accept(300))
            val cellNet = ShadowNetwork.newInstance(42)
            shadowOf(cm).setNetworkCapabilities(cellNet, caps(NetworkCapabilities.TRANSPORT_CELLULAR))
            callback.onAvailable(cellNet)
            relay.accept(10_000)!!.use { again ->
                again.read()
                again.sendText(welcome)
                waitFor { ConnectionManager.state == "connected" }
            }
            assertEquals(listOf("Mobile data", "No network", "Mobile data via VPN"),
                entries(ConnectionLog.Kind.NETWORK).map { it.reason })
        }
    }

    @Test
    fun theHistorySurvivesARestartAndKeepsItsNewestEntries() {
        repeat(ConnectionLog.MAX_ENTRIES + 5) { i ->
            ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.STOPPED, i.toLong()))
        }
        ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.FAILED, 1_000, "Couldn't reach the relay",
            "ConnectException: refused", "Wi-Fi"))
        ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.FAILED, 2_000, "Couldn't reach the relay",
            "ConnectException: refused again", "Wi-Fi"))

        // As after the app restarts: read back from storage.
        ConnectionLog::class.java.getDeclaredField("file").apply { isAccessible = true }.set(ConnectionLog, null)
        ConnectionLog.init(context)
        val loaded = ConnectionLog.entries()
        val saved = context.filesDir.listFiles()?.joinToString { "${it.name} (${it.length()} bytes)" }
        assertEquals("files: $saved", ConnectionLog.MAX_ENTRIES, loaded.size)
        assertEquals(ConnectionLog.Entry(ConnectionLog.Kind.FAILED, 1_000, "Couldn't reach the relay",
            "ConnectException: refused again", "Wi-Fi", count = 2, untilMs = 2_000), loaded.first())
        // The oldest fell off the end.
        assertEquals(6L, loaded.last().atMs)
    }

    @Test
    fun copiedTextLinesUpWithTheRelaysUtcLog() {
        val at = java.time.Instant.parse("2026-11-01T09:56:25Z").toEpochMilli()
        val entries = listOf(
            ConnectionLog.Entry(ConnectionLog.Kind.CONNECTED, at + 10_000, network = "Wi-Fi via VPN", durationMs = 10_400),
            ConnectionLog.Entry(ConnectionLog.Kind.LOST, at, "No reply to a ping within 20 s",
                "SocketTimeoutException: sent ping but didn't receive pong within 20000ms (after 1493 successful ping/pongs)",
                "Wi-Fi via VPN", durationMs = (8 * 3600 + 18 * 60 + 5) * 1000L),
            ConnectionLog.Entry(ConnectionLog.Kind.NETWORK, at - 2_000, "Mobile data via VPN", "was Wi-Fi via VPN"),
            ConnectionLog.Entry(ConnectionLog.Kind.FAILED, at - 60_000, "Couldn't reach the relay", "ConnectException: x",
                "Wi-Fi", count = 3, untilMs = at - 30_000),
        )
        assertEquals(
            "2026-11-01 02:56:35 MST (09:56:35 UTC)  Connected after 10 s offline. Wi-Fi via VPN\n" +
            "2026-11-01 02:56:25 MST (09:56:25 UTC)  Lost the connection after 8 h 18 m. " +
                "No reply to a ping within 20 s. Wi-Fi via VPN [SocketTimeoutException: sent ping but didn't " +
                "receive pong within 20000ms (after 1493 successful ping/pongs)]\n" +
            "2026-11-01 02:56:23 MST (09:56:23 UTC)  Network: Mobile data via VPN. was Wi-Fi via VPN\n" +
            "2026-11-01 02:55:25 MST (09:55:25 UTC)  Couldn't connect (3 tries), the last at 09:55:55 UTC. " +
                "Couldn't reach the relay. Wi-Fi [ConnectException: x]",
            ConnectionLog.toText(entries, TimeZone.getTimeZone("America/Phoenix")))
    }

    @Test
    fun reasonsSayWhichEndGaveUp() {
        assertEquals("No reply to a ping within 20 s", ConnectionLog.reasonFor(SocketTimeoutException(
            "sent ping but didn't receive pong within 20000ms (after 12 successful ping/pongs)")))
        assertEquals("Timed out waiting for the relay", ConnectionLog.reasonFor(SocketTimeoutException("timeout")))
        assertEquals("This phone's network went away",
            ConnectionLog.reasonFor(SocketException("Software caused connection abort")))
        assertEquals("The connection was cut", ConnectionLog.reasonFor(SocketException("Connection reset")))
        assertEquals("Couldn't look up the relay's address",
            ConnectionLog.reasonFor(UnknownHostException("Unable to resolve host \"relay.remote-desktop.co\"")))
        assertEquals("The relay answered HTTP 502 instead of connecting",
            ConnectionLog.reasonFor(java.net.ProtocolException("Expected HTTP 101 response but was '502 Bad Gateway'"), 502))
        assertEquals("SocketException: Connection reset", ConnectionLog.detailFor(SocketException("Connection reset")))
        assertEquals("EOFException", ConnectionLog.detailFor(java.io.EOFException()))
    }

    @Test
    fun theScreensShowWhyThePhoneIsOffline() {
        val now = ShadowSystem.currentTimeMillis()
        ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.CONNECTED, now - 60_000, network = "Wi-Fi"))
        ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.LOST, now - 5_000, "No reply to a ping within 20 s",
            "SocketTimeoutException: sent ping", "Wi-Fi", durationMs = 55_000))
        ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.NETWORK, now - 1_000, "Mobile data", "was Wi-Fi"))
        connectionField("state").set(ConnectionManager, "disconnected: retrying...")
        Prefs(context).sessionKey = key

        Robolectric.buildActivity(MainActivity::class.java).setup().also { controller ->
            assertEquals("No reply to a ping within 20 s. Retrying automatically.",
                controller.get().findViewById<TextView>(R.id.statusSubtitle).text.toString())
            controller.pause().stop().destroy()
        }

        Robolectric.buildActivity(SettingsActivity::class.java).setup().also { controller ->
            val value = controller.get().findViewById<TextView>(R.id.connectionHistoryValue).text.toString()
            assertTrue(value, value.startsWith("Last drop ") && value.endsWith(": No reply to a ping within 20 s"))
            controller.pause().stop().destroy()
        }

        val controller = Robolectric.buildActivity(ConnectionHistoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            val group = activity.findViewById<LinearLayout>(R.id.historyGroup)
            val texts = texts(group)
            // Newest first.
            val order = listOf("Network: Mobile data", "Lost the connection after 55 s", "Connected")
                .map { title -> texts.indexOf(title) }
            assertTrue("$texts", order.all { it >= 0 } && order == order.sorted())
            assertTrue(texts.contains("No reply to a ping within 20 s"))
            assertTrue(texts.contains("Was Wi-Fi"))
            assertTrue(texts.contains("SocketTimeoutException: sent ping"))

            // A new entry shows without reopening the screen.
            ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.CONNECTED, now, network = "Mobile data", durationMs = 5_000))
            main.idle()
            assertEquals("Connected after 5.0 s offline", texts(group).first())

            val menu = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu
            assertEquals("Copy", menu.getItem(0).title.toString())
            assertTrue(menu.performIdentifierAction(menu.getItem(0).itemId, 0))
            val copied = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied, copied.startsWith("Remote Desktop connection history: "))
            assertTrue(copied, copied.contains("Lost the connection after 55 s. No reply to a ping within 20 s. Wi-Fi " +
                "[SocketTimeoutException: sent ping]"))
            assertEquals(5, copied.lines().size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun videoGoingBackToPicturesIsALineInTheHistoryAndNoConnectionProblem() {
        val now = ShadowSystem.currentTimeMillis()
        val drop = ConnectionLog.Entry(ConnectionLog.Kind.LOST, now - 5_000, "The connection was cut",
            "SocketException: Connection reset", "Wi-Fi", durationMs = 55_000)
        fun video(at: Long) = ConnectionLog.Entry(ConnectionLog.Kind.VIDEO, at, "The video encoder stopped working.",
            "android.media.MediaCodec\$CodecException: Error 0xffffffe0")
        ConnectionLog.add(drop)
        ConnectionLog.add(video(now - 1_000))
        // The phone is still offline for the reason it was; the video line says nothing about that.
        assertEquals(drop, ConnectionLog.currentProblem())
        assertEquals(drop, ConnectionLog.lastDrop())
        ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.CONNECTED, now - 500, network = "Wi-Fi"))
        ConnectionLog.add(video(now))
        assertNull(ConnectionLog.currentProblem())

        // It is kept across a restart like the rest.
        ConnectionLog::class.java.getDeclaredField("file").apply { isAccessible = true }.set(ConnectionLog, null)
        ConnectionLog.init(context)
        assertEquals(video(now), ConnectionLog.entries().first())

        val at = java.time.Instant.parse("2026-11-01T09:56:25Z").toEpochMilli()
        assertEquals("2026-11-01 02:56:25 MST (09:56:25 UTC)  Video went back to pictures. " +
            "The video encoder stopped working. [android.media.MediaCodec\$CodecException: Error 0xffffffe0]",
            ConnectionLog.toText(listOf(video(at)), TimeZone.getTimeZone("America/Phoenix")))

        val controller = Robolectric.buildActivity(ConnectionHistoryActivity::class.java).setup()
        try {
            val texts = texts(controller.get().findViewById<LinearLayout>(R.id.historyGroup))
            assertEquals("Video went back to pictures", texts.first())
            assertTrue(texts.contains("The video encoder stopped working."))
            assertTrue(texts.contains("android.media.MediaCodec\$CodecException: Error 0xffffffe0"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun texts(group: ViewGroup): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: android.view.View) {
            if (v is TextView) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(group)
        return out
    }

    private fun caps(vararg transports: Int) = ShadowNetworkCapabilities.newInstance().also { caps ->
        transports.forEach { shadowOf(caps).addTransportType(it) }
    }

    private fun refuse(conn: FakeRelay.Connection) {
        val hello = JSONObject(String(conn.read().second))
        assertEquals("hello", hello.getString("type"))
        // The other devices are told which version this phone runs.
        assertEquals(BuildConfig.VERSION_NAME, hello.getString("version"))
        conn.sendText("""{"type":"error","code":"too-many-connections",""" +
            """"message":"too many connections from this network"}""")
        conn.sendClose(4002, "too-many-connections")
        assertEquals(0x8, conn.read().first)
    }

    /** Runs the main looper until the delayed reconnect reaches the relay. */
    private fun acceptRetry(relay: FakeRelay): FakeRelay.Connection {
        repeat(40) {
            main.idleFor(Duration.ofSeconds(5))
            relay.accept(250)?.let { return it }
        }
        throw AssertionError("the phone never retried")
    }

    /** Until a refused attempt has scheduled its retry, which a new network may bring forward. */
    private fun waitForRetry() = waitFor {
        main.idle()
        connectionField("retryScheduled").getBoolean(ConnectionManager) && main.nextScheduledTaskTime > Duration.ZERO
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }
}
