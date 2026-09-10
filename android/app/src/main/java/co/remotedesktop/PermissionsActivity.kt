package co.remotedesktop

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * "Permissions & Access": the two ongoing consent switches for paired devices,
 * plus the three one-time OS grants (screen sharing, accessibility, files).
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var bannerCard: MaterialCardView
    private lateinit var bannerIcon: ImageView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerText: TextView
    private lateinit var screenShareSub: TextView
    private lateinit var screenShareStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var fileAccessStatus: TextView

    private val captureStateListener: () -> Unit = { refresh() }

    private val projectionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ScreenCaptureService.start(this, result.resultCode, result.data!!)
                Toast.makeText(this, "Screen sharing enabled", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ConnectionManager.init(this)
        setContentView(R.layout.activity_permissions)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        bannerCard = findViewById(R.id.bannerCard)
        bannerIcon = findViewById(R.id.bannerIcon)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerText = findViewById(R.id.bannerText)
        screenShareSub = findViewById(R.id.screenShareSub)
        screenShareStatus = findViewById(R.id.screenShareStatus)
        a11yStatus = findViewById(R.id.a11yStatus)
        fileAccessStatus = findViewById(R.id.fileAccessStatus)

        // Ongoing consent switches, checked live for every incoming peer message.
        findViewById<MaterialSwitch>(R.id.allowControlSwitch).apply {
            isChecked = prefs.allowControl
            setOnCheckedChangeListener { _, v -> prefs.allowControl = v; refresh() }
        }
        findViewById<MaterialSwitch>(R.id.allowFilesSwitch).apply {
            isChecked = prefs.allowFileAccess
            setOnCheckedChangeListener { _, v -> prefs.allowFileAccess = v; refresh() }
        }

        findViewById<View>(R.id.screenShareRow).setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                ScreenCaptureService.stop(this)
                refresh()
            } else {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
        findViewById<View>(R.id.a11yRow).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.fileAccessRow).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ScreenCaptureService.stateListeners.add(captureStateListener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        ScreenCaptureService.stateListeners.remove(captureStateListener)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun setPill(pill: TextView, granted: Boolean, offLabel: String = "Tap to enable") {
        pill.text = if (granted) "Enabled" else offLabel
        // mutate(): the three pills share this drawable's constant state.
        pill.background.mutate().setTint(getColor(if (granted) R.color.rd_good_container else R.color.rd_warn_container))
        pill.setTextColor(getColor(if (granted) R.color.rd_on_good_container else R.color.rd_on_warn_container))
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val sharing = ScreenCaptureService.isRunning
        val a11y = InputAccessibilityService.instance != null
        val filesOk = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

        setPill(screenShareStatus, sharing)
        setPill(a11yStatus, a11y)
        setPill(fileAccessStatus, filesOk)
        screenShareSub.text =
            if (sharing) "Sharing is active — tap to stop"
            else "Lets paired devices see this phone's screen"

        // The two consent switches count toward the total alongside the OS grants.
        val granted = listOf(sharing, a11y, filesOk, prefs.allowControl, prefs.allowFileAccess).count { it }
        if (granted == 5) {
            bannerTitle.text = "All good!"
            bannerText.text = "5 of 5 permissions enabled\nRemote features are fully available."
            bannerCard.setCardBackgroundColor(getColor(R.color.rd_good_container))
            bannerIcon.setColorFilter(getColor(R.color.rd_good))
            bannerTitle.setTextColor(getColor(R.color.rd_on_good_container))
            bannerText.setTextColor(getColor(R.color.rd_on_good_container))
        } else {
            bannerTitle.text = "Action needed"
            bannerText.text = "$granted of 5 permissions enabled\nTurn on or grant the items below."
            bannerCard.setCardBackgroundColor(getColor(R.color.rd_warn_container))
            bannerIcon.setColorFilter(getColor(R.color.rd_warn))
            bannerTitle.setTextColor(getColor(R.color.rd_on_warn_container))
            bannerText.setTextColor(getColor(R.color.rd_on_warn_container))
        }
    }
}
