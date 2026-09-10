package co.remotedesktop

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var deviceNameValue: TextView
    private lateinit var serverUrlValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ConnectionManager.init(this)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        deviceNameValue = findViewById(R.id.deviceNameValue)
        serverUrlValue = findViewById(R.id.serverUrlValue)

        findViewById<View>(R.id.deviceNameRow).setOnClickListener {
            Ui.showTextDialog(this, "Device name", prefs.deviceName) { name ->
                prefs.deviceName = name.ifBlank { Build.MODEL }
                refresh()
                // The name is announced when joining, so rejoin to propagate it.
                if (prefs.sessionKey.isNotEmpty()) Ui.reconnect(this)
            }
        }

        findViewById<View>(R.id.serverUrlRow).setOnClickListener {
            Ui.showTextDialog(
                this, "Server URL (ws:// or wss://)", prefs.serverUrl,
                InputType.TYPE_TEXT_VARIATION_URI,
            ) { url ->
                prefs.serverUrl = url
                refresh()
                if (prefs.sessionKey.isNotEmpty()) Ui.reconnect(this)
            }
        }

        findViewById<View>(R.id.pairedDevicesRow).setOnClickListener {
            startActivity(Intent(this, DevicesActivity::class.java))
        }

        findViewById<View>(R.id.regenerateRow).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Generate a new session ID?")
                .setMessage(
                    "Every device must be given the new ID and paired again. " +
                        "Do this if the current ID may have leaked."
                )
                .setPositiveButton("Generate") { _, _ ->
                    prefs.sessionKey = Ui.generateSessionKey()
                    Ui.reconnect(this)
                    Toast.makeText(this, "New session ID generated — update your other devices", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<TextView>(R.id.versionValue).text = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        deviceNameValue.text = prefs.deviceName
        serverUrlValue.text = prefs.serverUrl
    }
}
