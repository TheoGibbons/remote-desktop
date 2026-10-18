package co.remotedesktop

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

/** Auto privacy: what a PC's privacy screen does when viewing starts and stops. */
class PrivacySettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var autoPrivacySwitch: MaterialSwitch
    private lateinit var autoUnprivacySwitch: MaterialSwitch
    private lateinit var rememberPrivacySwitch: MaterialSwitch
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_privacy_settings)
        Ui.applySystemBarInsets(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        autoPrivacySwitch = findViewById(R.id.autoPrivacySwitch)
        autoUnprivacySwitch = findViewById(R.id.autoUnprivacySwitch)
        rememberPrivacySwitch = findViewById(R.id.rememberPrivacySwitch)
        refresh()
        bind(autoPrivacySwitch) { prefs.autoPrivacy = it }
        bind(autoUnprivacySwitch) { prefs.autoUnprivacy = it }
        bind(rememberPrivacySwitch) { prefs.rememberPrivacy = it }
    }

    // Prefs switches off whichever options a change conflicts with, so every
    // change re-reads all three.
    private fun bind(switch: MaterialSwitch, save: (Boolean) -> Unit) =
        switch.setOnCheckedChangeListener { _, checked ->
            if (!refreshing) {
                save(checked)
                refresh()
            }
        }

    private fun refresh() {
        refreshing = true
        autoPrivacySwitch.isChecked = prefs.autoPrivacy
        autoUnprivacySwitch.isChecked = prefs.autoUnprivacy
        rememberPrivacySwitch.isChecked = prefs.rememberPrivacy
        refreshing = false
    }
}
