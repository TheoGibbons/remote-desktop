package co.remotedesktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.text.Html
import org.json.JSONObject

/**
 * Clipboard exchange with a paired PC (see PROTOCOL.md, "Clipboard"). Copies
 * carry plain text plus, when the source app offered one, an HTML version, so
 * apps that accept rich paste keep bold, colours and links.
 */
object PcClipboard {
    /** Limit on text + HTML, in UTF-16 units. Android's clipboard goes through
     *  Binder (about 1 MB a transaction), and a big control message holds up
     *  the input queued behind it. */
    const val MAX_CHARS = 200_000
    const val REPLY_TIMEOUT_MS = 10_000L
    /** Android hands a text selection to another app trimmed to this many
     *  characters (TextUtils.trimToParcelableSize), without saying so. */
    const val SELECTION_LIMIT = 100_000
    private const val LABEL = "Remote Desktop"
    // ClipDescription.EXTRA_IS_SENSITIVE (API 33). Older versions ignore it.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val PREVIEW_CHARS = 300
    private const val PREVIEW_LINES = 8

    /** This phone's clipboard as Paste sends it. */
    data class PhoneClip(val text: String, val html: String?, val sensitive: Boolean)

    /** What fits under [MAX_CHARS]: the HTML version goes first, and text too
     *  long on its own doesn't fit at all (null). */
    fun fit(text: String, html: String?): Pair<String, String?>? = when {
        text.length > MAX_CHARS -> null
        html != null && text.length + html.length > MAX_CHARS -> text to null
        else -> text to html
    }

    /** A string field of a reply, or null when it is missing or JSON null. */
    fun stringOf(msg: JSONObject, name: String): String? =
        if (msg.isNull(name)) null else msg.optString(name).takeIf { it.isNotEmpty() }

    /** A reply's `error`, treating missing, JSON null and "null" alike. */
    fun errorOf(msg: JSONObject): String? = msg.optString("error").takeIf { it.isNotEmpty() && it != "null" }

    /** The start of some text, for a dialog that asks before it is sent or used. */
    fun preview(text: String): String {
        val lf = text.replace("\r\n", "\n").replace('\r', '\n')
        val shown = lf.take(PREVIEW_CHARS).split('\n').take(PREVIEW_LINES).joinToString("\n")
        return if (shown.length < lf.length) "$shown…" else shown
    }

    /** Puts text, and its HTML version if there is one, on this phone's clipboard.
     *  @return false if Android refused even the plain text. */
    fun put(context: Context, text: String, html: String?, sensitive: Boolean): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        // Windows ends lines with CRLF; Android apps expect LF and some show the CR.
        val lf = text.replace("\r\n", "\n").replace('\r', '\n')
        fun clip(withHtml: Boolean) =
            (if (withHtml && html != null) ClipData.newHtmlText(LABEL, lf, html) else ClipData.newPlainText(LABEL, lf))
                .apply {
                    // Keeps a password out of Android 13's copy preview and keyboard suggestions.
                    if (sensitive) description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
                }
        return try {
            clipboard.setPrimaryClip(clip(withHtml = true))
            true
        } catch (e: RuntimeException) {
            // Most likely too big for one Binder transaction, and the HTML is the bulk of it.
            html != null && try {
                clipboard.setPrimaryClip(clip(withHtml = false))
                true
            } catch (e: RuntimeException) {
                false
            }
        }
    }

    /**
     * This phone's clipboard as text plus any HTML version, or null when it
     * holds no text. Only text already in the clip is used: coerceToText would
     * read a content URI's whole stream on the main thread (a big file, a slow
     * cloud provider), paste an exception's message if that failed, and turn an
     * Intent into an intent: URI. A web link is the one URI worth pasting.
     */
    fun read(context: Context): PhoneClip? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val html = item.htmlText?.takeIf { it.isNotEmpty() }
        val text = item.text?.toString()?.takeIf { it.isNotEmpty() }
            ?: html?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }?.takeIf { it.isNotEmpty() }
            ?: item.uri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
            ?: return null
        val sensitive = clip.description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true
        return PhoneClip(text, html, sensitive)
    }
}
