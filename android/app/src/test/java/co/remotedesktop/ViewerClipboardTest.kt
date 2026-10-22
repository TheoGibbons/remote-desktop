package co.remotedesktop

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewerClipboardTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private val previous = mutableMapOf<String, Any?>()
    private lateinit var controller: ActivityController<ViewerActivity>
    private lateinit var activity: ViewerActivity

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("appContext", "encKey", "ws", "fs")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("fs").set(ConnectionManager, FsHandler(context))
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        clipboard.clearPrimaryClip()
        controller = Robolectric.buildActivity(ViewerActivity::class.java).setup()
        activity = controller.get()
        socket.messages.clear()
    }

    @After
    fun cleanup() {
        controller.pause().stop().destroy()
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun copyReleasesLatchedModifiersBeforeAskingThePc() {
        val keyboard = field("keyboard") as KeyboardPanel
        buttons(keyboard).single { it.contentDescription == "Ctrl" }.performClick()
        socket.messages.clear()
        button("copyButton").performClick()
        assertEquals(listOf("key", "clip-get"), socket.messages.map { it.getString("type") })
        assertEquals("CTRL", socket.messages[0].getString("code"))
        assertEquals("up", socket.messages[0].getString("action"))
        assertEquals("pc", socket.messages[1].getString("to"))
        assertTrue(socket.messages[1].getString("reqId").isNotEmpty())
        assertFalse(button("copyButton").isEnabled)
    }

    @Test
    fun onlyTheAnswerToTheLatestCopyLandsOnTheClipboard() {
        button("copyButton").performClick()
        val reqId = copyReqId()
        val toasts = ShadowToast.shownToastCount()
        deliver(copyResult("stale").put("text", "old"))
        deliver(copyResult(reqId, from = "other").put("text", "spoofed"))
        assertNull(clipboard.primaryClip)
        assertFalse(button("copyButton").isEnabled)

        deliver(copyResult(reqId).put("text", "Red").put("html", "<b style=\"color:red\">Red</b>"))
        val item = clipboard.primaryClip!!.getItemAt(0)
        assertEquals("Red", item.text.toString())
        assertEquals("<b style=\"color:red\">Red</b>", item.htmlText)
        assertTrue(button("copyButton").isEnabled)
        assertEquals(toasts, ShadowToast.shownToastCount()) // Android 13+ confirms copies itself.

        deliver(copyResult(reqId).put("text", "again"))
        assertEquals("Red", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun fallingBackToTheOldClipboardIsMentionedAndSensitiveCopiesAreFlagged() {
        button("copyButton").performClick()
        deliver(copyResult(copyReqId()).put("text", "https://example.com").put("fresh", false))
        assertEquals("https://example.com", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(activity.getString(R.string.clip_copied_existing), ShadowToast.getTextOfLatestToast())

        button("copyButton").performClick()
        deliver(copyResult(copyReqId()).put("text", "hunter2").put("sensitive", true))
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    @Config(sdk = [32])
    fun olderPhonesAreToldTheCopyWorked() {
        button("copyButton").performClick()
        deliver(copyResult(copyReqId()).put("text", "hi"))
        assertEquals("Copied from Test PC", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun errorsOversizedTextAndASilentPcAreReported() {
        button("copyButton").performClick()
        deliver(copyResult(copyReqId()).put("error", "The focused window runs as administrator."))
        assertEquals("The focused window runs as administrator.", ShadowToast.getTextOfLatestToast())
        assertTrue(button("copyButton").isEnabled)

        button("copyButton").performClick()
        deliver(copyResult(copyReqId()).put("text", "x".repeat(PcClipboard.MAX_CHARS + 1)))
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Too much text"))
        assertNull(clipboard.primaryClip)

        button("copyButton").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PcClipboard.REPLY_TIMEOUT_MS))
        assertTrue(button("copyButton").isEnabled)
        assertEquals(activity.getString(R.string.clip_no_reply), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun copiedFilesAreOfferedThenDownloadedOneAtATime() {
        button("copyButton").performClick()
        deliver(copyResult(copyReqId()).put("folders", 1)
            .put("files", JSONArray(listOf("C:\\Users\\me\\a.pdf", "C:\\Users\\me\\b.png"))))
        assertNull(clipboard.primaryClip)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message, message.startsWith("The PC copied 2 files."))
        assertTrue(message, message.contains("a.pdf\nb.png"))
        assertTrue(message, message.endsWith("Folders are skipped."))

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val first = socket.messages.single { it.optString("type") == "fs-get" }
        assertEquals("pc", first.getString("to"))
        assertEquals("C:\\Users\\me\\a.pdf", first.getString("path"))
        // The PC refuses it, which comes without an fs-begin.
        refuse(first.getInt("xferId"), "File access is disabled on the remote device")
        val gets = socket.messages.filter { it.optString("type") == "fs-get" }
        assertEquals(listOf("C:\\Users\\me\\a.pdf", "C:\\Users\\me\\b.png"), gets.map { it.getString("path") })
        refuse(gets[1].getInt("xferId"), "Not found")
        assertEquals("Saved 0 of 2 files. a.pdf: File access is disabled on the remote device",
            ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun pasteSendsThePhonesClipboardButNeverAnEmptyOne() {
        button("pasteButton").performClick()
        assertTrue(socket.messages.none { it.optString("type") == "clip-set" })
        assertEquals(activity.getString(R.string.clip_nothing_to_paste), ShadowToast.getTextOfLatestToast())

        clipboard.setPrimaryClip(ClipData.newHtmlText("note", "Bold", "<b>Bold</b>"))
        button("pasteButton").performClick()
        val set = socket.messages.single { it.optString("type") == "clip-set" }
        assertEquals("pc", set.getString("to"))
        assertEquals("Bold", set.getString("text"))
        assertEquals("<b>Bold</b>", set.getString("html"))
        assertTrue(set.getBoolean("paste"))
        assertFalse(button("pasteButton").isEnabled)
        deliver(JSONObject().put("type", "clip-set-result").put("from", "pc").put("reqId", set.getString("reqId"))
            .put("error", "Remote control is turned off on this PC."))
        assertTrue(button("pasteButton").isEnabled)
        assertEquals("Remote control is turned off on this PC.", ShadowToast.getTextOfLatestToast())

        socket.messages.clear()
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "plain"))
        button("pasteButton").performClick()
        assertFalse(socket.messages.single { it.optString("type") == "clip-set" }.has("html"))
    }

    @Test
    fun leavingTheViewerDropsUnansweredRequests() {
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "mine"))
        button("copyButton").performClick()
        button("pasteButton").performClick()
        val reqId = copyReqId()
        controller.pause()
        assertTrue(button("copyButton").isEnabled)
        assertTrue(button("pasteButton").isEnabled)
        deliver(copyResult(reqId).put("text", "late"))
        assertEquals("mine", clipboard.primaryClip!!.getItemAt(0).text.toString())
        val toasts = ShadowToast.shownToastCount()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PcClipboard.REPLY_TIMEOUT_MS))
        assertEquals(toasts, ShadowToast.shownToastCount())
        controller.resume()
    }

    private fun field(name: String): Any? = ViewerActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun button(name: String) = field(name) as Button

    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun deliver(message: JSONObject) {
        @Suppress("UNCHECKED_CAST")
        (field("jsonListener") as (JSONObject) -> Unit)(message)
    }

    private fun copyReqId() = socket.messages.last { it.optString("type") == "clip-get" }.getString("reqId")

    private fun copyResult(reqId: String, from: String = "pc") = JSONObject().put("type", "clip-get-result")
        .put("from", from).put("reqId", reqId).put("fresh", true)

    private fun refuse(xferId: Int, error: String) {
        ConnectionManager.fs.handleJson(JSONObject().put("type", "fs-end").put("from", "pc")
            .put("xferId", xferId).put("ok", false).put("error", error), true)
        shadowOf(Looper.getMainLooper()).idle()
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
