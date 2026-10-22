package co.remotedesktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import org.json.JSONObject
import java.util.UUID

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
    private const val LABEL = "Remote Desktop"
    // ClipDescription.EXTRA_IS_SENSITIVE (API 33). Older versions ignore it.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

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

    /** Puts text, and its HTML version if there is one, on this phone's clipboard.
     *  @return false if Android refused even the plain text. */
    fun put(context: Context, text: String, html: String?, sensitive: Boolean): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        fun clip(withHtml: Boolean) =
            (if (withHtml && html != null) ClipData.newHtmlText(LABEL, text, html) else ClipData.newPlainText(LABEL, text))
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

    /** This phone's clipboard as text plus any HTML version, or null when it
     *  holds no text. An image must never be pasted as its content:// address. */
    fun read(context: Context): Pair<String, String?>? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0 || !clip.description.hasMimeType("text/*")) return null
        val item = clip.getItemAt(0)
        val text = item.coerceToText(context)?.toString().orEmpty()
        if (text.isEmpty()) return null
        return text to item.htmlText?.takeIf { it.isNotEmpty() }
    }

    /** Puts text on a PC's clipboard ("Copy to PC", shared text). The screen
     *  that asked has usually closed by the time the PC answers, so the outcome
     *  is a toast from the app context. */
    fun push(context: Context, peerId: String, peerName: String, text: String) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val reqId = UUID.randomUUID().toString()
        lateinit var listener: (JSONObject) -> Unit
        val timeout = Runnable {
            ConnectionManager.jsonListeners.remove(listener)
            toast(app, app.getString(R.string.copy_to_pc_no_reply, peerName))
        }
        listener = { msg ->
            if (msg.optString("type") == "clip-set-result" && msg.optString("from") == peerId &&
                msg.optString("reqId") == reqId
            ) {
                ConnectionManager.jsonListeners.remove(listener)
                main.removeCallbacks(timeout)
                toast(app, errorOf(msg) ?: app.getString(R.string.copy_to_pc_done, peerName))
            }
        }
        ConnectionManager.jsonListeners.add(listener)
        main.postDelayed(timeout, REPLY_TIMEOUT_MS)
        ConnectionManager.sendJson(
            JSONObject().put("type", "clip-set").put("to", peerId).put("reqId", reqId).put("text", text)
        )
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
