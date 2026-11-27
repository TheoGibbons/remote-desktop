package co.remotedesktop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Answers a viewer's `clip-get` (PROTOCOL.md, "Clipboard"): the Copy in a
 * PC's phone window. It presses Copy in the text field in use, then sends
 * what is on the clipboard, with its HTML version when the app that copied
 * offered one, so a rich paste on the PC keeps bold, colours and links.
 * Anywhere else the phone's own Copy has to be tapped first, which is how
 * most apps copy anyway: their own Copy buttons and selection menus. Nothing
 * on the screen is read for it, the text field's content included.
 *
 * Android lets only the app in front, or the keyboard, read the clipboard.
 * So [ClipReadActivity], an invisible screen, comes to the front for the
 * moment it takes to read it. The Accessibility service is what lets it start
 * with no window of the app open. Android 12 and later then say on the phone
 * that Remote Desktop pasted from the clipboard.
 *
 * One request at a time, kept on the main thread.
 */
internal object PhoneClipCopy {
    /** A clip put on the clipboard this recently counts as just copied: the
     *  person tapped Copy on the phone, then asked for it at the PC. */
    const val RECENT_MS = 60_000L
    /** How long a copy that was pressed gets to land on the clipboard once
     *  the screen is up. A web page's copy goes through the browser's own
     *  processes and can come a moment after the field says it is done. */
    const val LAND_WAIT_MS = 300L
    /** How long the whole of a request may take: pressing Copy, bringing the
     *  screen up and reading. A viewer waits about 10 s. */
    private const val ANSWER_WAIT_MS = 5_000L
    private const val TAG = "PhoneClipCopy"

    /** What a `clip-get-result` says. */
    data class Answer(
        val text: String? = null,
        val html: String? = null,
        val sensitive: Boolean = false,
        val fresh: Boolean = true,
        val error: String? = null,
    )

