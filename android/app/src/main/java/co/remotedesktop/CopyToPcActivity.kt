package co.remotedesktop

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.util.UUID

/**
 * "Copy to PC" in Android's text-selection menu, also reached by sharing text
 * to the app: puts the selected text on a paired PC's clipboard. Android only
 * lets the app in front read the clipboard, so the selection arrives here
 * directly instead. It never replaces the selected text.
 *
 * It asks before sending, showing the text and the PC: this activity has to
 * be exported for the menu, so any app could open it, and the PC would
 * otherwise paste whatever another app chose. It stays open until the PC
 * answers, because a toast from an app that has gone to the background is
 * dropped when its notifications are off.
 */
class CopyToPcActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var text = ""
    private var dialog: AlertDialog? = null
    // Whether [dialog] is the waiting one, whose message changes in place.
    private var dialogIsWaiting = false
    // Waiting for a PC to come online and approve this phone.
    private var waiting = false
    private var timing = false
    // The PC the text went to, and the request id its answer will carry.
    private var sentTo: ConnectionManager.Peer? = null
    private var reqId: String? = null
    private val stateListener: (String) -> Unit = { onConnectionChanged() }
    private val trustListener: () -> Unit = { onConnectionChanged() }
    private val jsonListener: (JSONObject) -> Unit = { onMessage(it) }
    private val connectTimeout = Runnable {
        timing = false
        fail(getString(R.string.copy_to_pc_unreachable))
    }
    private val replyTimeout = Runnable {
        sentTo?.let { fail(getString(R.string.copy_to_pc_no_reply, it.name)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.init(this)
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (selected.isBlank()) {
            finish()
            return
        }
        if (PcClipboard.fit(selected, null) == null) {
            Toast.makeText(this,
                getString(R.string.clip_too_large, selected.length, PcClipboard.MAX_CHARS), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        text = selected
        ConnectionManager.stateListeners.add(stateListener)
        PeerAuth.changeListeners.add(trustListener)
        ConnectionManager.jsonListeners.add(jsonListener)
        if (!offerPcs()) waitForPc()
    }

    private fun approvedPcs() =
        ConnectionManager.peers.filter { it.device == "windows" && PeerAuth.isTrusted(it.id) }

    /** PCs approved on both ends: a PC drops what an unapproved phone sends. */
    private fun readyPcs() = approvedPcs().filter { PeerAuth.trustedBy(it.id) }

    /** Asks the user to confirm the one ready PC, or to choose among several.
     *  @return false while no PC is ready. */
    private fun offerPcs(): Boolean {
        val pcs = readyPcs()
        when {
            pcs.isEmpty() -> return false
            pcs.size == 1 -> confirm(pcs[0])
            else -> choose(pcs)
        }
        return true
    }

    private fun confirm(pc: ConnectionManager.Peer) {
        val message = buildString {
            append(PcClipboard.preview(text))
            // Android trims a selection it hands to another app, without saying so.
            if (text.length >= PcClipboard.SELECTION_LIMIT - 1) append("\n\n").append(getString(R.string.copy_to_pc_truncated))
        }
        show(builder()
            .setTitle(getString(R.string.copy_to_pc_confirm, pc.name))
            .setMessage(message)
            .setPositiveButton(R.string.copy_to_pc_copy) { _, _ -> send(pc) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() })
    }

    /** Picking a PC is the confirmation. The one viewing this phone, which is
     *  probably the one in front of the user, comes first. */
    private fun choose(pcs: List<ConnectionManager.Peer>) {
        val ordered = pcs.sortedByDescending { PhoneScreenCapture.isViewing(it.id) }
        show(builder()
            .setTitle(R.string.copy_to_pc_choose)
            .setItems(ordered.map { it.name }.toTypedArray()) { _, which -> send(ordered[which]) })
    }

    private fun send(pc: ConnectionManager.Peer) {
        if (ConnectionManager.state != "connected" || ConnectionManager.peers.none { it.id == pc.id }) {
            fail(getString(R.string.clip_connection_lost))
            return
        }
        val id = UUID.randomUUID().toString()
        sentTo = pc
        reqId = id
        show(builder().setMessage(getString(R.string.copy_to_pc_copying, pc.name)))
        ConnectionManager.sendJson(
            JSONObject().put("type", "clip-set").put("to", pc.id).put("reqId", id).put("text", text)
        )
        main.postDelayed(replyTimeout, PcClipboard.REPLY_TIMEOUT_MS)
    }

    private fun onMessage(msg: JSONObject) {
        val pc = sentTo ?: return
        if (msg.optString("type") != "clip-set-result" || msg.optString("from") != pc.id ||
            msg.optString("reqId") != reqId
        ) return
        main.removeCallbacks(replyTimeout)
        sentTo = null
        val error = PcClipboard.errorOf(msg)
        if (error != null) {
            fail(error)
            return
        }
        // Shown while this activity is still in front, so Android doesn't drop it.
        Toast.makeText(this, getString(R.string.copy_to_pc_done, pc.name), Toast.LENGTH_SHORT).show()
        finish()
    }

    /** The app may have been asleep, or killed by the system, with no connection. */
    private fun waitForPc() {
        if (Prefs(this).sessionKey.length < 16) {
            Toast.makeText(this, R.string.copy_to_pc_not_set_up, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        waiting = true
        ConnectionManager.start(this)
        ConnectionService.start(this)
        // The user is waiting on this; don't sit out a reconnect backoff of up to 30 s.
        ConnectionManager.reconnectNow()
        proceedOnceReady()
    }

    private fun onConnectionChanged() {
        if (isFinishing) return
        val pc = sentTo
        if (pc != null) {
            if (ConnectionManager.state != "connected" || ConnectionManager.peers.none { it.id == pc.id }) {
                fail(getString(R.string.clip_connection_lost))
            }
            return
        }
        proceedOnceReady()
    }

    /** Offers the PC once it has rejoined and both ends have approved each
     *  other again; until then says what it is waiting for. */
    private fun proceedOnceReady() {
        if (!waiting || isFinishing) return
        if (readyPcs().isNotEmpty()) {
            stopWaiting()
            offerPcs()
            return
        }
        val approved = approvedPcs()
        val denied = approved.firstOrNull { PeerAuth.resultFrom(it.id) == "denied" }
        val pending = approved.firstOrNull { PeerAuth.resultFrom(it.id) == "pending" }
        // Checked, but not approved here: this phone's own approval prompt is waiting.
        val unapproved = ConnectionManager.peers.firstOrNull {
            it.device == "windows" && !PeerAuth.isTrusted(it.id) && PeerAuth.fingerprintOfPeer(it.id) != null
        }
        when {
            ConnectionManager.state == "blocked" -> fail(getString(R.string.copy_to_pc_blocked))
            denied != null -> fail(getString(R.string.copy_to_pc_denied, denied.name))
            approved.isEmpty() && unapproved != null ->
                fail(getString(R.string.copy_to_pc_approve_here, unapproved.name))
            pending != null -> {
                // Approval needs someone at the PC, so no timer: they can cancel.
                stopTimer()
                showWaiting(getString(R.string.copy_to_pc_approve, pending.name))
            }
            else -> {
                if (!timing) {
                    timing = true
                    main.postDelayed(connectTimeout, PcClipboard.REPLY_TIMEOUT_MS)
                }
                showWaiting(getString(R.string.copy_to_pc_connecting))
            }
        }
    }

    private fun showWaiting(message: String) {
        val current = dialog
        if (dialogIsWaiting && current != null && current.isShowing) {
            current.setMessage(message)
            return
        }
        show(builder().setMessage(message).setNegativeButton(android.R.string.cancel) { _, _ -> finish() })
        dialogIsWaiting = true
    }

    private fun stopTimer() {
        timing = false
        main.removeCallbacks(connectTimeout)
    }

    private fun stopWaiting() {
        waiting = false
        stopTimer()
    }

    /** Says what went wrong and closes once the user has read it. */
    private fun fail(message: String) {
        stopWaiting()
        main.removeCallbacks(replyTimeout)
        sentTo = null
        reqId = null
        show(builder().setMessage(message).setPositiveButton(android.R.string.ok) { _, _ -> finish() })
    }

    private fun builder() = AlertDialog.Builder(this, dialogTheme()).setOnCancelListener { finish() }

    private fun show(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialogIsWaiting = false
        dialog = builder.show()
    }

    // The window itself stays transparent; only the dialogs follow dark mode.
    private fun dialogTheme() =
        if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    override fun onDestroy() {
        stopWaiting()
        main.removeCallbacks(replyTimeout)
        ConnectionManager.stateListeners.remove(stateListener)
        PeerAuth.changeListeners.remove(trustListener)
        ConnectionManager.jsonListeners.remove(jsonListener)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }
}
