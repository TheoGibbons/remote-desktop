package co.remotedesktop

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var deviceNameValue: TextView
    private lateinit var serverUrlValue: TextView
    private lateinit var autoPrivacyValue: TextView
    private lateinit var connectionHistoryValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ConnectionManager.init(this)
        setContentView(R.layout.activity_settings)
        Ui.applySystemBarInsets(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        deviceNameValue = findViewById(R.id.deviceNameValue)
        serverUrlValue = findViewById(R.id.serverUrlValue)

        autoPrivacyValue = findViewById(R.id.autoPrivacyValue)

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

        connectionHistoryValue = findViewById(R.id.connectionHistoryValue)
        findViewById<View>(R.id.connectionHistoryRow).setOnClickListener {
            startActivity(Intent(this, ConnectionHistoryActivity::class.java))
        }

        findViewById<View>(R.id.pairedDevicesRow).setOnClickListener {
            startActivity(Intent(this, DevicesActivity::class.java))
        }

        findViewById<View>(R.id.autoPrivacyRow).setOnClickListener {
            startActivity(Intent(this, PrivacySettingsActivity::class.java))
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
        connectionHistoryValue.text = ConnectionLog.lastDrop()?.let {
            val day = if (DateUtils.isToday(it.atMs)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
            getString(R.string.connection_history_last_drop,
                DateUtils.formatDateTime(this, it.atMs, DateUtils.FORMAT_SHOW_TIME or day), it.reason)
        } ?: getString(R.string.connection_history_no_drops)
        autoPrivacyValue.text = getString(when {
            prefs.rememberPrivacy -> R.string.auto_privacy_remembered
            prefs.autoPrivacy && prefs.autoUnprivacy -> R.string.auto_privacy_both
            prefs.autoPrivacy -> R.string.auto_privacy_description
            prefs.autoUnprivacy -> R.string.auto_unprivacy_description
            else -> R.string.auto_privacy_off
        })
    }
}
