package co.joypilot.remotedesktop

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
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
    private lateinit var a11yBtn: Button
    private lateinit var filesPermBtn: Button
    private lateinit var permHeader: TextView
    private lateinit var permGroup: LinearLayout

    private val grantedColor = Color.parseColor("#2E7D32")   // green: all good
    private val neededColor = Color.parseColor("#C62828")    // red: action required

    private val stateListener: (String) -> Unit = { refreshStatus() }

    private val projectionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ScreenCaptureService.start(this, result.resultCode, result.data!!)
                Toast.makeText(this, "Screen sharing enabled", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { applyScannedSettings(it) }
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

        val keyButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyButtons.addView(Button(this).apply {
            text = "Generate strong key"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener {
                val bytes = ByteArray(24)
                SecureRandom().nextBytes(bytes)
                keyBox.setText(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                )
            }
        })
        keyButtons.addView(Button(this).apply {
            text = "Scan QR"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener {
                qrScanner.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Scan the QR code shown by “QR code” in the desktop app")
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                })
            }
        })
        root.addView(keyButtons)

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

        // ---- permissions: collapsible section, green = granted, red = needed ----
        permHeader = TextView(this).apply {
            textSize = 16f
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setOnClickListener {
                permGroup.visibility =
                    if (permGroup.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                refreshStatus()
            }
        }
        root.addView(permHeader)

        permGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setBackgroundColor(Color.parseColor("#14000000"))
            visibility = View.GONE // collapsed by default; the heading shows the state
        }

        shareBtn = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
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
        permGroup.addView(shareBtn)

        a11yBtn = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        permGroup.addView(a11yBtn)

        filesPermBtn = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
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
        }
        permGroup.addView(filesPermBtn)
        root.addView(permGroup)

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })

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
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.stateListeners.remove(stateListener)
    }

    /** QR payload from the desktop app: {"v":1,"server":"wss://…","key":"…"} */
    private fun applyScannedSettings(contents: String) {
        try {
            val obj = JSONObject(contents)
            val server = obj.optString("server")
            val key = obj.optString("key")
            if (key.length < 16) throw Exception("key too short")
            if (server.isNotBlank()) serverBox.setText(server)
            keyBox.setText(key)
            Toast.makeText(this, "Session settings scanned", Toast.LENGTH_SHORT).show()
            saveAndConnect()
        } catch (e: Exception) {
            // Not our JSON payload — accept a bare key string as a fallback.
            if (contents.trim().length >= 16 && !contents.contains('\n')) {
                keyBox.setText(contents.trim())
                Toast.makeText(this, "Session key scanned", Toast.LENGTH_SHORT).show()
                saveAndConnect()
            } else {
                Toast.makeText(this, "That QR code is not a Remote Desktop pairing code", Toast.LENGTH_LONG).show()
            }
        }
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
        // Foreground service keeps the session alive so the desktop can reach
        // this phone without the app being open (ConnectionManager.start is
        // idempotent, so the service won't open a second socket).
        ConnectionService.start(this)
    }

    private fun setPermState(b: Button, granted: Boolean, grantedText: String, neededText: String) {
        b.text = if (granted) "✓  $grantedText" else "✗  $neededText"
        b.backgroundTintList = ColorStateList.valueOf(if (granted) grantedColor else neededColor)
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

        setPermState(shareBtn, ScreenCaptureService.isRunning,
            "Screen sharing enabled (tap to stop)", "Enable screen sharing")
        setPermState(a11yBtn, InputAccessibilityService.instance != null,
            "Tap control enabled", "Enable tap control (Accessibility)")
        val filesOk = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        setPermState(filesPermBtn, filesOk,
            "File access granted", "Grant file access (for desktop file browsing)")

        val grantedCount = listOf(ScreenCaptureService.isRunning,
            InputAccessibilityService.instance != null, filesOk).count { it }
        val arrow = if (permGroup.visibility == View.VISIBLE) "▾" else "▸"
        permHeader.text = "$arrow  Permissions ($grantedCount/3 enabled)"
        // Slight tint: green when everything is set up, red when action is needed.
        permHeader.setBackgroundColor(
            if (grantedCount == 3) Color.parseColor("#334CAF50") else Color.parseColor("#33F44336"))
    }
}
