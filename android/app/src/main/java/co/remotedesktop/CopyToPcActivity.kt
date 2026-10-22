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

/**
 * "Copy to PC" in Android's text-selection menu, also reached by sharing text
 * to the app: puts the selected text on a paired PC's clipboard. Android only
 * lets the app in front read the clipboard, so the selection arrives here
 * directly instead. Invisible unless it has to ask which PC, or wait for the
 * connection; it never replaces the selected text.
 */
class CopyToPcActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var text = ""
    private var dialog: AlertDialog? = null
    private var waiting = false
    private val stateListener: (String) -> Unit = { proceedOnceOnline() }
    private val trustListener: () -> Unit = { proceedOnceOnline() }
    // PCs that have said they trust this phone since the wait began. Trust is
    // checked both ways at once, and until a PC's side is done it drops what
    // this phone sends, so the copy would be lost.
    private val trustedBy = mutableSetOf<String>()
    private val authListener: (JSONObject) -> Unit = { msg ->
        if (msg.optString("type") == "auth-result" && msg.optString("status") == "trusted") {
            trustedBy.add(msg.optString("from"))
            proceedOnceOnline()
        }
    }
    private val connectTimeout = Runnable {
        Toast.makeText(applicationContext, R.string.copy_to_pc_unreachable, Toast.LENGTH_LONG).show()
        finish()
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
            Toast.makeText(applicationContext,
                getString(R.string.clip_too_large, selected.length, PcClipboard.MAX_CHARS), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        text = selected
        if (!choosePc()) waitForPc()
    }

    private fun approvedPcs() =
        ConnectionManager.peers.filter { it.device == "windows" && PeerAuth.isTrusted(it.id) }

    /** @return false while no approved PC is online. */
    private fun choosePc(pcs: List<ConnectionManager.Peer> = approvedPcs()): Boolean {
        // The PC on screen right now is the one the user is looking at.
        val viewing = pcs.filter { PhoneScreenCapture.isViewing(it.id) }
        when {
            pcs.isEmpty() -> return false
            pcs.size == 1 -> send(pcs[0])
            viewing.size == 1 -> send(viewing[0])
            else -> dialog = AlertDialog.Builder(this, dialogTheme())
                .setTitle(R.string.copy_to_pc_choose)
                .setItems(pcs.map { it.name }.toTypedArray()) { _, which -> send(pcs[which]) }
                .setOnCancelListener { finish() }
                .show()
        }
        return true
    }

    private fun send(pc: ConnectionManager.Peer) {
        PcClipboard.push(this, pc.id, pc.name, text)
        finish()
    }

    /** The app may have been asleep, or killed by the system, with no connection. */
    private fun waitForPc() {
        if (Prefs(this).sessionKey.length < 16) {
            Toast.makeText(applicationContext, R.string.copy_to_pc_not_set_up, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        waiting = true
        ConnectionManager.stateListeners.add(stateListener)
        PeerAuth.changeListeners.add(trustListener)
        ConnectionManager.jsonListeners.add(authListener)
        ConnectionManager.start(this)
        ConnectionService.start(this)
        dialog = AlertDialog.Builder(this, dialogTheme())
            .setMessage(R.string.copy_to_pc_connecting)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
        main.postDelayed(connectTimeout, PcClipboard.REPLY_TIMEOUT_MS)
    }

    /** A PC is usable once it has rejoined and the device check has passed
     *  again on both ends. */
    private fun proceedOnceOnline() {
        val ready = approvedPcs().filter { it.id in trustedBy }
        if (!waiting || isFinishing || ready.isEmpty()) return
        stopWaiting()
        dialog?.dismiss()
        dialog = null
        choosePc(ready)
    }

    private fun stopWaiting() {
        waiting = false
        main.removeCallbacks(connectTimeout)
        ConnectionManager.stateListeners.remove(stateListener)
        PeerAuth.changeListeners.remove(trustListener)
        ConnectionManager.jsonListeners.remove(authListener)
    }

    // The window itself stays transparent; only the dialogs follow dark mode.
    private fun dialogTheme() =
        if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    override fun onDestroy() {
        stopWaiting()
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }
}
