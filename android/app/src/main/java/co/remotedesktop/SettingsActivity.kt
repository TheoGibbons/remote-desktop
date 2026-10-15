package co.remotedesktop

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var deviceNameValue: TextView
    private lateinit var serverUrlValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ConnectionManager.init(this)
        setContentView(R.layout.activity_settings)
        Ui.applySystemBarInsets(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        deviceNameValue = findViewById(R.id.deviceNameValue)
        serverUrlValue = findViewById(R.id.serverUrlValue)

        findViewById<MaterialSwitch>(R.id.autoPrivacySwitch).apply {
            isChecked = prefs.autoPrivacy
            setOnCheckedChangeListener { _, checked -> prefs.autoPrivacy = checked }
        }
        findViewById<MaterialSwitch>(R.id.autoUnprivacySwitch).apply {
            isChecked = prefs.autoUnprivacy
            setOnCheckedChangeListener { _, checked -> prefs.autoUnprivacy = checked }
        }

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
