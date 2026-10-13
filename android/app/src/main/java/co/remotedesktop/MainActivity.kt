package co.remotedesktop

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.journeyapps.barcodescanner.ScanContract

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var sessionIdSubtitle: TextView
    private lateinit var viewDesktopBtn: MaterialButton
    private lateinit var filesBtn: MaterialButton
    private lateinit var deviceActionsRow: android.view.View
    private lateinit var statusDot: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var permissionsSubtitle: TextView
    private lateinit var thisPhoneName: TextView

    private val stateListener: (String) -> Unit = { refreshStatus() }
    private val trustListener: () -> Unit = { refreshStatus() }
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
        viewDesktopBtn = findViewById(R.id.viewDesktopBtn)
        filesBtn = findViewById(R.id.filesBtn)
        deviceActionsRow = findViewById(R.id.deviceActionsRow)
        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusSubtitle = findViewById(R.id.statusSubtitle)
        permissionsSubtitle = findViewById(R.id.permissionsSubtitle)
        thisPhoneName = findViewById(R.id.thisPhoneName)

        findViewById<MaterialCardView>(R.id.sessionIdCard).setOnClickListener {
            startActivity(Intent(this, SessionIdActivity::class.java))
        }

        viewDesktopBtn.setOnClickListener { startActivity(Intent(this, ViewerActivity::class.java)) }
        filesBtn.setOnClickListener { startActivity(Intent(this, FileExplorerActivity::class.java)) }

        findViewById<MaterialCardView>(R.id.statusCard).setOnClickListener {
            startActivity(Intent(this, DevicesActivity::class.java))
        }
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
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.stateListeners.remove(stateListener)
        PeerAuth.changeListeners.remove(trustListener)
        PeerAuth.approvalListeners.remove(approvalListener)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val sessionId = prefs.sessionKey
        val missingSessionId = sessionId.isBlank()
        sessionIdSubtitle.text = if (missingSessionId) getString(R.string.session_id_required)
            else getString(R.string.session_id_preview, sessionId.take(6))
        sessionIdSubtitle.setTextColor(if (missingSessionId) getColor(R.color.rd_danger)
            else com.google.android.material.color.MaterialColors.getColor(
                sessionIdSubtitle, com.google.android.material.R.attr.colorOnSurfaceVariant))

        val state = ConnectionManager.state
        val (title, dotColor) = when {
            state == "connected" -> "Connected" to getColor(R.color.rd_good)
            state == "connecting" -> "Connecting…" to getColor(R.color.rd_warn)
            state.startsWith("error") -> "Connection error" to getColor(R.color.rd_danger)
            else -> "Not connected" to getColor(R.color.rd_danger)
        }
        statusTitle.text = title
        statusDot.setColorFilter(dotColor)

        val peers = ConnectionManager.peers
        statusSubtitle.text =
            if (peers.isEmpty()) "No paired devices online"
            else "Paired: " + peers.joinToString { "${it.name} (${it.device})" }
        // The desktop actions live with the connected-devices card and only
        // appear while a computer is actually online in the session.
        val hasWindows = peers.any { it.device == "windows" }
        deviceActionsRow.visibility = if (hasWindows) android.view.View.VISIBLE else android.view.View.GONE

        val pendingCount = PeerAuth.pendingRequests().size
        if (pendingCount > 0) {
            statusSubtitle.text = "$pendingCount device(s) waiting for approval — tap to review"
        }

        val filesOk = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        // Same five items as the Permissions & Access banner: 3 OS grants + 2 consents.
        val granted = listOf(ScreenCaptureService.isRunning,
            InputAccessibilityService.instance != null, filesOk,
            prefs.allowControl, prefs.allowFileAccess).count { it }
        permissionsSubtitle.text =
            if (granted == 5) "All permissions enabled — remote features fully available"
            else "$granted of 5 permissions enabled — tap to review"
        permissionsSubtitle.setTextColor(
            if (granted == 5)
                com.google.android.material.color.MaterialColors.getColor(
                    permissionsSubtitle, com.google.android.material.R.attr.colorOnSurfaceVariant)
            else getColor(R.color.rd_warn)
        )

        thisPhoneName.text = "${prefs.deviceName} (${PeerAuth.shortCode(PeerAuth.ownFingerprint)})"
    }
}
