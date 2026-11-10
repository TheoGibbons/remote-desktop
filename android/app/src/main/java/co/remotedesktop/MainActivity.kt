package co.remotedesktop

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.journeyapps.barcodescanner.ScanContract
import org.json.JSONObject
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var sessionIdSubtitle: TextView
    private lateinit var peerRows: LinearLayout
    private lateinit var statusDot: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var retryBtn: ImageButton
    private lateinit var permissionsSubtitle: TextView
    private lateinit var thisPhoneName: TextView

    private val stateListener: (String) -> Unit = { refreshStatus() }
    private val trustListener: () -> Unit = { refreshStatus() }
    private val captureStateListener: () -> Unit = { refreshStatus() }
    private val approvalListener: (PeerAuth.PendingRequest) -> Unit = { Ui.showApprovalDialog(this, it) }

    // Another phone can be opened only once it says Allow other phones is on
    // there (`phones` in its file-access reply). Phones on older apps never reply.
    private val phonesAllowed = mutableMapOf<String, Boolean>()
    private val askedAt = mutableMapOf<String, Long>()
    private val jsonListener: (JSONObject) -> Unit = { msg ->
        if (msg.optString("type") == "file-access" && msg.has("phones")) {
            phonesAllowed[msg.optString("from")] = msg.optBoolean("phones")
            refreshStatus()
        }
    }
    // Turns a phone that hasn't answered into "update the app there".
    private val replyTimeout = Runnable { refreshStatus() }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let {
            SessionPairing.applyScannedSettings(this, it)
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ConnectionManager.init(this)
        setContentView(R.layout.activity_main)
        Ui.applySystemBarInsets(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            } else false
        }

        sessionIdSubtitle = findViewById(R.id.sessionIdSubtitle)
        peerRows = findViewById(R.id.peerRows)
        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusSubtitle = findViewById(R.id.statusSubtitle)
        retryBtn = findViewById(R.id.retryNow)
        permissionsSubtitle = findViewById(R.id.permissionsSubtitle)
        thisPhoneName = findViewById(R.id.thisPhoneName)

        findViewById<MaterialCardView>(R.id.sessionIdCard).setOnClickListener {
            startActivity(Intent(this, SessionIdActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.statusCard).setOnClickListener {
            startActivity(Intent(this, DevicesActivity::class.java))
        }
        // Try again now instead of waiting out the reconnect backoff (up to 30 s).
        retryBtn.setOnClickListener { Ui.reconnect(this) }
        findViewById<MaterialCardView>(R.id.permissionsCard).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.thisPhoneCard).setOnClickListener {
            Ui.showTextDialog(this, "Device name", prefs.deviceName) { name ->
                prefs.deviceName = name.ifBlank { Build.MODEL }
                refreshStatus()
                // The name is announced when joining, so rejoin to propagate it.
                if (prefs.sessionKey.isNotEmpty()) Ui.reconnect(this)
            }
        }

        findViewById<MaterialButton>(R.id.scanQrBtn).setOnClickListener {
            qrScanner.launch(SessionPairing.scanOptions())
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        if (prefs.autoConnect && prefs.sessionKey.length >= 16) {
            ConnectionService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.stateListeners.add(stateListener)
        PeerAuth.changeListeners.add(trustListener)
        PeerAuth.approvalListeners.add(approvalListener)
        PhoneScreenCapture.stateListeners.add(captureStateListener)
        ConnectionManager.jsonListeners.add(jsonListener)
        // Ask again: Allow other phones may have changed while this was away.
        askedAt.clear()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.stateListeners.remove(stateListener)
        PeerAuth.changeListeners.remove(trustListener)
        PeerAuth.approvalListeners.remove(approvalListener)
        PhoneScreenCapture.stateListeners.remove(captureStateListener)
        ConnectionManager.jsonListeners.remove(jsonListener)
        peerRows.removeCallbacks(replyTimeout)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val sessionId = prefs.sessionKey
        val missingSessionId = sessionId.isBlank()
        sessionIdSubtitle.text = if (missingSessionId) getString(R.string.session_id_required)
            else getString(R.string.session_id_preview, sessionId.take(23))
        sessionIdSubtitle.setTextColor(if (missingSessionId) getColor(R.color.rd_danger)
            else com.google.android.material.color.MaterialColors.getColor(
                sessionIdSubtitle, com.google.android.material.R.attr.colorOnSurfaceVariant))

        val state = ConnectionManager.state
        val (title, dotColor) = when {
            state == "connected" -> "Connected" to getColor(R.color.rd_good)
            state == "connecting" -> "Connecting…" to getColor(R.color.rd_warn)
            state == "blocked" -> "Blocked until " + DateFormat.getTimeFormat(this)
                .format(Date(ConnectionManager.blockedUntilMs)) to getColor(R.color.rd_danger)
            state.startsWith("error") -> "Connection error" to getColor(R.color.rd_danger)
            else -> "Not connected" to getColor(R.color.rd_danger)
        }
        statusTitle.text = title
        statusDot.setColorFilter(dotColor)
        retryBtn.visibility = if (state != "connected" && prefs.sessionKey.isNotEmpty())
            android.view.View.VISIBLE else android.view.View.GONE

        val peers = ConnectionManager.peers
        val problem = ConnectionLog.currentProblem()?.reason
        val subtitle = when {
            // Why the relay refused, e.g. "session key must be at least 16 characters".
            state == "blocked" -> "Too many join attempts from this network. Retrying automatically."
            state.startsWith("error: ") -> state.removePrefix("error: ").replaceFirstChar { it.uppercase() }
            // This phone's own reason, e.g. "No reply to a ping within 20 s".
            problem != null && (state == "connecting" || state.startsWith("disconnected: retrying")) ->
                "$problem. Retrying automatically."
            peers.isEmpty() -> "No paired devices online"
            // Each device online gets its own row instead.
            else -> null
        }
        statusSubtitle.text = subtitle
        statusSubtitle.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        showPeers(peers)

        val filesOk = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        val persistentCapture = Build.VERSION.SDK_INT >= 30
        val required = listOf(InputAccessibilityService.instance != null &&
            (!persistentCapture || PhoneScreenCapture.isAvailable), filesOk,
            prefs.allowControl, prefs.allowFileAccess) +
            if (persistentCapture) emptyList() else listOf(ScreenCaptureService.isRunning)
        val granted = required.count { it }
        permissionsSubtitle.text =
            if (granted == required.size) "All required permissions enabled — remote features fully available"
            else "$granted of ${required.size} required permissions enabled — tap to review"
        permissionsSubtitle.setTextColor(
            if (granted == required.size)
                com.google.android.material.color.MaterialColors.getColor(
                    permissionsSubtitle, com.google.android.material.R.attr.colorOnSurfaceVariant)
            else getColor(R.color.rd_warn)
        )

        thisPhoneName.text = "${prefs.deviceName} (${PeerAuth.shortCode(PeerAuth.ownFingerprint)})"
    }

    /** Where a device online in the session stands with this phone. Its
     *  buttons only work once each has approved the other. */
    private class PeerStatus(val label: String, val color: Int, val detail: String? = null, val ready: Boolean = false)

    private fun showPeers(peers: List<ConnectionManager.Peer>) {
        peerRows.removeAllViews()
        val online = peers.map { it.id }.toSet()
        phonesAllowed.keys.retainAll(online)
        askedAt.keys.retainAll(online)
        for (peer in peers) {
            val row = layoutInflater.inflate(R.layout.item_session_peer, peerRows, false)
            val status = phoneStatus(peer, peerStatus(peer))
            val kind = when (peer.device) {
                "windows" -> getString(R.string.peer_kind_pc)
                "android" -> getString(R.string.peer_kind_phone)
                else -> peer.device
            }
            row.findViewById<TextView>(R.id.peerName).text = getString(R.string.peer_name, peer.name, kind)
            row.findViewById<TextView>(R.id.peerStatus).apply {
                text = status.label
                setTextColor(status.color)
            }
            // A PC on an older version can be told to update from here.
            val latest = AppUpdate.latest(this)
            val behind = peer.device == "windows" && status.ready && latest != null &&
                AppUpdate.isNewer(latest, peer.version)
            val detail = status.detail ?: if (behind) getString(R.string.peer_pc_behind, latest, peer.name, peer.version) else null
            row.findViewById<TextView>(R.id.peerDetail).apply {
                text = detail
                visibility = if (detail == null) View.GONE else View.VISIBLE
            }
            row.findViewById<MaterialButton>(R.id.peerUpdate).apply {
                visibility = if (behind) View.VISIBLE else View.GONE
                if (behind) {
                    val updating = AppUpdate.isUpdating(peer.id)
                    isEnabled = !updating
                    text = if (updating) getString(R.string.peer_pc_updating) else getString(R.string.peer_pc_update, latest)
                    contentDescription = getString(R.string.peer_pc_update_description, peer.name, latest)
                    setOnClickListener {
                        AppUpdate.askToUpdate(peer.id)
                        refreshStatus()
                    }
                }
            }
            val phone = peer.device == "android"
            if (peer.device == "windows" || phone) {
                row.findViewById<View>(R.id.peerActions).visibility = View.VISIBLE
                row.findViewById<MaterialButton>(R.id.peerView).apply {
                    isEnabled = status.ready
                    contentDescription = getString(R.string.peer_view_description, peer.name)
                    if (phone) setIconResource(R.drawable.ic_smartphone)
                    setOnClickListener {
                        open(if (phone) PhoneViewerActivity::class.java else ViewerActivity::class.java, peer)
                    }
                }
                row.findViewById<MaterialButton>(R.id.peerBrowse).apply {
                    isEnabled = status.ready
                    contentDescription = getString(R.string.peer_browse_description, peer.name)
                    setOnClickListener { open(FileExplorerActivity::class.java, peer) }
                }
            }
            peerRows.addView(row)
        }
    }

    /** A phone approved both ways still needs Allow other phones on there,
     *  and an app new enough to have it. Asks it, then says which is missing. */
    private fun phoneStatus(peer: ConnectionManager.Peer, status: PeerStatus): PeerStatus {
        if (peer.device != "android" || !status.ready) return status
        val now = SystemClock.elapsedRealtime()
        val asked = askedAt[peer.id]
        if (asked == null) {
            askedAt[peer.id] = now
            ConnectionManager.sendJson(JSONObject().put("type", "get-file-access").put("to", peer.id))
            peerRows.postDelayed(replyTimeout, PHONE_REPLY_TIMEOUT_MS)
        }
        val allowed = phonesAllowed[peer.id]
        if (allowed == true) return status
        val detail = when {
            allowed == false -> getString(R.string.peer_phone_off, peer.name)
            asked != null && now - asked >= PHONE_REPLY_TIMEOUT_MS -> getString(R.string.peer_phone_outdated, peer.name)
            else -> null
        }
        return PeerStatus(status.label, status.color, detail)
    }

    private fun open(activity: Class<*>, peer: ConnectionManager.Peer) =
        startActivity(Intent(this, activity).putExtra(ConnectionManager.EXTRA_PEER_ID, peer.id))

    private fun peerStatus(peer: ConnectionManager.Peer): PeerStatus {
        val warn = getColor(R.color.rd_warn)
        val danger = getColor(R.color.rd_danger)
        // This phone's own prompt first: it can be answered right here.
        if (PeerAuth.pendingRequests().any { it.peerId == peer.id }) {
            return PeerStatus(getString(R.string.peer_needs_you), warn, getString(R.string.peer_needs_you_detail))
        }
        return when (PeerAuth.resultFrom(peer.id)) {
            "pending" -> PeerStatus(getString(R.string.peer_awaiting), warn,
                getString(R.string.peer_awaiting_detail, peer.name, PeerAuth.shortCode(PeerAuth.ownFingerprint)))
            "denied" -> PeerStatus(getString(R.string.peer_denied), danger, getString(R.string.peer_denied_detail, peer.name))
            "revoked" -> PeerStatus(getString(R.string.peer_revoked), danger, getString(R.string.peer_revoked_detail, peer.name))
            else -> when {
                PeerAuth.isTrusted(peer.id) && PeerAuth.trustedBy(peer.id) ->
                    PeerStatus(getString(R.string.peer_paired), getColor(R.color.rd_good), ready = true)
                PeerAuth.fingerprintOfPeer(peer.id) != null && !PeerAuth.isTrusted(peer.id) ->
                    PeerStatus(getString(R.string.peer_denied_here), danger)
                // The device check on joining hasn't finished.
                else -> PeerStatus(getString(R.string.peer_checking), com.google.android.material.color.MaterialColors
                    .getColor(peerRows, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        }
    }

    private companion object {
        /** How long another phone has to answer before it counts as an older app. */
        const val PHONE_REPLY_TIMEOUT_MS = 3_000L
    }
}
