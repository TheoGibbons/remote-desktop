package co.remotedesktop

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Base64
import android.widget.Button
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.TimeZone

/**
 * The Stats panel's Export, on a phone viewing a PC: what the viewer keeps
 * for it, what it asks the PC, and the text that is shared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewerStatsExportTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private lateinit var controller: ActivityController<ViewerActivity>
    private lateinit var activity: ViewerActivity

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun authField(name: String) = PeerAuth::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(PeerAuth)

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = authField("trustedPeers") as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private val resultFrom get() = authField("resultFrom") as MutableMap<String, String>

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("appContext", "encKey", "ws", "fs", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("fs").set(ConnectionManager, FsHandler(context))
        connectionField("state").set(ConnectionManager, "connected")
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC", "1.0.69"))
        trustedPeers.add("pc")
        resultFrom["pc"] = "trusted"
        controller = Robolectric.buildActivity(ViewerActivity::class.java).setup()
        activity = controller.get()
    }

    @After
    fun cleanup() {
        controller.pause().stop().destroy()
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        resultFrom.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun theLogKeepsTheNewestAndCountsWhatRepeats() {
        val log = ViewerStatsLog(maxSamples = 3, maxEvents = 4)
        for (i in 1..5) log.sample(sample(atMs = i * 1000L, frames = i.toLong()))
        assertEquals(listOf(3L, 4L, 5L), log.samples().map { it.frames })

        log.event(1000, "Asked the PC for a whole picture")
        log.event(3000, "Asked the PC for a whole picture")
        log.event(5000, "Asked the PC for a whole picture")
        log.event(6000, "First picture, 0.4 s after asking")
        log.event(7000, "on show at 100x200", key = "viewport")
        log.event(8000, "on show at 300x400", key = "viewport")
        log.event(9000, "Asked the PC for a whole picture")
        assertEquals(
            listOf(
                ViewerStatsLog.Event(1000, "Asked the PC for a whole picture", times = 3, untilMs = 5000),
                ViewerStatsLog.Event(6000, "First picture, 0.4 s after asking"),
                ViewerStatsLog.Event(7000, "on show at 300x400", times = 2, untilMs = 8000, key = "viewport"),
                ViewerStatsLog.Event(9000, "Asked the PC for a whole picture"),
            ),
            log.events())
        log.event(10_000, "one more")
        assertEquals(4, log.events().size)
        assertEquals("First picture, 0.4 s after asking", log.events().first().what)
    }

    @Test
    fun theExportIsOneTextWithEverythingInIt() {
        val zone = TimeZone.getTimeZone("GMT-07:00")
        // 2026-11-27 16:00:00 UTC
        val at = 1795795200000L
        val text = ViewerStatsLog.export(
            ViewerStatsLog.Heading(at, "My phone", "1.0.69", "17", "samsung SM-S942W", "THEO", "1.0.69"),
            now = "Connection  connected (5:00)\nLast frame  26.1 s ago\n",
            samples = listOf(
                sample(atMs = at - 4000, fps = 11.5, frames = 100, rttMs = 38, kind = "H.264", lastFrameAgoMs = 120, lastPacketAgoMs = 40),
                sample(atMs = at - 2000, fps = 0.0, frames = 100, rttMs = null, kind = "H.264", lastFrameAgoMs = 2120, lastPacketAgoMs = 61_000),
            ),
            events = listOf(
                ViewerStatsLog.Event(at - 300_000, "Opened on THEO"),
                ViewerStatsLog.Event(at - 60_000, "Asked the PC for a whole picture", times = 12, untilMs = at - 36_000),
            ),
            connectionHistory = "2026-11-27 08:10:00 GMT-07:00 (15:10:00 UTC)  Connected\n",
            pcReport = "Remote Desktop 1.0.69 on Windows, \"THEO\", running 2 h 15 min\nWindow: no messages waiting\n",
            noPcReport = null,
            zone = zone,
        )
        val lines = text.lines()
        assertEquals("Remote Desktop stats, 2026-11-27 09:00:00 -0700 (16:00:00 UTC)", lines[0])
        assertEquals("This phone: My phone, app 1.0.69, Android 17, samsung SM-S942W", lines[1])
        assertEquals("Viewing: THEO, app 1.0.69", lines[2])
        val sections = lines.filter { it.startsWith("== ") }
        assertEquals(
            listOf(
                "== Now ==",
                "== What the PC says about itself ==",
                "== What happened, oldest first, in this phone's time ==",
                "== The figures every 2 s, oldest first ==",
                "== This phone's connection history, newest first ==",
            ),
            sections)
        assertTrue(text, text.contains("== Now ==\nConnection  connected (5:00)\nLast frame  26.1 s ago\n\n"))
        assertTrue(text, text.contains("Window: no messages waiting\n\n== What happened"))
        assertTrue(text, text.contains("08:55:00  Opened on THEO\n"))
        assertTrue(text, text.contains("08:59:00  Asked the PC for a whole picture (12 times, the last at 08:59:24)\n"))
        val rows = lines.dropWhile { !it.startsWith("time ") }.drop(1).take(2).map { it.trim().split(Regex(" {2,}")) }
        assertEquals(listOf("08:59:56", "connected", "11.5", "0.42", "0.45", "100", "3", "0", "0", "120 ms", "40 ms", "38 ms", "H.264"), rows[0])
        assertEquals(listOf("08:59:58", "connected", "0.0", "0.42", "0.45", "100", "3", "0", "0", "2.1 s", "1:01", "-", "H.264"), rows[1])
        assertTrue(text, text.endsWith("== This phone's connection history, newest first ==\n2026-11-27 08:10:00 GMT-07:00 (15:10:00 UTC)  Connected\n"))

        val bare = ViewerStatsLog.export(
            ViewerStatsLog.Heading(at, "My phone", "1.0.69", "17", "samsung SM-S942W", "THEO", null),
            now = "", samples = emptyList(), events = emptyList(), connectionHistory = "",
            pcReport = null, noPcReport = "The PC did not answer within 3 s.", zone = zone)
        assertTrue(bare, bare.contains("Viewing: THEO, app version not given\n"))
        assertTrue(bare, bare.contains("== What the PC says about itself ==\nThe PC did not answer within 3 s.\n"))
        assertEquals(3, Regex("Nothing recorded\\.").findAll(bare).count())
    }

    @Test
    fun exportAsksThePcAndSharesWhatItSays() {
        openStats()
        socket.messages.clear()
        exportButton().performClick()
        val ask = socket.messages.single { it.getString("type") == "diagnostic-report-get" }
        assertEquals("pc", ask.getString("to"))
        assertFalse(exportButton().isEnabled)
        assertNull("Nothing is shared before the PC answers or the wait is up", shared())

        // Not from the PC being viewed, and not the answer to this asking.
        deliver(report(ask.getString("reqId"), "A report from somebody else", from = "another"))
        deliver(report("some-other-request", "An answer to another question"))
        assertNull(shared())

        deliver(report(ask.getString("reqId"), "Remote Desktop 1.0.69 on Windows, \"Test PC\", running 3 min\nWindow: no messages waiting\n"))
        val text = shared()!!
        assertTrue(exportButton().isEnabled)
        assertTrue(text, text.contains("\nViewing: Test PC, app 1.0.69\n"))
        assertTrue(text, text.contains("== Now ==\nConnection  connected"))
        assertTrue(text, text.contains("== What the PC says about itself ==\nRemote Desktop 1.0.69 on Windows, \"Test PC\", running 3 min\nWindow: no messages waiting\n"))
        assertTrue(text, text.contains("  Opened on Test PC\n"))
        assertTrue(text, text.contains("  Asked the PC for its screen\n"))
        assertFalse("The session key is this phone's secret", text.contains(Base64.encodeToString(socket.key, Base64.NO_WRAP)))
    }

    @Test
    fun aPcThatSaysNothingIsSharedAsThat() {
        openStats()
        exportButton().performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2900))
        assertNull(shared())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        val text = shared()!!
        assertTrue(text, text.contains("== What the PC says about itself ==\nThe PC did not answer within 3 s. Its app (version 1.0.69) may be older"))
        assertTrue(exportButton().isEnabled)
        // What it says once the wait is up comes too late to be shared again.
        val ask = socket.messages.last { it.getString("type") == "diagnostic-report-get" }
        deliver(report(ask.getString("reqId"), "Late"))
        assertNull(shared())
    }

    @Test
    fun aPhoneThatIsOffTheRelayDoesNotAsk() {
        openStats()
        connectionField("state").set(ConnectionManager, "disconnected")
        socket.messages.clear()
        exportButton().performClick()
        assertTrue(socket.messages.none { it.getString("type") == "diagnostic-report-get" })
        val text = shared()!!
        assertTrue(text, text.contains("== What the PC says about itself ==\nNot asked: this phone was not connected to the relay.\n"))
    }

    @Test
    fun theFiguresAreNotedWithThePanelShutAndNothingIsSentForThem() {
        socket.messages.clear()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(6100))
        assertEquals(3, log().samples().size)
        assertEquals("connected", log().samples().last().state)
        assertNull("No round trip is measured unless the panel is open", log().samples().last().rttMs)
        assertTrue("Noting the figures sent the PC ${socket.messages.map { it.getString("type") }}",
            socket.messages.none { it.getString("type").startsWith("diagnostic") })

        // What happens is written down as it happens.
        @Suppress("UNCHECKED_CAST")
        val stateListener = field("stateListener") as (String) -> Unit
        stateListener("disconnected: the relay stopped answering")
        assertEquals("This phone's connection to the relay: disconnected: the relay stopped answering", log().events().last().what)

        controller.pause()
        val kept = log().samples().size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals("Nothing is noted while the viewer is not in front", kept, log().samples().size)
        assertEquals("Left the viewer", log().events().last().what)
        controller.resume()
        assertTrue(log().events().any { it.what == "Came back to the viewer" })
    }

    private fun sample(
        atMs: Long, fps: Double = 0.0, frames: Long = 0, rttMs: Long? = null, kind: String? = null,
        lastFrameAgoMs: Long? = null, lastPacketAgoMs: Long? = null,
    ) = ViewerStatsLog.Sample(atMs, "connected", fps, 0.42, 0.45, frames, 3, 0, 0, lastFrameAgoMs, lastPacketAgoMs, rttMs, kind)

    private fun field(name: String): Any? = ViewerActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun log() = field("statsLog") as ViewerStatsLog

    private fun exportButton() = field("exportButton") as Button

    private fun openStats() {
        ViewerActivity::class.java.getDeclaredMethod("showDiagnostics").apply { isAccessible = true }.invoke(activity)
    }

    private fun deliver(message: JSONObject) {
        @Suppress("UNCHECKED_CAST")
        (field("jsonListener") as (JSONObject) -> Unit)(message)
    }

    private fun report(reqId: String, text: String, from: String = "pc") = JSONObject()
        .put("type", "diagnostic-report").put("from", from).put("reqId", reqId).put("report", text)

    /** The text handed to Android's share sheet since this was last asked, or null. */
    private fun shared(): String? {
        val chooser = shadowOf(activity).nextStartedActivity ?: return null
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        return send.getStringExtra(Intent.EXTRA_TEXT)
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
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
}
