package co.remotedesktop

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
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
 * "Permissions & Access": the ongoing consent switches for paired devices,
 * plus Accessibility/file grants and optional, per-session smooth screen sharing.
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
    private lateinit var notificationsStatus: TextView
    private lateinit var offerCapturesSwitch: MaterialSwitch
    private lateinit var offerCapturesSub: TextView

    private val captureStateListener: () -> Unit = { refresh() }

    private val projectionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ScreenCaptureService.start(this, result.resultCode, result.data!!)
                Toast.makeText(this, "Smooth screen sharing enabled for this session", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ConnectionManager.init(this)
        setContentView(R.layout.activity_permissions)
        Ui.applySystemBarInsets(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        bannerCard = findViewById(R.id.bannerCard)
        bannerIcon = findViewById(R.id.bannerIcon)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerText = findViewById(R.id.bannerText)
        screenShareSub = findViewById(R.id.screenShareSub)
        screenShareStatus = findViewById(R.id.screenShareStatus)
        a11yStatus = findViewById(R.id.a11yStatus)
        fileAccessStatus = findViewById(R.id.fileAccessStatus)
        notificationsStatus = findViewById(R.id.notificationsStatus)
        if (Build.VERSION.SDK_INT >= 30) {
            findViewById<TextView>(R.id.screenShareTitle).setText(R.string.smooth_screen_sharing)
            findViewById<TextView>(R.id.a11yTitle).setText(R.string.screen_view_and_control)
            findViewById<TextView>(R.id.a11ySub).setText(R.string.accessibility_screen_view_help)
        }

        // Ongoing consent switches, checked live for every incoming peer message.
        findViewById<MaterialSwitch>(R.id.allowControlSwitch).apply {
            isChecked = prefs.allowControl
            setOnCheckedChangeListener { _, v -> prefs.allowControl = v; refresh() }
        }
        findViewById<MaterialSwitch>(R.id.allowFilesSwitch).apply {
            isChecked = prefs.allowFileAccess
            setOnCheckedChangeListener { _, v -> prefs.allowFileAccess = v; refresh() }
        }
        findViewById<MaterialSwitch>(R.id.allowPhonesSwitch).apply {
            isChecked = prefs.allowPhones
            setOnCheckedChangeListener { _, v -> prefs.allowPhones = v }
        }
        offerCapturesSub = findViewById(R.id.offerCapturesSub)
        offerCapturesSwitch = findViewById<MaterialSwitch>(R.id.offerCapturesSwitch).apply {
            isChecked = prefs.offerCaptures
            setOnCheckedChangeListener { _, v -> prefs.offerCaptures = v }
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
        // The app's own notification settings: Android asks only once or
        // twice by itself, and a switch turned off there stays off.
        findViewById<View>(R.id.notificationsRow).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onResume() {
        super.onResume()
        PhoneScreenCapture.stateListeners.add(captureStateListener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        PhoneScreenCapture.stateListeners.remove(captureStateListener)
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
        val filesOk = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

        val persistentCapture = Build.VERSION.SDK_INT >= 30
        setPill(screenShareStatus, sharing, if (persistentCapture) "Optional" else "Tap to enable")
        if (persistentCapture && !sharing) {
            screenShareStatus.background.mutate().setTint(
                com.google.android.material.color.MaterialColors.getColor(screenShareStatus,
                    com.google.android.material.R.attr.colorSurfaceVariant))
            screenShareStatus.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                screenShareStatus, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        setPill(a11yStatus, InputAccessibilityService.instance != null)
        setPill(fileAccessStatus, filesOk)
        setPill(notificationsStatus, notificationsOn(this))
        // A PC can only download what it's offered while file access is on.
        offerCapturesSwitch.isEnabled = prefs.allowFileAccess
        offerCapturesSub.setText(if (prefs.allowFileAccess) R.string.offer_captures_description
            else R.string.offer_captures_needs_files)
        screenShareSub.text =
            if (sharing) "Smoother viewing is active for this session — tap to stop"
            else if (persistentCapture) "Optional smoother viewing. Requires approval again after a restart."
            else "Required to view this phone. Requires approval again after a restart."

        val required = required(this)
        val granted = required.count { it }
        if (granted == required.size) {
            bannerTitle.text = "All good!"
            bannerText.text = "$granted of ${required.size} required permissions enabled\nRemote features are fully available."
            bannerCard.setCardBackgroundColor(getColor(R.color.rd_good_container))
            bannerIcon.setColorFilter(getColor(R.color.rd_good))
            bannerTitle.setTextColor(getColor(R.color.rd_on_good_container))
            bannerText.setTextColor(getColor(R.color.rd_on_good_container))
        } else {
            bannerTitle.text = "Action needed"
            bannerText.text = "$granted of ${required.size} required permissions enabled\nTurn on or grant the required items below."
            bannerCard.setCardBackgroundColor(getColor(R.color.rd_warn_container))
            bannerIcon.setColorFilter(getColor(R.color.rd_warn))
            bannerTitle.setTextColor(getColor(R.color.rd_on_warn_container))
            bannerText.setTextColor(getColor(R.color.rd_on_warn_container))
        }
    }

    companion object {
        /** A Samsung phone turns its screen on for a viewer only while this is true. */
        fun notificationsOn(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

        /**
         * Everything remote features need, each as whether it is on. This
         * screen and the main one both count it. Smooth sharing is optional
         * on Android 11+, where Accessibility does the default viewing.
         */
        fun required(context: Context): List<Boolean> {
            val prefs = Prefs(context)
            val persistentCapture = Build.VERSION.SDK_INT >= 30
            val filesOk = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
            return listOf(
                InputAccessibilityService.instance != null && (!persistentCapture || PhoneScreenCapture.isAvailable),
                filesOk, prefs.allowControl, prefs.allowFileAccess, notificationsOn(context),
            ) + if (persistentCapture) emptyList() else listOf(ScreenCaptureService.isRunning)
        }
    }
}
