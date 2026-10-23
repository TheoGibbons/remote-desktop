package co.remotedesktop

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
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
    fun windowsLineEndingsBecomeAndroidOnes() {
        assertTrue(PcClipboard.put(context, "one\r\ntwo\rthree\n", null, sensitive = false))
        assertEquals("one\ntwo\nthree\n", clipboard.primaryClip!!.getItemAt(0).text.toString())
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
        assertEquals(PcClipboard.PhoneClip("hello", null, false), PcClipboard.read(context))
        clipboard.setPrimaryClip(ClipData.newHtmlText("page", "Bold", "<b>Bold</b>"))
        assertEquals(PcClipboard.PhoneClip("Bold", "<b>Bold</b>", false), PcClipboard.read(context))
        clipboard.setPrimaryClip(ClipData(ClipDescription("photo", arrayOf("image/png")),
            ClipData.Item(Uri.parse("content://media/external/images/1"))))
        assertNull(PcClipboard.read(context))
        clipboard.setPrimaryClip(ClipData.newPlainText("empty", ""))
        assertNull(PcClipboard.read(context))
    }

    @Test
    fun readingNeverOpensAStreamOrAnIntent() {
        // A text file by URI would be read whole on the main thread; it isn't read at all.
        clipboard.setPrimaryClip(ClipData(ClipDescription("notes", arrayOf("text/plain")),
            ClipData.Item(Uri.parse("content://files/huge.txt"))))
        assertNull(PcClipboard.read(context))
        clipboard.setPrimaryClip(ClipData(ClipDescription("shortcut", arrayOf(ClipDescription.MIMETYPE_TEXT_INTENT)),
            ClipData.Item(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))))
        assertNull(PcClipboard.read(context))
        // A web link is the one URI worth pasting as text.
        clipboard.setPrimaryClip(ClipData.newRawUri("link", Uri.parse("https://example.com/a")))
        assertEquals("https://example.com/a", PcClipboard.read(context)!!.text)
    }

    @Test
    fun anHtmlOnlyClipStillHasText() {
        // Android insists on some plain text beside HTML, but it can be empty.
        clipboard.setPrimaryClip(ClipData(ClipDescription("page", arrayOf(ClipDescription.MIMETYPE_TEXT_HTML)),
            ClipData.Item("", "<p>Hello <b>there</b></p>")))
        assertEquals(PcClipboard.PhoneClip("Hello there", "<p>Hello <b>there</b></p>", false), PcClipboard.read(context))
    }

    @Test
    fun aClipMarkedSensitiveSaysSo() {
        clipboard.setPrimaryClip(ClipData.newPlainText("password", "hunter2").apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        })
        assertEquals(PcClipboard.PhoneClip("hunter2", null, true), PcClipboard.read(context))
    }

    @Test
    fun previewsShowTheStartOfLongText() {
        assertEquals("short", PcClipboard.preview("short"))
        assertEquals("a\nb", PcClipboard.preview("a\r\nb"))
        val lines = (1..20).joinToString("\n") { "line $it" }
        assertEquals((1..8).joinToString("\n") { "line $it" } + "…", PcClipboard.preview(lines))
        assertEquals("x".repeat(300) + "…", PcClipboard.preview("x".repeat(1000)))
    }
}
