package co.joypilot.remotedesktop

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.security.SecureRandom
import java.util.Base64

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var serverBox: EditText
    private lateinit var keyBox: EditText
    private lateinit var nameBox: EditText
    private lateinit var statusText: TextView
    private lateinit var peersText: TextView
    private lateinit var viewDesktopBtn: Button
    private lateinit var filesBtn: Button
    private lateinit var shareBtn: Button

    private val stateListener: (String) -> Unit = { refreshStatus() }

    private val projectionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ScreenCaptureService.start(this, result.resultCode, result.data!!)
                Toast.makeText(this, "Screen sharing enabled", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Remote Desktop"
        prefs = Prefs(this)
        ConnectionManager.init(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String) = root.addView(TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 4) })

        label("Server URL (ws:// or wss://)")
        serverBox = EditText(this).apply {
            setText(prefs.serverUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(serverBox)

        label("Session key (same long string on every device — set once)")
        keyBox = EditText(this).apply {
            setText(prefs.sessionKey)
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(keyBox)
        root.addView(Button(this).apply {
            text = "Generate strong key"
            setOnClickListener {
                val bytes = ByteArray(24)
                SecureRandom().nextBytes(bytes)
                keyBox.setText(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                )
            }
        })

        label("Device name")
        nameBox = EditText(this).apply { setText(prefs.deviceName) }
        root.addView(nameBox)

        root.addView(Button(this).apply {
            text = "Save & connect"
            setOnClickListener { saveAndConnect() }
        })

        statusText = TextView(this).apply { text = "Not connected"; setPadding(0, pad / 2, 0, 0) }
        root.addView(statusText)
        peersText = TextView(this).apply { setPadding(0, 4, 0, pad / 2) }
        root.addView(peersText)

        viewDesktopBtn = Button(this).apply {
            text = "View desktop"
            isEnabled = false
            setOnClickListener { startActivity(Intent(this@MainActivity, ViewerActivity::class.java)) }
        }
        root.addView(viewDesktopBtn)

        filesBtn = Button(this).apply {
            text = "Browse desktop files"
            isEnabled = false
            setOnClickListener { startActivity(Intent(this@MainActivity, FileExplorerActivity::class.java)) }
        }
        root.addView(filesBtn)

        label("Let the desktop control this phone:")
        shareBtn = Button(this).apply {
            text = "Enable screen sharing"
            setOnClickListener {
                if (ScreenCaptureService.isRunning) {
                    ScreenCaptureService.stop(this@MainActivity)
                    refreshStatus()
                } else {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }
        }
        root.addView(shareBtn)

        root.addView(Button(this).apply {
            text = "Enable tap control (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = "Grant file access (for desktop file browsing)"
            setOnClickListener {
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
        })

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        if (prefs.autoConnect && prefs.sessionKey.length >= 16) {
            ConnectionManager.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.stateListeners.add(stateListener)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.stateListeners.remove(stateListener)
    }

    private fun saveAndConnect() {
        val key = keyBox.text.toString().trim()
        if (key.length < 16) {
            Toast.makeText(this, "Session key must be at least 16 characters", Toast.LENGTH_LONG).show()
            return
        }
        val url = serverBox.text.toString().trim()
        Log.d("MainActivity", "Saving settings: url=$url, keyLen=${key.length}")
        prefs.serverUrl = url
        prefs.sessionKey = key
        prefs.deviceName = nameBox.text.toString().trim().ifBlank { Build.MODEL }
        ConnectionManager.stop()
        ConnectionManager.start(this)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        statusText.text = "Status: ${ConnectionManager.state}"
        val peers = ConnectionManager.peers
        peersText.text =
            if (peers.isEmpty()) "No paired devices online"
            else "Paired: " + peers.joinToString { "${it.name} (${it.device})" }
        val hasWindows = peers.any { it.device == "windows" }
        viewDesktopBtn.isEnabled = hasWindows
        filesBtn.isEnabled = hasWindows
        shareBtn.text = if (ScreenCaptureService.isRunning) "Disable screen sharing" else "Enable screen sharing"
        val a11y = if (InputAccessibilityService.instance != null) " · tap control ON" else ""
        val mgd = if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) " · file access OK" else ""
        statusText.text = "Status: ${ConnectionManager.state}$a11y$mgd"
    }
}
