package co.remotedesktop

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ShareFilesActivity : AppCompatActivity() {
    private lateinit var model: ShareFilesModel
    private lateinit var countText: TextView
    private lateinit var availabilityText: TextView
    private lateinit var statusText: TextView
    private lateinit var desktopsGroup: RadioGroup
    private lateinit var progress: ProgressBar
    private lateinit var sendButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private var renderedDesktops: List<ShareDesktop>? = null
    private var renderedSelection: String? = null
    private var renderedBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Text on its own goes to the PC's clipboard, the same as "Copy to PC".
        if (savedInstanceState == null) SharedFiles.textFromIntent(intent)?.let { text ->
            // Refused here: passing it on would put the whole of it through Binder again.
            if (PcClipboard.fit(text, null) == null) Toast.makeText(this,
                getString(R.string.clip_too_large, text.length, PcClipboard.MAX_CHARS), Toast.LENGTH_LONG).show()
            else startActivity(Intent(this, CopyToPcActivity::class.java).putExtra(Intent.EXTRA_PROCESS_TEXT, text))
            finish()
            return
        }
        title = "Send to desktop"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ConnectionManager.init(this)
        model = ViewModelProvider(this)[ShareFilesModel::class.java]

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun textView() = TextView(this).apply {
            setPadding(0, pad / 2, 0, pad / 2)
            root.addView(this)
        }
        countText = textView().apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        }
        textView().text = "Choose the receiving computer. Files are saved in its Downloads folder."
        availabilityText = textView()
        desktopsGroup = RadioGroup(this).also(root::addView)
        progress = ProgressBar(this).apply {
            contentDescription = "File transfer in progress"
            root.addView(this)
        }
        statusText = textView().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        sendButton = MaterialButton(this).apply {
            text = "Send"
            setOnClickListener { model.send() }
            root.addView(this)
        }
        connectButton = MaterialButton(this).apply {
            setOnClickListener {
                if (model.configured) model.connect()
                else startActivity(Intent(this@ShareFilesActivity, SessionIdActivity::class.java))
            }
            root.addView(this)
        }
        root.addView(MaterialButton(this).apply {
            text = "Review devices"
            setOnClickListener { startActivity(Intent(this@ShareFilesActivity, DevicesActivity::class.java)) }
        })
        cancelButton = MaterialButton(this).apply {
            setOnClickListener { closeOrCancel() }
            root.addView(this)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        Ui.applySystemBarInsets(root)
        model.changes.observe(this) { render() }
        @Suppress("DEPRECATION")
        val files = savedInstanceState?.getParcelableArrayList<android.net.Uri>("remainingFiles")
            ?: SharedFiles.fromIntent(intent)
        model.initialize(files, savedInstanceState?.getBoolean("transferWasActive") == true,
            savedInstanceState?.getInt("savedCount") ?: 0, savedInstanceState?.getString("transferStatus"))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeOrCancel()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("transferWasActive", model.busy)
        outState.putParcelableArrayList("remainingFiles", ArrayList(model.remaining))
        outState.putInt("savedCount", model.savedCount)
        outState.putString("transferStatus", model.status)
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean { closeOrCancel(); return true }

    private fun closeOrCancel() {
        if (!model.busy) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Cancel this transfer?")
            .setMessage("Files already saved stay on the desktop. The remaining files will stop sending.")
            .setPositiveButton("Cancel transfer") { _, _ -> model.cancel() }
            .setNegativeButton("Keep sending", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        countText.text = "${model.remaining.size} file${if (model.remaining.size == 1) "" else "s"} to send" +
            if (model.savedCount > 0) " · ${model.savedCount} saved" else ""
        availabilityText.text = model.availability
        statusText.text = model.status
        progress.visibility = if (model.busy) View.VISIBLE else View.GONE
        sendButton.isEnabled = model.canSend
        sendButton.text = if (model.savedCount > 0) "Send remaining files" else "Send"
        connectButton.text = if (model.configured) "Connect / refresh" else "Set up session"
        connectButton.isEnabled = !model.busy
        cancelButton.text = if (model.busy) "Cancel transfer" else "Close"

        if (renderedDesktops != model.desktops || renderedSelection != model.selectedId || renderedBusy != model.busy) {
            desktopsGroup.removeAllViews()
            for (desktop in model.desktops) {
                desktopsGroup.addView(RadioButton(this).apply {
                    id = View.generateViewId()
                    text = desktop.name + (desktop.code?.let { " · $it" } ?: "") +
                        if (desktop.verified) "" else " · Awaiting approval"
                    isEnabled = desktop.verified && !model.busy
                    isChecked = desktop.id == model.selectedId
                    setOnClickListener { model.select(desktop.id) }
                })
            }
            renderedDesktops = model.desktops
            renderedSelection = model.selectedId
            renderedBusy = model.busy
        }
    }
}
