package co.remotedesktop

import android.content.Context
import android.content.Intent
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

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = PeerAuth::class.java.getDeclaredField("trustedPeers")
        .apply { isAccessible = true }.get(PeerAuth) as MutableSet<String>

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
    }

    @After
    fun cleanup() {
        controllers.forEach { it.pause().stop().destroy() }
        main.idle()
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
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
    fun theOnlyApprovedPcGetsTheSelectionAndItsAnswerIsShown() {
        pcs("pc" to "Desk")
        ConnectionManager.peers.add(ConnectionManager.Peer("tablet", "android", "Tablet"))
        trustedPeers.add("tablet")
        val listeners = ConnectionManager.jsonListeners.toSet()
        val activity = launch("hello")
        assertTrue(activity.isFinishing)
        val set = sent().single()
        assertEquals("pc", set.getString("to"))
        assertEquals("hello", set.getString("text"))
        assertFalse(set.has("paste"))

        val reply = (ConnectionManager.jsonListeners - listeners).single()
        reply(JSONObject().put("type", "clip-set-result").put("from", "other").put("reqId", set.getString("reqId")))
        reply(JSONObject().put("type", "clip-set-result").put("from", "pc").put("reqId", set.getString("reqId")))
        assertEquals("Copied to Desk", ShadowToast.getTextOfLatestToast())
        assertFalse(ConnectionManager.jsonListeners.contains(reply))
        main.idleFor(Duration.ofMillis(PcClipboard.REPLY_TIMEOUT_MS)) // The answer cancelled the timeout.
        assertEquals("Copied to Desk", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun errorsAndSilenceFromThePcAreShown() {
        pcs("pc" to "Desk")
        val listeners = ConnectionManager.jsonListeners.toSet()
        launch("first")
        val reply = (ConnectionManager.jsonListeners - listeners).single()
        reply(JSONObject().put("type", "clip-set-result").put("from", "pc")
            .put("reqId", sent().single().getString("reqId")).put("error", "The PC is locked."))
        assertEquals("The PC is locked.", ShadowToast.getTextOfLatestToast())

        launch("second")
        main.idleFor(Duration.ofMillis(PcClipboard.REPLY_TIMEOUT_MS))
        assertEquals(context.getString(R.string.copy_to_pc_no_reply, "Desk"), ShadowToast.getTextOfLatestToast())
        assertEquals(listeners, ConnectionManager.jsonListeners.toSet())
    }

    @Test
    fun thePcViewingThisPhoneIsChosenOverOthers() {
        pcs("pc" to "Desk", "laptop" to "Laptop")
        viewers.add("laptop")
        assertTrue(launch("hello").isFinishing)
        assertEquals("laptop", sent().single().getString("to"))
    }

    @Test
    fun severalPcsAndNoneViewingAsksWhich() {
        pcs("pc" to "Desk", "laptop" to "Laptop")
        val activity = launch("hello")
        assertFalse(activity.isFinishing)
        assertTrue(sent().isEmpty())
        val chooser = shadowOf(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(listOf("Desk", "Laptop"), chooser.items.map { it.toString() })
        chooser.clickOnItem(1)
        assertEquals("laptop", sent().single().getString("to"))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun aPcThatComesOnlineWhileConnectingGetsTheSelectionOnceItTrustsThisPhone() {
        Prefs(context).apply {
            sessionKey = "R8qT2mZ9pK5vN7xB4dF6sW1j"
            serverUrl = "" // Starting the connection must not contact a relay in tests.
        }
        val activity = launch("hello")
        assertFalse(activity.isFinishing)
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        pcs("pc" to "Desk")
        PeerAuth.changeListeners.toList().forEach { it() }
        // This phone trusts the PC, but the PC is still checking this phone
        // and would drop the copy.
        assertTrue(sent().isEmpty())
        assertFalse(activity.isFinishing)
        trustedByPc("pc")
        assertEquals("pc", sent().single().getString("to"))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun aPcThatTrustsThisPhoneFirstGetsTheSelectionOnceApprovedHereToo() {
        Prefs(context).apply {
            sessionKey = "R8qT2mZ9pK5vN7xB4dF6sW1j"
            serverUrl = ""
        }
        val activity = launch("hello")
        trustedByPc("pc")
        assertTrue(sent().isEmpty())
        pcs("pc" to "Desk")
        PeerAuth.changeListeners.toList().forEach { it() }
        assertEquals("pc", sent().single().getString("to"))
        assertTrue(activity.isFinishing)
    }

    private fun trustedByPc(pcId: String) = ConnectionManager.jsonListeners.toList().forEach {
        it(JSONObject().put("type", "auth-result").put("from", pcId).put("ok", true).put("status", "trusted"))
    }

    @Test
    fun noPcComingOnlineGivesUpAfterTenSeconds() {
        Prefs(context).apply {
            sessionKey = "R8qT2mZ9pK5vN7xB4dF6sW1j"
            serverUrl = ""
        }
        val activity = launch("hello")
        main.idleFor(Duration.ofMillis(PcClipboard.REPLY_TIMEOUT_MS))
        assertTrue(activity.isFinishing)
        assertEquals(context.getString(R.string.copy_to_pc_unreachable), ShadowToast.getTextOfLatestToast())
        assertTrue(sent().isEmpty())
    }

    @Test
    fun nothingIsSentWithoutASessionBlankTextOrTooMuchText() {
        assertTrue(launch("hello").isFinishing)
        assertEquals(context.getString(R.string.copy_to_pc_not_set_up), ShadowToast.getTextOfLatestToast())
        pcs("pc" to "Desk")
        assertTrue(launch("   ").isFinishing)
        assertTrue(launch("x".repeat(PcClipboard.MAX_CHARS + 1)).isFinishing)
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Too much text"))
        assertTrue(sent().isEmpty())
    }

    private fun pcs(vararg pcs: Pair<String, String>) {
        for ((id, name) in pcs) {
            ConnectionManager.peers.add(ConnectionManager.Peer(id, "windows", name))
            trustedPeers.add(id)
        }
    }

    private fun launch(text: String): CopyToPcActivity {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        return Robolectric.buildActivity(CopyToPcActivity::class.java, intent).also { controllers.add(it) }.setup().get()
    }

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
