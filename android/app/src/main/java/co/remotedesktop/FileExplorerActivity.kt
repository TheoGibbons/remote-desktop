package co.remotedesktop

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/**
 * Browses the paired desktop's filesystem. Tap a folder to open it, tap a file
 * to download it into the phone's Downloads folder, or use Upload
 * to push a file from the phone to the desktop.
 */
class FileExplorerActivity : AppCompatActivity() {

    private data class Entry(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

    private lateinit var pathText: TextView
    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private var winId: String? = null
    private var path = ""
    private var reqCounter = 0
    private var lastReqId = ""
    private val entries = ArrayList<Entry>()
    private lateinit var adapter: ArrayAdapter<String>

    private val jsonListener: (JSONObject) -> Unit = { msg ->
        when (msg.optString("type")) {
            "fs-list-result" -> if (msg.optString("reqId") == lastReqId) showResult(msg)
            "peer-left" -> if (msg.optString("id") == winId) { finish() }
        }
    }

    private val transferListener: (String) -> Unit = { s -> runOnUiThread { setStatus(s) } }

    // Browsing a saved download must not trigger the upload picker's callback.
    private val savedFilePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { }

    private val uploadPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) res.data?.data?.let { uploadUri(it) }
        }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Desktop files"
        ConnectionManager.init(this)
        winId = ConnectionManager.peers.firstOrNull { it.device == "windows" }?.id
        if (winId == null) {
            Toast.makeText(this, "No desktop is online in this session", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply { text = "⬆ Up"; isAllCaps = false; setOnClickListener { goUp() } })
        pathText = TextView(this).apply {
            text = "/"
            setPadding(pad, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(pathText)
        header.addView(Button(this).apply { text = "⟳"; setOnClickListener { requestList(path) } })
        root.addView(header)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView = ListView(this).apply {
            this.adapter = this@FileExplorerActivity.adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, pos, _ -> onEntryTap(pos) }
        }
        root.addView(listView)

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(Button(this).apply {
            text = "⬆ Upload file to desktop"
            isAllCaps = false
            setOnClickListener {
                uploadPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                })
            }
        })
        root.addView(footer)

        statusText = TextView(this).apply { text = "Tap a file to download it here." }
        root.addView(statusText)

        setContentView(root)
        requestList("")
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.jsonListeners.add(jsonListener)
        ConnectionManager.fs.transferStatus.add(transferListener)
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.jsonListeners.remove(jsonListener)
        ConnectionManager.fs.transferStatus.remove(transferListener)
    }

    private fun requestList(p: String) {
        setStatus("Loading...")
        lastReqId = (++reqCounter).toString()
        ConnectionManager.sendJson(
            JSONObject().put("type", "fs-list").put("to", winId)
                .put("path", p).put("reqId", lastReqId)
        )
    }

    @SuppressLint("SetTextI18n")
    private fun showResult(msg: JSONObject) {
        val err = msg.opt("error")
        if (err != null && err != JSONObject.NULL) {
            setStatus("Error: $err")
            return
        }
        path = msg.optString("path")
        pathText.text = if (path.isEmpty()) "This PC" else path
        entries.clear()
        val arr = msg.optJSONArray("entries")
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            entries.add(Entry(e.getString("name"), e.optBoolean("dir"), e.optLong("size"), e.optLong("mtime")))
        }
        entries.sortWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() })
        adapter.clear()
        adapter.addAll(entries.map { (if (it.dir) "📁 " else "📄 ") + it.name + if (!it.dir) "   (${fmtSize(it.size)})" else "" })
        adapter.notifyDataSetChanged()
        setStatus("${entries.size} items")
    }

    private fun onEntryTap(pos: Int) {
        val e = entries.getOrNull(pos) ?: return
        if (e.dir) {
            requestList(join(path, e.name))
        } else {
            download(join(path, e.name), e.name)
        }
    }

    private fun download(fullPath: String, name: String) {
        val xferId = ConnectionManager.fs.nextXferId()
        ConnectionManager.fs.expectDownload(xferId,
            { saved -> runOnUiThread { setStatus("Saved to $saved", saved) } },
            { errMsg -> runOnUiThread { setStatus("Failed: $errMsg") } })
        ConnectionManager.sendJson(
            JSONObject().put("type", "fs-get").put("to", winId).put("path", fullPath).put("xferId", xferId)
        )
        setStatus("Downloading $name...")
    }

    private fun setStatus(text: String, savedPath: String? = null) {
        statusText.text = text
        statusText.setOnClickListener(if (savedPath != null) View.OnClickListener {
            openSavedDirectory(savedPath)
        } else null)
        statusText.isClickable = savedPath != null
        statusText.isFocusable = savedPath != null
        statusText.tooltipText = if (savedPath != null) "Open containing folder" else null
    }

    @Suppress("DEPRECATION")
    private fun openSavedDirectory(savedPath: String) {
        val directory = File(savedPath).parentFile ?: return
        val relativePath = directory.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath
        val directoryUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:$relativePath"
        )
        try {
            savedFilePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, directoryUri)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker is available", Toast.LENGTH_LONG).show()
        }
    }

    private fun uploadUri(uri: Uri) {
        var name = "upload.bin"
        var size = -1L
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = it.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = it.getString(ni)
                if (si >= 0 && !it.isNull(si)) size = it.getLong(si)
            }
        }
        val input = contentResolver.openInputStream(uri)
        if (input == null) {
            setStatus("Cannot open selected file"); return
        }
        ConnectionManager.fs.sendFileAsync(winId!!, input, name, size) { ok ->
            if (ok) runOnUiThread {
                Toast.makeText(
                    this,
                    "“$name” uploaded to the desktop's Downloads folder " +
                        "(not the folder you are browsing)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        setStatus("Uploading $name...")
    }

    private fun goUp() {
        if (path.isEmpty()) return
        val trimmed = path.trimEnd('\\', '/')
        // Windows paths use backslashes; a drive root like "C:\" goes to roots.
        val idx = maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'))
        if (idx <= 0 || trimmed.endsWith(":")) { requestList(""); return }
        val parent = trimmed.substring(0, idx)
        requestList(if (parent.endsWith(":")) "$parent\\" else parent)
    }

    private fun join(dir: String, name: String): String {
        if (dir.isEmpty()) return name
        val sep = if (dir.contains('\\') || dir.endsWith(":")) "\\" else "/"
        return if (dir.endsWith("\\") || dir.endsWith("/")) dir + name else dir + sep + name
    }

    private fun fmtSize(s: Long): String = when {
        s < 1024 -> "$s B"
        s < 1024 * 1024 -> "%.1f KB".format(s / 1024.0)
        s < 1024L * 1024 * 1024 -> "%.1f MB".format(s / 1024.0 / 1024)
        else -> "%.2f GB".format(s / 1024.0 / 1024 / 1024)
    }
}
