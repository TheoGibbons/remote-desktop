package co.remotedesktop

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import javax.imageio.ImageIO

/** A phone viewing and controlling another phone. Same setup as
 *  PairingStatusTest, whose PeerAuth shadow it shares. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSessionPeerAuth::class, ShadowSessionEnvironment::class],
    instrumentedPackages = ["co.remotedesktop"])
class PhoneViewerActivityTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private var controller: ActivityController<PhoneViewerActivity>? = null
    private lateinit var activity: PhoneViewerActivity

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
        for (name in listOf("settings", "install")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).autoConnect = false
        for (name in listOf("appContext", "encKey", "ws", "fs", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("fs").set(ConnectionManager, FsHandler(context))
        connectionField("state").set(ConnectionManager, "connected")
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("phone", "android", "Pixel"))
        trustedPeers.addAll(listOf("pc", "phone"))
        resultFrom["phone"] = "trusted"
    }

    @After
    fun cleanup() {
        controller?.let { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        resultFrom.clear()
        for (name in listOf("settings", "install")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun open(peerId: String = "phone") {
        val intent = Intent(context, PhoneViewerActivity::class.java).putExtra(ConnectionManager.EXTRA_PEER_ID, peerId)
        controller = Robolectric.buildActivity(PhoneViewerActivity::class.java, intent).setup().visible()
        activity = controller!!.get()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun deliver(msg: JSONObject) {
        ConnectionManager.jsonListeners.forEach { it(msg) }
        idle()
    }

    private fun frame(type: Byte, payload: ByteArray = jpeg()) {
        ConnectionManager.binaryListeners.forEach { it(byteArrayOf(type) + payload) }
        idle()
    }

    /** A 360 x 800 picture of a phone screen. */
    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(360, 800, BufferedImage.TYPE_INT_RGB), "jpg", it)
    }.toByteArray()

    private val screen get() = activity.findViewById<PhoneScreenView>(R.id.phoneScreen)
    private val notice get() = activity.findViewById<TextView>(R.id.phoneViewerNotice)

    private fun sent(vararg types: String) = socket.messages.filter { it.getString("type") in types }

    @Test
    fun itViewsThePhoneWhileOnScreenAndLiftsAFingerLeftDownWhenItGoes() {
        open()
        assertEquals("Pixel", activity.findViewById<MaterialToolbar>(R.id.phoneViewerToolbar).title.toString())
        assertEquals(listOf("phone"), sent("start-view").map { it.getString("to") })
        frame(Crypto.CH_VIDEO)
        assertTrue(screen.hasFrame)

        // A finger rests in the middle of the picture until it counts as a press.
        val now = SystemClock.uptimeMillis()
        screen.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
            screen.width / 2f, screen.height / 2f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        sent("touch").single().let {
            assertEquals("down", it.getString("action"))
            assertEquals(0.5, it.getDouble("x"), 0.01)
            assertEquals(0.5, it.getDouble("y"), 0.01)
            assertEquals("phone", it.getString("to"))
        }

        controller!!.pause()
        assertEquals(listOf("touch:down", "touch:up", "stop-view:"), sent("touch", "stop-view").map {
            it.getString("type") + ":" + it.optString("action") })
        // Nothing is drawn while paused.
        socket.messages.clear()
        controller!!.resume()
        assertEquals(1, sent("start-view").size)
    }

    @Test
    fun onlyWholePicturesAndThisPhonesScreenInfoCount() {
        open()
        // A PC's patches and video, which an older relay may pass on.
        frame(Crypto.CH_PATCH)
        frame(Crypto.CH_H264)
        assertFalse(screen.hasFrame)

        deliver(JSONObject().put("type", "screen-info").put("from", "other").put("width", 360).put("height", 800)
            .put("typing", false))
        activity.findViewById<MaterialToolbar>(R.id.phoneViewerToolbar).menu.getItem(0).let {
            activity.findViewById<MaterialToolbar>(R.id.phoneViewerToolbar).menu.performIdentifierAction(it.itemId, 0)
        }
        assertNotEquals("Pixel needs Android 13 or later to type into it", ShadowToast.getTextOfLatestToast())

        deliver(JSONObject().put("type", "screen-info").put("from", "phone").put("width", 360).put("height", 800)
            .put("typing", false))
        activity.findViewById<MaterialToolbar>(R.id.phoneViewerToolbar).menu.getItem(0).let {
            activity.findViewById<MaterialToolbar>(R.id.phoneViewerToolbar).menu.performIdentifierAction(it.itemId, 0)
        }
        assertEquals("Pixel needs Android 13 or later to type into it", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theButtonsAndKeyboardSendWhatThePhoneExpects() {
        open()
        mapOf(R.id.phoneNavBack to "back", R.id.phoneNavHome to "homebtn", R.id.phoneNavRecents to "recents",
            R.id.phoneNavNotifications to "notifications").forEach { (id, type) ->
            activity.findViewById<View>(id).performClick()
            socket.messages.last().let {
                assertEquals(type, it.getString("type"))
                assertEquals("phone", it.getString("to"))
            }
        }

        val ime = activity.findViewById<EditText>(R.id.phoneViewerIme)
        socket.messages.clear()
        ime.setText("hi")
        ime.setText("ho\n")
        assertEquals(listOf("text hi", "key BACKSPACE", "text o", "key ENTER"), socket.messages.map {
            it.getString("type") + " " + (it.optString("text").ifEmpty { it.optString("code") }) })
        assertTrue(socket.messages.all { it.getString("to") == "phone" })
    }

    @Test
    fun itSaysWhyTheScreenIsEmpty() {
        resultFrom["phone"] = "pending"
        open()
        assertEquals(View.VISIBLE, notice.visibility)
        assertEquals("Approve this phone on Pixel.\nCode: A3F2-9C41", notice.text.toString())
        assertFalse("Touches must still reach the screen", notice.isClickable)

        // Approved: the stream is asked for again.
        socket.messages.clear()
        resultFrom["phone"] = "trusted"
        deliver(JSONObject().put("type", "auth-result").put("from", "phone").put("status", "trusted"))
        assertEquals(View.GONE, notice.visibility)
        assertEquals(1, sent("start-view").size)

        // Still nothing five seconds later.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(View.VISIBLE, notice.visibility)
        assertTrue(notice.text.toString().startsWith("Nothing from Pixel yet. On Pixel, check that View and control"))
        frame(Crypto.CH_VIDEO)
        assertEquals(View.GONE, notice.visibility)
    }

    @Test
    fun itClosesWhenThePhoneLeavesOrHangsUp() {
        open()
        deliver(JSONObject().put("type", "peer-left").put("id", "phone"))
        assertTrue(activity.isFinishing)
        assertEquals("Pixel disconnected", ShadowToast.getTextOfLatestToast())
        controller!!.pause().stop().destroy()

        open()
        deliver(JSONObject().put("type", "auth-result").put("from", "phone").put("status", "revoked"))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun aPcOrAMissingPhoneIsNotOpened() {
        open("pc")
        assertTrue(activity.isFinishing)
        assertEquals("That phone is no longer online", ShadowToast.getTextOfLatestToast())
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
