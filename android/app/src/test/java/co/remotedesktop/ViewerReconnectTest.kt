package co.remotedesktop

import android.content.Context
import android.os.Looper
import android.util.Base64
import android.view.View
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewerReconnectTest {
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

    private val notice get() = field("reconnectNotice") as View
    private val reason get() = field("reconnectReason") as TextView

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
        trustedPeers.add("pc")
        resultFrom["pc"] = "trusted"
        ConnectionLog.clear()
        controller = Robolectric.buildActivity(ViewerActivity::class.java).setup()
        activity = controller.get()
        socket.messages.clear()
    }

    @After
    fun cleanup() {
        controller.pause().stop().destroy()
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        resultFrom.clear()
        ConnectionLog.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aDropShowsReconnectingWithThePhonesReasonUntilTheRelayIsBack() {
        assertEquals(View.GONE, notice.visibility)

        ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.LOST, 0, "No reply to a ping within 20 s",
            durationMs = 60_000))
        ConnectionManager.peers.clear() // as the phone does when its socket fails
        setState("disconnected: retrying...")
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals("No reply to a ping within 20 s", reason.text.toString())
        assertFalse(notice.isClickable) // the toolbar's Close still works
        assertFalse(activity.isFinishing)

        // Admitted again with the PC still there: the PC's approval restarts the stream.
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        setState("connected")
        assertEquals(View.GONE, notice.visibility)
        assertFalse(activity.isFinishing)
        deliver(JSONObject().put("type", "auth-result").put("from", "pc").put("status", "trusted"))
        assertTrue(socket.messages.any { it.optString("type") == "start-view" && it.optString("to") == "pc" })
    }

    @Test
    fun backOnTheRelayWithoutThePcClosesTheViewerOnce() {
        ConnectionManager.peers.clear()
        setState("disconnected: retrying...")
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals(View.GONE, reason.visibility) // no reason recorded

        // The PC dropped too and is not in the new welcome's peers.
        setState("connected")
        assertTrue(activity.isFinishing)
        assertEquals("Desktop disconnected", ShadowToast.getTextOfLatestToast())
        val toasts = ShadowToast.shownToastCount()
        deliver(JSONObject().put("type", "peer-left").put("id", "pc"))
        assertEquals(toasts, ShadowToast.shownToastCount())
    }

    private fun setState(state: String) {
        connectionField("state").set(ConnectionManager, state)
        ConnectionManager.stateListeners.forEach { it(state) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun deliver(msg: JSONObject) {
        ConnectionManager.jsonListeners.forEach { it(msg) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun field(name: String): Any? = ViewerActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

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
