package co.remotedesktop

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Base64
import android.widget.TextView
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CopyToPcActivityTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val previous = mutableMapOf<String, Any?>()
    private val controllers = mutableListOf<ActivityController<CopyToPcActivity>>()

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun authField(name: String) = PeerAuth::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(PeerAuth)

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = authField("trustedPeers") as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private val peerFingerprint get() = authField("peerFingerprint") as MutableMap<String, String>

    @Suppress("UNCHECKED_CAST")
    private val viewers get() = PhoneScreenCapture::class.java.getDeclaredField("viewers")
        .apply { isAccessible = true }.get(PhoneScreenCapture) as MutableSet<String>

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        // "state" too: the connecting tests start a connection that stops at "not configured".
        for (name in listOf("appContext", "encKey", "ws", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("state").set(ConnectionManager, "connected")
    }

    @After
    fun cleanup() {
        controllers.forEach { it.pause().stop().destroy() }
        main.idle()
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        PeerAuth.resetConnection()
        viewers.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun selectionMenuOffersCopyToPc() {
        val matches = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
        val entry = matches.single { it.activityInfo.name == CopyToPcActivity::class.java.name }
        assertTrue(entry.activityInfo.exported)
        assertEquals("Copy to PC", entry.loadLabel(context.packageManager).toString())
    }

    @Test
    fun nothingGoesToThePcUntilTheUserSaysCopy() {
        pcs("pc" to "Desk")
        ConnectionManager.peers.add(ConnectionManager.Peer("tablet", "android", "Tablet"))
        trustedPeers.add("tablet")
        val activity = launch("hello")
        assertFalse(activity.isFinishing)
        assertTrue(sent().isEmpty())
        val confirm = latestDialog()
        assertEquals("Copy to Desk?", shadowOf(confirm).title.toString())
        assertEquals("hello", message(confirm))

        press(confirm, AlertDialog.BUTTON_POSITIVE)
        val set = sent().single()
        assertEquals("pc", set.getString("to"))
        assertEquals("hello", set.getString("text"))
        assertFalse(set.has("paste"))
        assertEquals("Copying to Desk…", message(latestDialog()))
        assertFalse(activity.isFinishing)

        // It waits for the PC's answer, and says so while still in front.
        answer(JSONObject().put("type", "clip-set-result").put("from", "other").put("reqId", set.getString("reqId")))
        assertFalse(activity.isFinishing)
        answer(JSONObject().put("type", "clip-set-result").put("from", "pc").put("reqId", set.getString("reqId")))
        assertEquals("Copied to Desk", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun cancellingSendsNothing() {
        pcs("pc" to "Desk")
        val activity = launch("hello")
        press(latestDialog(), AlertDialog.BUTTON_NEGATIVE)
        assertTrue(activity.isFinishing)
        assertTrue(sent().isEmpty())
    }

    @Test
    fun errorsSilenceAndADroppedConnectionAreShownUntilRead() {
        pcs("pc" to "Desk")
        var activity = launch("first")
        press(latestDialog(), AlertDialog.BUTTON_POSITIVE)
        answer(JSONObject().put("type", "clip-set-result").put("from", "pc")
            .put("reqId", sent().single().getString("reqId")).put("error", "The PC is locked."))
        assertEquals("The PC is locked.", message(latestDialog()))
        assertFalse(activity.isFinishing)
        press(latestDialog(), AlertDialog.BUTTON_POSITIVE)
        assertTrue(activity.isFinishing)

        activity = launch("second")
        press(latestDialog(), AlertDialog.BUTTON_POSITIVE)
        main.idleFor(Duration.ofMillis(PcClipboard.REPLY_TIMEOUT_MS))
        assertEquals(context.getString(R.string.copy_to_pc_no_reply, "Desk"), message(latestDialog()))
        assertFalse(activity.isFinishing)

        launch("third")
        press(latestDialog(), AlertDialog.BUTTON_POSITIVE)
        connectionField("state").set(ConnectionManager, "disconnected: retrying...")
        ConnectionManager.stateListeners.toList().forEach { it("disconnected: retrying...") }
        assertEquals(context.getString(R.string.clip_connection_lost), message(latestDialog()))
    }

    @Test
    fun severalPcsAskWhichWithTheOneViewingThisPhoneFirst() {
        pcs("pc" to "Desk", "laptop" to "Laptop")
        viewers.add("laptop")
        val activity = launch("hello")
        assertTrue(sent().isEmpty())
        val chooser = shadowOf(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(listOf("Laptop", "Desk"), chooser.items.map { it.toString() })
        // Picking one is the confirmation.
        chooser.clickOnItem(1)
        assertEquals("pc", sent().single().getString("to"))
        assertFalse(activity.isFinishing) // Waiting for its answer.
    }

    @Test
    fun aPcThatComesOnlineIsOfferedOnceItHasApprovedThisPhone() {
        configure()
        val activity = launch("hello")
        assertFalse(activity.isFinishing)
        assertEquals(context.getString(R.string.copy_to_pc_connecting), message(latestDialog()))
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Desk"))
        trustedPeers.add("pc")
        PeerAuth.changeListeners.toList().forEach { it() }
        // This phone has checked the PC, but the PC is still checking this
        // phone and would drop the copy.
        assertEquals(context.getString(R.string.copy_to_pc_connecting), message(latestDialog()))
        PeerAuth.onResultFrom("pc", "trusted")
        main.idle()
        assertEquals("Copy to Desk?", shadowOf(latestDialog()).title.toString())
        assertTrue(sent().isEmpty())
    }

    @Test
    fun aPcStillApprovingThisPhoneIsWaitedForWithoutATimer() {
        configure()
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Desk"))
        trustedPeers.add("pc")
        PeerAuth.onResultFrom("pc", "pending")
        main.idle()
        val activity = launch("hello")
        assertEquals(context.getString(R.string.copy_to_pc_approve, "Desk"), message(latestDialog()))
        main.idleFor(Duration.ofMillis(PcClipboard.REPLY_TIMEOUT_MS * 3))
        assertFalse(activity.isFinishing)
        assertEquals(context.getString(R.string.copy_to_pc_approve, "Desk"), message(latestDialog()))
        PeerAuth.onResultFrom("pc", "trusted")
        main.idle()
        assertEquals("Copy to Desk?", shadowOf(latestDialog()).title.toString())
    }

    @Test
    fun refusalsAndBansAreSaidPlainly() {
        configure()
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Desk"))
        trustedPeers.add("pc")
        PeerAuth.onResultFrom("pc", "denied")
        main.idle()
        launch("hello")
        assertEquals(context.getString(R.string.copy_to_pc_denied, "Desk"), message(latestDialog()))

        PeerAuth.resetConnection()
        main.idle()
        ConnectionManager.peers.clear()
        // Already running and refused by the relay, so starting it changes nothing.
        connectionField("enabled").set(ConnectionManager, true)
        connectionField("state").set(ConnectionManager, "blocked")
        connectionField("blockedUntilMs").set(ConnectionManager, System.currentTimeMillis() + 600_000)
        try {
            launch("hello")
            assertEquals(context.getString(R.string.copy_to_pc_blocked), message(latestDialog()))
        } finally {
            connectionField("enabled").set(ConnectionManager, false)
            connectionField("blockedUntilMs").set(ConnectionManager, 0L)
        }

        // A PC this phone has checked but not approved: its own prompt is waiting.
        connectionField("state").set(ConnectionManager, "connected")
        ConnectionManager.peers.add(ConnectionManager.Peer("new", "windows", "New PC"))
        peerFingerprint["new"] = "a3f29c41"
        launch("hello")
        assertEquals(context.getString(R.string.copy_to_pc_approve_here, "New PC"), message(latestDialog()))
        assertTrue(sent().isEmpty())
    }

    @Test
    fun noPcComingOnlineGivesUpAfterTenSeconds() {
        configure()
        val activity = launch("hello")
        main.idleFor(Duration.ofMillis(PcClipboard.REPLY_TIMEOUT_MS))
        assertEquals(context.getString(R.string.copy_to_pc_unreachable), message(latestDialog()))
        press(latestDialog(), AlertDialog.BUTTON_POSITIVE)
        assertTrue(activity.isFinishing)
        assertTrue(sent().isEmpty())
    }

    @Test
    fun aSelectionAndroidMayHaveCutShort() {
        pcs("pc" to "Desk")
        launch("y".repeat(PcClipboard.SELECTION_LIMIT))
        assertTrue(message(latestDialog()).endsWith(context.getString(R.string.copy_to_pc_truncated)))
        launch("short")
        assertFalse(message(latestDialog()).contains(context.getString(R.string.copy_to_pc_truncated)))
    }

    @Test
    fun nothingIsSentWithoutASessionBlankTextOrTooMuchText() {
        connectionField("state").set(ConnectionManager, "disconnected")
        assertTrue(launch("hello").isFinishing)
        assertEquals(context.getString(R.string.copy_to_pc_not_set_up), ShadowToast.getTextOfLatestToast())
        pcs("pc" to "Desk")
        assertTrue(launch("   ").isFinishing)
        assertTrue(launch("x".repeat(PcClipboard.MAX_CHARS + 1)).isFinishing)
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Too much text"))
        assertTrue(sent().isEmpty())
    }

    /** PCs that are online and approved on both ends. */
    private fun pcs(vararg pcs: Pair<String, String>) {
        for ((id, name) in pcs) {
            ConnectionManager.peers.add(ConnectionManager.Peer(id, "windows", name))
            trustedPeers.add(id)
            PeerAuth.onResultFrom(id, "trusted")
        }
        main.idle()
    }

    private fun configure() = Prefs(context).apply {
        sessionKey = "R8qT2mZ9pK5vN7xB4dF6sW1j"
        serverUrl = "" // Starting the connection must not contact a relay in tests.
    }

    private fun launch(text: String): CopyToPcActivity {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        return Robolectric.buildActivity(CopyToPcActivity::class.java, intent).also { controllers.add(it) }.setup().get()
    }

    private fun latestDialog() = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    /** Dialog buttons answer through the main looper, as on a device. */
    private fun press(dialog: AlertDialog, which: Int) {
        dialog.getButton(which).performClick()
        main.idle()
    }

    private fun message(dialog: AlertDialog) = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun answer(msg: JSONObject) = ConnectionManager.jsonListeners.toList().forEach { it(msg) }

    private fun sent() = socket.messages.filter { it.optString("type") == "clip-set" }

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
