package co.remotedesktop

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.stateListeners.remove(stateListener)
        PeerAuth.changeListeners.remove(trustListener)
        PeerAuth.approvalListeners.remove(approvalListener)
        PhoneScreenCapture.stateListeners.remove(captureStateListener)
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
        val subtitle = when {
            // Why the relay refused, e.g. "session key must be at least 16 characters".
            state == "blocked" -> "Too many join attempts from this network. Retrying automatically."
            state.startsWith("error: ") -> state.removePrefix("error: ").replaceFirstChar { it.uppercase() }
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
        for (peer in peers) {
            val row = layoutInflater.inflate(R.layout.item_session_peer, peerRows, false)
            val status = peerStatus(peer)
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
            row.findViewById<TextView>(R.id.peerDetail).apply {
                text = status.detail
                visibility = if (status.detail == null) View.GONE else View.VISIBLE
            }
            if (peer.device == "windows") {
                row.findViewById<View>(R.id.peerActions).visibility = View.VISIBLE
                row.findViewById<MaterialButton>(R.id.peerView).apply {
                    isEnabled = status.ready
                    contentDescription = getString(R.string.peer_view_description, peer.name)
                    setOnClickListener { open(ViewerActivity::class.java, peer) }
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
}