    private class Request(
        val token: Long, val context: Context, val from: String, val reqId: String?, val press: Boolean,
        val refusal: () -> String?, val startedAtMs: Long,
    ) {
        var pressed = false     // Copy was pressed in a text field
        var screenAsked = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var current: Request? = null
    private var tokens = 0L
    private val gaveUp = Runnable { finish(null) }

    /** Where Copy is pressed: it waits on the app in front. A test runs it in place. */
    internal var offMain: Executor = Executors.newSingleThreadExecutor()

    // ---------------- what to answer ----------------

    /**
     * What to send back.
     * @param name this phone's name, for the person at the PC.
     * @param press whether the viewer asked for the selection to be copied,
     *   rather than for the clipboard as it is.
     * @param pressed whether Copy was pressed in a text field for it.
     * @param startedAtMs when the request began, on the wall clock.
     * @param clip the clipboard as read; null when it held no text.
     * @param read whether it could be read at all.
     */
    fun answer(name: String, press: Boolean, pressed: Boolean, startedAtMs: Long,
               clip: PcClipboard.PhoneClip?, read: Boolean, nowMs: Long): Answer {
        if (!read) return Answer(error = "Android didn't let Remote Desktop read $name's clipboard. Try again.")
        if (clip == null) return Answer(error =
            if (press) "Nothing is selected on $name, and its clipboard has no text." else "There's no text on $name's clipboard.")
        // The copy this request pressed reached the clipboard, or the phone's
        // own Copy was tapped a moment ago: either is what's wanted.
        val landed = pressed && clip.copiedAtMs >= startedAtMs
        val recent = clip.copiedAtMs > 0 && nowMs - clip.copiedAtMs in 0..RECENT_MS
        val fresh = !press || landed || recent
        // A secret leaves only as what was just copied or what was asked for
        // as it is, never as the leftover of a copy that didn't happen.
        if (clip.sensitive && !fresh)
            return Answer(error = "Nothing new was copied on $name, and what's on its clipboard is marked private, " +
                "as a password manager does. Copy it again on the phone to send it.")
        return fit(clip.text, clip.html, clip.sensitive, fresh)
    }

    private fun fit(text: String, html: String?, sensitive: Boolean, fresh: Boolean): Answer {
        val (fitText, fitHtml) = PcClipboard.fit(text, html) ?: return Answer(error =
            "That's too much text to copy (%,d characters; the limit is %,d).".format(text.length, PcClipboard.MAX_CHARS))
        return Answer(fitText, fitHtml, sensitive, fresh)
    }

    fun json(to: String, reqId: String?, answer: Answer): JSONObject {
        val msg = JSONObject().put("type", "clip-get-result").put("to", to).put("fresh", answer.fresh)
        if (reqId != null) msg.put("reqId", reqId)
        answer.text?.let { msg.put("text", it) }
        answer.html?.let { msg.put("html", it) }
        if (answer.sensitive) msg.put("sensitive", true)
        answer.error?.let { msg.put("error", it) }
        return msg
    }

    // ---------------- a request ----------------

    /**
     * `clip-get` from an approved device. [refusal] says why it may not
     * have this phone's clipboard, asked again before the answer goes.
     */
    fun handle(context: Context, from: String, reqId: String?, press: Boolean, refusal: () -> String?) {
        val app = context.applicationContext
        main.post { start(app, from, reqId, press, refusal) }
    }

    private fun start(context: Context, from: String, reqId: String?, press: Boolean, refusal: () -> String?) {
        val name = Prefs(context).deviceName
        fun refuse(error: String) = ConnectionManager.sendJson(json(from, reqId, Answer(error = error)))
        refusal()?.let { return refuse(it) }
        // Only for a device viewing the screen, which the phone shows: its
        // clipboard is never read for one nobody can see at it.
        if (!PhoneScreenCapture.isViewing(from)) return refuse("Open $name's screen on this PC to copy from it.")
        val service = InputAccessibilityService.instance
            ?: return refuse("Remote Desktop's Accessibility service is off on $name, so it can't copy there.")
        if (!LockState.read(context).inUse) return refuse("$name is locked or its screen is off. Unlock it, then copy again.")
        if (current != null) return refuse("$name is still busy with the last copy. Try again.")
        val request = Request(++tokens, context, from, reqId, press, refusal, System.currentTimeMillis())
        current = request
        main.postDelayed(gaveUp, ANSWER_WAIT_MS)
        offMain.execute {
            val pressed = press && service.pressCopy()
            main.post { showScreen(request, pressed, service) }
        }
    }

    private fun showScreen(request: Request, pressed: Boolean, service: InputAccessibilityService) {
        if (current !== request) return
        request.pressed = pressed
        request.screenAsked = true
        try {
            service.startActivity(ClipReadActivity.intent(service, request.token))
        } catch (e: RuntimeException) {
            // A start Android refuses mostly fails without a word; this waits for the time limit either way.
            Log.w(TAG, "Could not start the clipboard reader", e)
        }
    }

    /** Whether [ClipReadActivity] with this token is still wanted. */
    fun wants(token: Long): Boolean = current?.let { it.token == token && it.screenAsked } == true

    /**
     * [ClipReadActivity] has the focus and reads the clipboard. False while a
     * copy that was pressed hasn't landed yet and [last] says there is time to
     * wait; true once the request is over, answered or not this one's.
     */
    fun read(context: Context, token: Long, last: Boolean): Boolean {
        val request = current?.takeIf { it.token == token } ?: return true
        val clip = try {
            PcClipboard.read(context)
        } catch (e: RuntimeException) {
            // Too big to come through Binder, or the app that copied has gone.
            Log.w(TAG, "Could not read the clipboard", e)
            null
        }
        val landed = clip != null && clip.copiedAtMs >= request.startedAtMs
        if (request.pressed && !landed && !last) return false
        finish(clip, read = true)
        return true
    }

    private fun finish(clip: PcClipboard.PhoneClip?, read: Boolean = false) {
        val request = current ?: return
        current = null
        main.removeCallbacks(gaveUp)
        // The device that asked may have stopped being approved meanwhile, or
        // left: its id would then be nobody's, or another device's. Or it has
        // closed the screen it asked from.
        if (!PeerAuth.isTrusted(request.from) || !PhoneScreenCapture.isViewing(request.from)) return
        val answer = request.refusal()?.let { Answer(error = it) }
            ?: answer(Prefs(request.context).deviceName, request.press, request.pressed, request.startedAtMs, clip, read,
                System.currentTimeMillis())
        ConnectionManager.sendJson(json(request.from, request.reqId, answer))
    }
}
