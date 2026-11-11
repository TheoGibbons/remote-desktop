package co.remotedesktop

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
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

    /** A 360 x 800 picture of a phone screen: black, or all one colour. */
    private fun jpeg(rgb: Int = 0): ByteArray = ByteArrayOutputStream().also {
        val image = BufferedImage(360, 800, BufferedImage.TYPE_INT_RGB)
        if (rgb != 0) image.createGraphics().apply { color = java.awt.Color(rgb); fillRect(0, 0, 360, 800); dispose() }
        ImageIO.write(image, "jpg", it)
    }.toByteArray()

    private val screen get() = activity.findViewById<PhoneScreenView>(R.id.phoneScreen)
    private val notice get() = activity.findViewById<TextView>(R.id.phoneViewerNotice)
    private val caption get() = activity.findViewById<TextView>(R.id.phoneViewerCaption)

    private fun lockState(from: String, screenOn: Boolean, locked: Boolean, wakeFailed: String? = null) =
        deliver(JSONObject().put("type", "lock-state").put("from", from).put("screenOn", screenOn).put("locked", locked)
            .apply { wakeFailed?.let { put("wakeFailed", it) } })

    /** The next picture, once the last one has been on show long enough to be looked at again. */
    private fun laterFrame(rgb: Int = 0) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LockCaption.BlackWatch.INTERVAL_MS))
        frame(Crypto.CH_VIDEO, jpeg(rgb))
    }

    private fun sent(vararg types: String) = socket.messages.filter { it.getString("type") in types }

    @Test
    fun itViewsThePhoneWhileOnScreenAndLiftsAFingerLeftDownWhenItGoes() {
        open()
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
    fun theTopBarHoldsTheButtonsBesideTheKeyboardAndNoName() {
        open()
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.phoneViewerToolbar)
        assertTrue(toolbar.title.isNullOrEmpty())
        val keyboard = (0 until toolbar.childCount).map(toolbar::getChildAt).filterIsInstance<ActionMenuView>().single()
        val top = IntArray(2).also(toolbar::getLocationInWindow)[1]
        var previousRight = 0
        listOf(R.id.phoneNavBack, R.id.phoneNavHome, R.id.phoneNavRecents, R.id.phoneNavNotifications).forEach { id ->
            val button = activity.findViewById<View>(id)
            val at = IntArray(2).also(button::getLocationInWindow)
            assertTrue("Button $id has no width", button.width > 0)
            assertEquals(top, at[1])
            assertTrue("Button $id overlaps the one before it", at[0] >= previousRight)
            previousRight = at[0] + button.width
        }
        assertTrue("The buttons run into the keyboard button",
            previousRight <= IntArray(2).also(keyboard::getLocationInWindow)[0])
    }

    @Test
    fun rotateTurnsThisPhoneWithoutRestartingTheStream() {
        open()
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.phoneViewerToolbar)
        val actions = (0 until toolbar.childCount).map(toolbar::getChildAt).filterIsInstance<ActionMenuView>().single()
        assertEquals("Keyboard and rotate both belong on the bar", 2, actions.childCount)
        val rotate = actions.getChildAt(1)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        socket.messages.clear()

        rotate.performClick()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)

        controller!!.configurationChange(Configuration(activity.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        })
        idle()
        rotate.performClick()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)

        assertFalse(activity.isDestroyed)
        assertEquals(emptyList<String>(), sent("start-view", "stop-view").map { it.getString("type") })
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
    fun itSaysWhenThePhoneIsAsleepOrOnItsUnlockScreen() {
        open()
        frame(Crypto.CH_VIDEO, jpeg(0x3366CC))
        assertEquals(View.GONE, caption.visibility)
        assertFalse("Touches must still reach the screen", caption.isClickable)

        lockState("phone", screenOn = false, locked = true)
        assertEquals(View.VISIBLE, caption.visibility)
        assertEquals("Pixel is asleep. Tap to wake it.", caption.text.toString())
        lockState("phone", screenOn = false, locked = true, wakeFailed = "notifications")
        assertEquals("Pixel didn't wake. Allow notifications for Remote Desktop on Pixel.", caption.text.toString())
        lockState("phone", screenOn = false, locked = true, wakeFailed = "unknown")
        assertEquals("Pixel didn't wake. Tap to try again.", caption.text.toString())

        // Awake on its lock screen, which is in the picture: nothing to add.
        lockState("phone", screenOn = true, locked = true)
        laterFrame(0x3366CC)
        assertEquals(View.GONE, caption.visibility)
        // Then its unlock screen, which Android leaves black.
        laterFrame()
        assertEquals(View.VISIBLE, caption.visibility)
        assertEquals("Android hides the unlock screen of Pixel from the picture. Taps and drags still reach it.",
            caption.text.toString())
        // Unlocked, and showing something black of its own.
        lockState("phone", screenOn = true, locked = false)
        laterFrame()
        assertEquals(View.GONE, caption.visibility)

        // What another device says about itself is not about this phone.
        lockState("pc", screenOn = false, locked = true)
        assertEquals(View.GONE, caption.visibility)
    }

    @Test
    fun theUnlockScreenIsDrawnWhereThePhoneSaysItIsAndADragLeavesATrail() {
        open()
        frame(Crypto.CH_VIDEO, jpeg())
        // 360 x 800 picture; the grid is a 240 pixel square at (60, 440).
        deliver(JSONObject("""{"type":"lock-state","from":"phone","screenOn":true,"locked":true,"entry":{"kind":"pattern","area":[0.1667,0.55,0.6667,0.3]},"message":"Draw your pattern"}"""))
        assertEquals("The grid is drawn, so nothing is said about a hidden screen", View.GONE, caption.visibility)

        // A finger drawn through the top row: the three dots are taken, and the touches still go to the phone.
        val picture = PhoneScreenView::class.java.getDeclaredField("picture").apply { isAccessible = true }.get(screen) as android.graphics.RectF
        fun at(fx: Float, fy: Float) = Pair(picture.left + fx * picture.width(), picture.top + fy * picture.height())
        val start = SystemClock.uptimeMillis()
        fun touch(action: Int, fx: Float, fy: Float, ms: Long) {
            val (x, y) = at(fx, fy)
            screen.dispatchTouchEvent(MotionEvent.obtain(start, start + ms, action, x, y, 0))
        }
        socket.messages.clear()
        touch(MotionEvent.ACTION_DOWN, 0.2778f, 0.6f, 0)
        for (step in 1..10) touch(MotionEvent.ACTION_MOVE, 0.2778f + 0.0444f * step, 0.6f, 40L * step)
        idle()
        assertEquals(setOf(0, 1, 2), screen.trailDots.toSet())
        assertEquals(11, screen.trailLength)
        assertTrue(sent("touch").isNotEmpty())
        touch(MotionEvent.ACTION_UP, 0.7222f, 0.6f, 500)
        idle()
        assertEquals("The trail goes when the finger lifts", 0, screen.trailLength)
        assertTrue(screen.trailDots.isEmpty())

        // A password has nothing to draw: it is asked for in words, under the phone's own message.
        deliver(JSONObject("""{"type":"lock-state","from":"phone","screenOn":true,"locked":true,"entry":{"kind":"password"},"message":"Wrong password"}"""))
        assertEquals("Wrong password\nOpen the keyboard above, type the password of Pixel and press Enter.", caption.text.toString())

        // Unlocked: nothing is drawn or said, and a drag leaves no trail.
        lockState("phone", screenOn = true, locked = false)
        assertEquals(View.GONE, caption.visibility)
        touch(MotionEvent.ACTION_DOWN, 0.3f, 0.6f, 1000)
        touch(MotionEvent.ACTION_MOVE, 0.5f, 0.6f, 1040)
        assertEquals(0, screen.trailLength)
        touch(MotionEvent.ACTION_UP, 0.5f, 0.6f, 1080)
    }

    @Test
    fun whatIsTypedAtAPinPadGoesToItsKeysAsTapsAndNeverAsText() {
        open()
        val keys = (0..9).map { """{"label":"$it","rect":[${it * 0.1},0.5,0.1,0.1]}""" } +
            listOf("""{"label":"delete","rect":[0.0,0.7,0.1,0.1]}""", """{"label":"ok","rect":[0.5,0.7,0.1,0.1]}""")
        deliver(JSONObject("""{"type":"lock-state","from":"phone","screenOn":true,"locked":true,"entry":{"kind":"pin","keys":[${keys.joinToString(",")}]}}"""))
        val ime = activity.findViewById<EditText>(R.id.phoneViewerIme)
        socket.messages.clear()
        fun sentNow() = socket.messages.map { m ->
            assertEquals("phone", m.getString("to"))
            assertEquals("Marked, so the other phone drops it once unlocked", true, m.opt("onlyIfLocked"))
            m.getString("type") + " " + "%.2f %.2f".format(java.util.Locale.ROOT, m.getDouble("x"), m.getDouble("y"))
        }.also { socket.messages.clear() }

        ime.setText("4")
        assertEquals(listOf("tap 0.45 0.55"), sentNow())
        // The keyboard hands over a digit and a letter together: the digit is tapped, the letter goes nowhere.
        ime.setText("47a")
        assertEquals(listOf("tap 0.75 0.55"), sentNow())
        // Backspace is the pad's delete key, Enter its OK key.
        ime.setText("47")
        assertEquals(listOf("tap 0.05 0.75"), sentNow())
        ime.setText("47\n")
        assertEquals(listOf("tap 0.55 0.75"), sentNow())
        ime.setText("47\nxyz")
        assertEquals(emptyList<String>(), sentNow())

        // Unlocked: typing is text again.
        lockState("phone", screenOn = true, locked = false)
        socket.messages.clear()
        ime.setText("47\nxyz1")
        assertEquals(listOf("text 1"), socket.messages.map { it.getString("type") + " " + it.optString("text") })
        assertFalse(socket.messages.single().has("onlyIfLocked"))
    }

    @Test
    fun aPictureFromBeforeThePhonesLockChangedDoesNotDecideTheCaption() {
        open()
        frame(Crypto.CH_VIDEO, jpeg(0x3366CC))
        assertEquals(View.GONE, caption.visibility)

        // A black picture is decoded where pictures arrive, off the main thread ...
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LockCaption.BlackWatch.INTERVAL_MS))
        Thread { ConnectionManager.binaryListeners.forEach { it(byteArrayOf(Crypto.CH_VIDEO) + jpeg()) } }.apply { start(); join() }
        // ... and before it reaches the screen the phone says, for the first time, that it is awake on its lock screen.
        ConnectionManager.jsonListeners.forEach {
            it(JSONObject().put("type", "lock-state").put("from", "phone").put("screenOn", true).put("locked", true))
        }
        idle()
        assertEquals("That picture was looked at before the phone said how it stands", View.GONE, caption.visibility)

        // The next picture is looked at afresh: black on a locked phone is its unlock screen.
        laterFrame()
        assertEquals(View.VISIBLE, caption.visibility)
    }

    @Test
    fun aPhoneThatNeverSaysHowItStandsIsExplainedByItsBlackPicture() {
        open()
        frame(Crypto.CH_VIDEO, jpeg(0x3366CC))
        assertEquals(View.GONE, caption.visibility)
        laterFrame()
        assertEquals(View.VISIBLE, caption.visibility)
        assertEquals("The screen of Pixel is off, or it is on its unlock screen. Taps and drags still reach an unlock screen.",
            caption.text.toString())
        laterFrame(0x3366CC)
        assertEquals(View.GONE, caption.visibility)
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
