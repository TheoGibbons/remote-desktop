package co.remotedesktop

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.PersistableBundle
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
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration
import java.util.concurrent.Executor

/** A PC viewing this phone asks for what is selected or was last copied
 *  (`clip-get`), and gets it on its own clipboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneClipCopyTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private val previous = mutableMapOf<String, Any?>()
    private val instanceField = InputAccessibilityService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }
    private lateinit var service: InputAccessibilityService
    private lateinit var previousOffMain: Executor

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = PeerAuth::class.java.getDeclaredField("trustedPeers")
        .apply { isAccessible = true }.get(PeerAuth) as MutableSet<String>

    private val captureContext = PhoneScreenCapture::class.java.getDeclaredField("appContext")
        .apply { isAccessible = true }

    private val currentRequest = PhoneClipCopy::class.java.getDeclaredField("current")
        .apply { isAccessible = true }

    private val handleJson = ConnectionManager::class.java.getDeclaredMethod("handleJson", JSONObject::class.java)
        .apply { isAccessible = true }

    private val name get() = Prefs(context).deviceName

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("appContext", "encKey", "ws", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("state").set(ConnectionManager, "connected")
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("other-pc", "windows", "Other PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("stranger", "windows", "Stranger"))
        trustedPeers.addAll(listOf("pc", "other-pc"))
        previous["capture"] = captureContext.get(PhoneScreenCapture)
        captureContext.set(PhoneScreenCapture, null)
        PhoneScreenCapture.init(context)
        service = Robolectric.buildService(InputAccessibilityService::class.java).create().get()
        instanceField.set(null, service)
        previousOffMain = PhoneClipCopy.offMain
        PhoneClipCopy.offMain = Executor { it.run() }
        ShadowPowerManager.clearWakeLocks()
        clipboard.clearPrimaryClip()
    }

    @After
    fun cleanup() {
        PhoneScreenCapture.onViewStopped()
        idle()
        currentRequest.set(PhoneClipCopy, null)
        PhoneClipCopy.offMain = previousOffMain
        instanceField.set(null, null)
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

    private fun message(type: String) = JSONObject().put("type", type)

    private fun copy(from: String = "pc", reqId: String = "1") = receive(from, message("clip-get").put("reqId", reqId))

    private fun results() = socket.messages.filter { it.getString("type") == "clip-get-result" }

    private fun ago(ms: Long) = System.currentTimeMillis() - ms

    /** Puts a clip on the clipboard as if another app had, at that time on the wall clock. */
    private fun put(clip: ClipData, copiedAtMs: Long) {
        clipboard.setPrimaryClip(clip)
        // The system stamps a clip as it arrives, and Robolectric with its own
        // clock, which isn't the one app code reads here: the stamp is put right.
        ReflectionHelpers.callInstanceMethod<Unit>(clipboard.primaryClip!!.description, "setTimestamp",
            ClassParameter.from(Long::class.javaPrimitiveType, copiedAtMs))
        assertEquals(copiedAtMs, clipboard.primaryClip!!.description.timestamp)
    }

    private fun secret(clip: ClipData) = clip.apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }

    /** The invisible reader the phone started, given the focus as Android gives it; null if none was started. */
    private fun focusReader(): ActivityController<ClipReadActivity>? {
        val intent = shadowOf(context).nextStartedActivity ?: return null
        assertEquals(ClipReadActivity::class.java.name, intent.component?.className)
        val reader = Robolectric.buildActivity(ClipReadActivity::class.java, intent).setup()
        reader.windowFocusChanged(true)
        idle()
        return reader
    }

    private fun errorOf(result: JSONObject) = result.optString("error").ifEmpty { null }

    @Test
    fun whatThePhoneJustCopiedArrivesWithItsFormatting() {
        receive("pc", message("start-view"))
        put(ClipData.newHtmlText("page", "Bold move", "<b>Bold</b> move"), ago(5_000))
        copy(reqId = "7")
        assertEquals("Nothing goes until the clipboard is read", emptyList<JSONObject>(), results())
        val reader = focusReader()!!
        results().single().let {
            assertEquals("pc", it.getString("to"))
            assertEquals("7", it.getString("reqId"))
            assertEquals("Bold move", it.getString("text"))
            assertEquals("<b>Bold</b> move", it.getString("html"))
            assertTrue(it.getBoolean("fresh"))
            assertFalse(it.has("sensitive"))
            assertNull(errorOf(it))
        }
        assertTrue("The reader goes once it has read", reader.get().isFinishing)
    }

    @Test
    fun whatWasCopiedLongAgoIsSentToBeAskedAbout() {
        receive("pc", message("start-view"))
        put(ClipData.newPlainText("note", "yesterday's"), ago(10 * 60_000))
        copy()
        focusReader()
        results().single().let {
            assertEquals("yesterday's", it.getString("text"))
            assertFalse(it.getBoolean("fresh"))
        }
    }

    @Test
    fun aPrivateCopyLeavesOnlyIfItIsNew() {
        receive("pc", message("start-view"))
        put(secret(ClipData.newPlainText("password", "hunter2")), ago(10 * 60_000))
        copy()
        focusReader()
        results().single().let {
            assertFalse(it.has("text"))
            assertTrue(errorOf(it)!!.contains("marked private"))
        }

        put(secret(ClipData.newPlainText("password", "hunter2")), ago(3_000))
        copy(reqId = "2")
        focusReader()
        results().last().let {
            assertEquals("hunter2", it.getString("text"))
            assertTrue(it.getBoolean("sensitive"))
            assertTrue(it.getBoolean("fresh"))
        }
    }

    @Test
    fun askedForAsItIsTheClipboardIsSentWhateverItsAge() {
        receive("pc", message("start-view"))
        put(secret(ClipData.newPlainText("password", "hunter2")), ago(10 * 60_000))
        receive("pc", message("clip-get").put("reqId", "1").put("press", false))
        focusReader()
        results().single().let {
            assertEquals("hunter2", it.getString("text"))
            assertTrue(it.getBoolean("fresh"))
            assertTrue(it.getBoolean("sensitive"))
        }
    }

    @Test
    fun anEmptyClipboardIsSaidSo() {
        receive("pc", message("start-view"))
        copy()
        focusReader()
        results().single().let {
            assertFalse(it.has("text"))
            assertEquals("Nothing is selected on $name, and its clipboard has no text.", errorOf(it))
        }
    }

    @Test
    fun aReaderThatNeverGetsTheFocusStillGetsAnAnswer() {
        receive("pc", message("start-view"))
        put(ClipData.newPlainText("note", "hello"), ago(1_000))
        copy()
        // Android started it, or refused to without a word; either way it never had the focus.
        assertNotNull(shadowOf(context).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals("Android didn't let Remote Desktop read $name's clipboard. Try again.", errorOf(results().single()))
        // A request that is over is not served by a reader that turns up late.
        copy(reqId = "2")
        val reader = focusReader()!!
        assertEquals(2, results().size)
        assertTrue(reader.get().isFinishing)
    }

    @Test
    fun aViewerThatHasGoneGetsNothing() {
        receive("pc", message("start-view"))
        put(ClipData.newPlainText("note", "hello"), ago(1_000))
        copy()
        receive("pc", message("stop-view"))
        focusReader()
        assertEquals(emptyList<JSONObject>(), results())
        // And the phone is free for the next.
        receive("other-pc", message("start-view"))
        copy(from = "other-pc")
        focusReader()
        assertEquals("hello", results().single().getString("text"))
    }

    @Test
    fun oneCopyAtATime() {
        receive("pc", message("start-view"))
        receive("other-pc", message("start-view"))
        put(ClipData.newPlainText("note", "hello"), ago(1_000))
        copy()
        copy(from = "other-pc")
        results().single().let {
            assertEquals("other-pc", it.getString("to"))
            assertEquals("$name is still busy with the last copy. Try again.", errorOf(it))
        }
        focusReader()
        assertEquals("hello", results().last().getString("text"))
        assertEquals("pc", results().last().getString("to"))
    }

    @Test
    fun refusalsSayWhy() {
        put(ClipData.newPlainText("note", "hello"), ago(1_000))

        // Not approved by this phone: not a word.
        receive("stranger", message("start-view"))
        copy(from = "stranger")
        assertEquals(emptyList<JSONObject>(), results())

        // Approved, but not looking at the screen.
        copy()
        assertEquals("Open $name's screen on this PC to copy from it.", errorOf(results().last()))

        receive("pc", message("start-view"))
        Prefs(context).allowControl = false
        copy()
        assertEquals("\"View and control this phone\" is turned off on $name.", errorOf(results().last()))
        Prefs(context).allowControl = true

        receive("pc", message("start-view"))
        val keyguard = shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        keyguard.setKeyguardLocked(true)
        copy()
        assertEquals("$name is locked or its screen is off. Unlock it, then copy again.", errorOf(results().last()))
        keyguard.setKeyguardLocked(false)

        instanceField.set(null, null)
        copy()
        assertEquals("Remote Desktop's Accessibility service is off on $name, so it can't copy there.", errorOf(results().last()))

        // None of them started a reader, and none of them said what's on the clipboard.
        assertNull(shadowOf(context).nextStartedActivity)
        assertTrue(results().none { it.has("text") })
    }

    // ---------------- the rules on their own ----------------

    private val now = 1_000_000_000L

    private fun answer(pressed: Boolean, clip: PcClipboard.PhoneClip?, press: Boolean = true, read: Boolean = true) =
        PhoneClipCopy.answer("Phone", press, pressed, startedAtMs = now - 200, clip = clip, read = read, nowMs = now)

    @Test
    fun aCopyThatLandedIsSentAsTheAppCopiedIt() {
        val clip = PcClipboard.PhoneClip("selected", "<i>selected</i>", false, copiedAtMs = now - 100)
        assertEquals(PhoneClipCopy.Answer("selected", "<i>selected</i>", fresh = true), answer(pressed = true, clip))
    }

    @Test
    fun aCopyThatChangedNothingLeavesAnOldClipToBeAskedAbout() {
        // Copy was pressed in a field with nothing selected; this is from before.
        val old = PcClipboard.PhoneClip("older", null, false, copiedAtMs = now - 5 * 60_000)
        assertEquals(PhoneClipCopy.Answer("older", fresh = false), answer(pressed = true, old))
        // No field to press it in: the same.
        assertEquals(PhoneClipCopy.Answer("older", fresh = false), answer(pressed = false, old))
        // The phone's own Copy, tapped a moment ago, needs no question.
        val recent = old.copy(copiedAtMs = now - 20_000)
        assertEquals(PhoneClipCopy.Answer("older", fresh = true), answer(pressed = false, recent))
        // A clip from the future (the clock was put back) is not taken for new.
        assertFalse(answer(pressed = false, old.copy(copiedAtMs = now + 60_000)).fresh)
    }

    @Test
    fun aClipboardThatCantBeReadOrIsEmptyIsSaidSo() {
        assertEquals("Android didn't let Remote Desktop read Phone's clipboard. Try again.",
            answer(pressed = true, null, read = false).error)
        assertEquals("Nothing is selected on Phone, and its clipboard has no text.", answer(pressed = true, null).error)
        assertEquals("There's no text on Phone's clipboard.", answer(pressed = false, null, press = false).error)
    }

    @Test
    fun tooMuchTextIsRefusedAndHtmlGoesFirst() {
        val long = PcClipboard.PhoneClip("x".repeat(PcClipboard.MAX_CHARS + 1), null, false, copiedAtMs = now)
        assertTrue(answer(pressed = true, long).error!!.startsWith("That's too much text to copy"))
        val fits = "x".repeat(PcClipboard.MAX_CHARS - 2)
        val clip = PcClipboard.PhoneClip(fits, "<b>$fits</b>", false, copiedAtMs = now)
        assertEquals(PhoneClipCopy.Answer(fits, null, fresh = true), answer(pressed = true, clip))
    }

    @Test
    fun aClipWithNoTimeIsNotTakenForNew() {
        val clip = PcClipboard.PhoneClip("hello", null, false, copiedAtMs = 0)
        assertFalse(answer(pressed = true, clip).fresh)
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
