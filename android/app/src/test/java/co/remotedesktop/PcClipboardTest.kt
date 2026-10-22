package co.remotedesktop

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PcClipboardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun htmlIsDroppedBeforeTextIsRefused() {
        val max = PcClipboard.MAX_CHARS
        assertEquals("a" to "<b>a</b>", PcClipboard.fit("a", "<b>a</b>"))
        assertEquals("x".repeat(max - 3) to "<b>", PcClipboard.fit("x".repeat(max - 3), "<b>"))
        assertEquals("x".repeat(max - 2) to null, PcClipboard.fit("x".repeat(max - 2), "<b>"))
        assertEquals("x".repeat(max) to null, PcClipboard.fit("x".repeat(max), null))
        assertNull(PcClipboard.fit("x".repeat(max + 1), null))
    }

    @Test
    fun repliesTreatMissingAndNullFieldsAsAbsent() {
        val reply = JSONObject().put("text", "null").put("html", JSONObject.NULL).put("error", "null")
        assertEquals("null", PcClipboard.stringOf(reply, "text")) // someone really copied "null"
        assertNull(PcClipboard.stringOf(reply, "html"))
        assertNull(PcClipboard.stringOf(reply, "missing"))
        assertNull(PcClipboard.errorOf(reply))
        assertNull(PcClipboard.errorOf(JSONObject().put("error", JSONObject.NULL)))
        assertEquals("Locked", PcClipboard.errorOf(JSONObject().put("error", "Locked")))
    }

    @Test
    fun richTextKeepsItsHtmlAndPlainTextStaysPlain() {
        assertTrue(PcClipboard.put(context, "Red", "<span style=\"color:red\">Red</span>", sensitive = false))
        clipboard.primaryClip!!.let {
            assertTrue(it.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
            assertEquals("Red", it.getItemAt(0).text.toString())
            assertEquals("<span style=\"color:red\">Red</span>", it.getItemAt(0).htmlText)
            assertFalse(it.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false)
        }
        assertTrue(PcClipboard.put(context, "plain", null, sensitive = false))
        clipboard.primaryClip!!.let {
            assertFalse(it.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
            assertEquals("plain", it.getItemAt(0).text.toString())
            assertNull(it.getItemAt(0).htmlText)
        }
    }

    @Test
    fun sensitiveClipsAreFlaggedForAndroidsCopyPreview() {
        assertTrue(PcClipboard.put(context, "hunter2", null, sensitive = true))
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun readingTakesTextAndHtmlButNeverAnImage() {
        clipboard.clearPrimaryClip()
        assertNull(PcClipboard.read(context))
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "hello"))
        assertEquals("hello" to null, PcClipboard.read(context))
        clipboard.setPrimaryClip(ClipData.newHtmlText("page", "Bold", "<b>Bold</b>"))
        assertEquals("Bold" to "<b>Bold</b>", PcClipboard.read(context))
        clipboard.setPrimaryClip(ClipData(ClipDescription("photo", arrayOf("image/png")),
            ClipData.Item(Uri.parse("content://media/external/images/1"))))
        assertNull(PcClipboard.read(context))
        clipboard.setPrimaryClip(ClipData.newPlainText("empty", ""))
        assertNull(PcClipboard.read(context))
    }
}
