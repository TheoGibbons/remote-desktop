package co.joypilot.remotedesktop

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider

/**
 * Pending pairing requests plus the trust list (disconnect/revoke) — new
 * devices must be approved here once; approval sticks until revoked or the
 * session ID changes.
 */
class DevicesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var pendingHeader: View
    private lateinit var pendingCard: View
    private lateinit var pendingGroup: LinearLayout
    private lateinit var trustedGroup: LinearLayout

    private val stateListener: (String) -> Unit = { rebuild() }
    private val trustListener: () -> Unit = { rebuild() }
    private val approvalListener: (PeerAuth.PendingRequest) -> Unit = { Ui.showApprovalDialog(this, it) }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ConnectionManager.init(this)
        setContentView(R.layout.activity_devices)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        pendingHeader = findViewById(R.id.pendingHeader)
        pendingCard = findViewById(R.id.pendingCard)
        pendingGroup = findViewById(R.id.pendingGroup)
        trustedGroup = findViewById(R.id.trustedGroup)

        findViewById<TextView>(R.id.ownCodeText).text =
            "This phone's code: ${PeerAuth.shortCode(PeerAuth.ownFingerprint)}"
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.stateListeners.add(stateListener)
        PeerAuth.changeListeners.add(trustListener)
        PeerAuth.approvalListeners.add(approvalListener)
        rebuild()
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.stateListeners.remove(stateListener)
        PeerAuth.changeListeners.remove(trustListener)
        PeerAuth.approvalListeners.remove(approvalListener)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** One list row: title + subtitle on the left, action buttons on the right. */
    private fun row(parent: LinearLayout, title: String, subtitle: String, vararg buttons: MaterialButton) {
        if (parent.childCount > 0) {
            parent.addView(MaterialDivider(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dividerThickness)
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(16)
            })
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            alpha = 0.7f
        })
        line.addView(texts)
        buttons.forEach { line.addView(it) }
        parent.addView(line)
    }

    private fun textButton(label: String, onClick: () -> Unit) = MaterialButton(
        this, null, com.google.android.material.R.attr.borderlessButtonStyle
    ).apply {
        text = label
        setOnClickListener { onClick() }
    }

    @SuppressLint("SetTextI18n")
    private fun rebuild() {
        pendingGroup.removeAllViews()
        trustedGroup.removeAllViews()

        val pending = PeerAuth.pendingRequests()
        pendingHeader.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        pendingCard.visibility = pendingHeader.visibility
        for (req in pending) {
            row(
                pendingGroup,
                "${req.name} (${req.device})",
                "Code ${PeerAuth.shortCode(req.fingerprint)} — wants to pair",
                textButton("Allow") { PeerAuth.approve(req.fingerprint) },
                textButton("Deny") { PeerAuth.deny(req.fingerprint) },
            )
        }

        val trusted = PeerAuth.trustedDevices()
        if (trusted.isEmpty()) {
            trustedGroup.addView(TextView(this).apply {
                text = "No paired devices yet — a request appears here when one connects."
                setPadding(dp(16), dp(16), dp(16), dp(16))
                alpha = 0.7f
            })
            return
        }

        for (d in trusted) {
            val onlinePeerId = ConnectionManager.peers
                .firstOrNull { PeerAuth.isTrusted(it.id) && PeerAuth.fingerprintOfPeer(it.id) == d.fingerprint }
                ?.id
            val buttons = mutableListOf(textButton("Revoke") {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Revoke ${d.name}?")
                    .setMessage("It will be disconnected and must be approved again before it can connect.")
                    .setPositiveButton("Revoke") { _, _ -> PeerAuth.revoke(d.fingerprint) }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            if (onlinePeerId != null) {
                buttons.add(0, textButton("Disconnect") {
                    PeerAuth.disconnectPeer(onlinePeerId)
                    // Hang up whatever it was watching; it stays trusted.
                    ScreenCaptureService.onViewStopped()
                    Toast.makeText(this, "Disconnected ${d.name} (it stays trusted)", Toast.LENGTH_SHORT).show()
                })
            }
            row(
                trustedGroup,
                "${d.name} (${d.device})",
                "Code ${PeerAuth.shortCode(d.fingerprint)} — " +
                    if (onlinePeerId != null) "online" else "offline, trusted",
                *buttons.toTypedArray(),
            )
        }
    }
}
