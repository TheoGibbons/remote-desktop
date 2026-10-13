package co.remotedesktop

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract

/** Session setup, reached from the main-screen Session ID card. */
class SessionIdActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var sessionKeyBox: TextInputEditText
    private lateinit var sessionKeyInput: TextInputLayout
    private lateinit var saveBtn: MaterialButton

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let {
            if (SessionPairing.applyScannedSettings(this, it)) {
                sessionKeyBox.setText(prefs.sessionKey)
                refreshInput()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ConnectionManager.init(this)
        setContentView(R.layout.activity_session_id)
        Ui.applySystemBarInsets(this)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        sessionKeyBox = findViewById(R.id.sessionKeyBox)
        sessionKeyInput = findViewById(R.id.sessionKeyInput)
        saveBtn = findViewById(R.id.saveBtn)
        sessionKeyBox.setText(prefs.sessionKey)
        sessionKeyBox.doAfterTextChanged { refreshInput() }
        saveBtn.setOnClickListener { saveAndConnect() }

        findViewById<MaterialButton>(R.id.regenerateBtn).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Generate a new session ID?")
                .setMessage("Every device must be given the new ID and paired again. " +
                    "Do this if the current ID may have leaked.")
                .setPositiveButton("Generate") { _, _ ->
                    sessionKeyBox.setText(Ui.generateSessionKey())
                    saveAndConnect()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<MaterialButton>(R.id.scanQrBtn).setOnClickListener {
            qrScanner.launch(SessionPairing.scanOptions())
        }
        refreshInput()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        refreshInput()
    }

    private fun refreshInput() {
        val key = sessionKeyBox.text.toString().trim()
        sessionKeyInput.error = if (key.isBlank()) getString(R.string.session_id_required) else null
        saveBtn.visibility = if (key != prefs.sessionKey) View.VISIBLE else View.GONE
    }

    private fun saveAndConnect() {
        val key = sessionKeyBox.text.toString().trim()
        if (key.isBlank()) {
            sessionKeyInput.error = getString(R.string.session_id_required)
            return
        }
        SessionKeyPolicy.weaknessOf(key)?.let { weakness ->
            sessionKeyInput.error = "This session ID is too easy to guess: $weakness. " +
                "Tap Generate new session ID for a strong one."
            return
        }
        prefs.sessionKey = key
        refreshInput()
        Ui.reconnect(this)
    }
}
