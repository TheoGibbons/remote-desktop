package co.remotedesktop

import android.annotation.SuppressLint
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.MenuCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Browses the paired desktop's filesystem, starting in its Downloads folder.
 * Tap a folder to open it, tap a file to download it into the phone's
 * Downloads folder, or use Upload to push files from the phone to the
 * desktop's Downloads folder, which the browser then shows.
 */
class FileExplorerActivity : AppCompatActivity() {

    private lateinit var pathText: TextView
    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private lateinit var uploadButton: Button
    private lateinit var upButton: ImageButton
    private lateinit var sortButton: MaterialButton
    private lateinit var loading: ProgressBar
    private lateinit var emptyText: TextView
    private var sort = DesktopFileSort.MODIFIED_DESC
    private var foldersFirst = false
    private var listing = false
    @Volatile private var fileAccessAllowed = false
    private var pendingUploadUris: List<Uri> = emptyList()
    private var winId: String? = null
    private var path = ""
    private var requestedPath = ""
    // Where uploads land on the desktop. Older desktops don't advertise it.
    private var downloadsPath: String? = null
    // Go to Downloads once the desktop's next listing says where it is.
    private var openDownloads = true
    private var lastReqId = ""
    private var pendingScroll: Bundle? = null
    private val entries = ArrayList<DesktopFileEntry>()
    private lateinit var adapter: FileAdapter

    private val jsonListener: (JSONObject) -> Unit = { msg ->
        when (msg.optString("type")) {
            "file-access" -> if (msg.optString("from") == winId) {
                fileAccessAllowed = msg.optBoolean("allowed", false)
                uploadButton.isEnabled = fileAccessAllowed
                val uris = pendingUploadUris
                pendingUploadUris = emptyList()
                if (uris.isNotEmpty() && fileAccessAllowed) showDownloads()
                uris.forEach { uploadUri(it) }
            }
            "fs-list-result" -> if (msg.optString("reqId") == lastReqId) showResult(msg)
            // Show each uploaded file once the desktop has saved it.
            "fs-saved" -> if (msg.optString("from") == winId && isDownloads(shownPath)) requestList(shownPath, quiet = true)
            "peer-left" -> if (msg.optString("id") == winId) { finish() }
        }
    }

    private val transferListener: (String) -> Unit = { s -> runOnUiThread { setStatus(s) } }

    // Browsing a saved download must not trigger the upload picker's callback.
    private val savedFilePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { }

    private val uploadPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                pendingUploadUris = uris
                ConnectionManager.sendJson(JSONObject().put("type", "get-file-access").put("to", winId))
            }
        }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.init(this)
        winId = ConnectionManager.desktop(intent.getStringExtra(ConnectionManager.EXTRA_PEER_ID))?.id
        if (winId == null) {
            Toast.makeText(this, "No desktop is online in this session", Toast.LENGTH_LONG).show()
            finish(); return
        }

        path = savedInstanceState?.getString("path") ?: ""
        openDownloads = savedInstanceState?.getBoolean("openDownloads") ?: true
        pendingScroll = savedInstanceState?.getBundle("scroll")
        val prefs = getSharedPreferences("file_browser", MODE_PRIVATE)
        sort = DesktopFileSort.entries.firstOrNull { it.name == prefs.getString("sort", null) } ?: DesktopFileSort.MODIFIED_DESC
        foldersFirst = prefs.getBoolean("folders_first", false)

        setContentView(R.layout.activity_file_explorer)
        Ui.applySystemBarInsets(this)
        findViewById<MaterialToolbar>(R.id.filesToolbar).apply {
            setNavigationOnClickListener { finish() }
            menu.add(getString(R.string.files_refresh)).apply {
                setIcon(R.drawable.ic_refresh)
                iconTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.rd_on_surface))
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener { requestList(path); true }
            }
        }
        pathText = findViewById<TextView>(R.id.filesPath).apply {
            setOnClickListener { showLocation() }
        }
        upButton = findViewById<ImageButton>(R.id.filesUp).apply {
            setOnClickListener { goUp() }
            tooltipText = getString(R.string.files_up)
        }
        sortButton = findViewById<MaterialButton>(R.id.filesSort).apply {
            setOnClickListener { showSortMenu() }
        }
        loading = findViewById(R.id.filesLoading)
        emptyText = findViewById(R.id.filesEmpty)
        adapter = FileAdapter()
        listView = findViewById<ListView>(R.id.filesList).apply {
            adapter = this@FileExplorerActivity.adapter
            emptyView = emptyText
            setOnItemClickListener { _, _, pos, _ -> onEntryTap(pos) }
        }
        uploadButton = findViewById<Button>(R.id.filesUpload).apply {
            tooltipText = getString(R.string.files_upload_description)
            setOnClickListener { uploadPicker.launch("*/*") }
        }
        // Leave room for the title when the screen is narrow or text is enlarged.
        if (resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale >= 1.3f) {
            (uploadButton as MaterialButton).apply {
                text = ""
                iconPadding = 0
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                minWidth = 0
                minimumWidth = 0
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, paddingTop, pad, paddingBottom)
                layoutParams = layoutParams.apply { width = pad * 4 }
            }
        }
        statusText = findViewById(R.id.filesStatus)
        updatePath()
        updateSortButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("path", if (listing) requestedPath else path)
        outState.putBoolean("openDownloads", openDownloads)
        outState.putBundle("scroll", captureScroll())
        super.onSaveInstanceState(outState)
    }

    private fun captureScroll(): Bundle? {
        // Keep the anchor through another rotation while the folder reply is pending.
        pendingScroll?.let { return it }
        if (!::listView.isInitialized || (listing && requestedPath != path)) return null
        val position = listView.firstVisiblePosition
        val entry = entries.getOrNull(position) ?: return null
        val firstRow = listView.getChildAt(0) ?: return null
        return Bundle().apply {
            putString("path", path)
            putString("name", entry.name)
            putInt("position", position)
            putInt("offset", firstRow.top - listView.paddingTop)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::uploadButton.isInitialized) return
        ConnectionManager.jsonListeners.add(jsonListener)
        fileAccessAllowed = false
        uploadButton.isEnabled = false
        ConnectionManager.sendJson(JSONObject().put("type", "get-file-access").put("to", winId))
        ConnectionManager.fs.transferStatus.add(transferListener)
        if (lastReqId.isEmpty() || listing) requestList(if (listing) requestedPath else path)
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.jsonListeners.remove(jsonListener)
        ConnectionManager.fs.transferStatus.remove(transferListener)
    }

    /** @param quiet keeps the status line, e.g. upload progress, while the folder reloads. */
    private fun requestList(p: String, quiet: Boolean = false) {
        pendingScroll = if (p == path) captureScroll() else null
        requestedPath = p
        setListing(true)
        emptyText.setText(R.string.files_loading)
        if (!quiet) setStatus(getString(R.string.files_loading))
        // Replies from the previous activity must not match a request after rotation.
        lastReqId = UUID.randomUUID().toString()
        ConnectionManager.sendJson(
            JSONObject().put("type", "fs-list").put("to", winId)
                .put("path", p).put("reqId", lastReqId)
        )
    }

    @SuppressLint("SetTextI18n")
    private fun showResult(msg: JSONObject) {
        setListing(false)
        (msg.opt("downloadsPath") as? String)?.takeIf { it.isNotEmpty() }?.let { downloadsPath = it }
        val err = msg.opt("error")
        if (err != null && err != JSONObject.NULL) {
            if (entries.isNotEmpty()) pendingScroll = null
            emptyText.setText(R.string.files_error)
            setStatus("Error: $err")
            return
        }
        if (openDownloads) {
            openDownloads = false
            // Skip drawing the drives the desktop listed on the way there.
            downloadsPath?.let { if (!isDownloads(msg.optString("path"))) { requestList(it); return } }
        }
        path = msg.optString("path")
        val scroll = pendingScroll?.takeIf { it.getString("path") == path }
        pendingScroll = null
        updatePath()
        entries.clear()
        val arr = msg.optJSONArray("entries")
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            entries.add(DesktopFileEntry(e.getString("name"), e.optBoolean("dir"), e.optLong("size"), e.optLong("mtime")))
        }
        applySort(scroll)
        emptyText.setText(R.string.files_empty)
        // Leave transfer progress that replaced "Loading" while the folder loaded.
        if (statusText.text.toString() == getString(R.string.files_loading)) setStatus(getString(R.string.files_list_hint,
            resources.getQuantityString(R.plurals.files_count, entries.size, entries.size)))
    }

    /** The folder on screen, or the one replacing it. */
    private val shownPath get() = if (listing) requestedPath else path

    // Windows paths ignore case.
    private fun isDownloads(p: String) = downloadsPath?.equals(p, ignoreCase = true) == true

    private fun showDownloads() {
        val downloads = downloadsPath
        if (downloads == null) openDownloads = true
        else if (!isDownloads(shownPath)) requestList(downloads)
    }

    private fun setListing(active: Boolean) {
        listing = active
        loading.visibility = if (active) View.VISIBLE else View.INVISIBLE
        listView.isEnabled = !active
        listView.alpha = if (active) 0.5f else 1f
        upButton.isEnabled = !active && path.isNotEmpty()
        upButton.alpha = if (upButton.isEnabled) 1f else 0.38f
    }

    private fun updatePath() {
        pathText.text = path.ifEmpty { getString(R.string.files_this_pc) }
        pathText.tooltipText = pathText.text
        upButton.isEnabled = !listing && path.isNotEmpty()
        upButton.alpha = if (upButton.isEnabled) 1f else 0.38f
    }

    private fun showLocation() {
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_RemoteDesktop_FileDialog)
            .setTitle(R.string.files_detail_location)
            .setView(detailContent(listOf(getString(R.string.files_detail_location) to pathText.text.toString())))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun updateSortButton() {
        sortButton.setText(sort.summary)
        sortButton.contentDescription = getString(if (foldersFirst) R.string.files_sort_description_folders_first
            else R.string.files_sort_description, getString(sort.label))
        sortButton.tooltipText = sortButton.contentDescription
    }

    private fun showSortMenu() {
        PopupMenu(this, sortButton, Gravity.END).apply {
            DesktopFileSort.entries.forEachIndexed { index, option ->
                menu.add(0, index, index, option.label).isChecked = option == sort
            }
            menu.setGroupCheckable(0, true, true)
            val foldersFirstId = DesktopFileSort.entries.size
            menu.add(1, foldersFirstId, foldersFirstId, R.string.files_sort_folders_first).apply {
                isCheckable = true
                isChecked = foldersFirst
            }
            MenuCompat.setGroupDividerEnabled(menu, true)
            setOnMenuItemClickListener { item ->
                if (item.itemId == foldersFirstId) foldersFirst = !foldersFirst
                else sort = DesktopFileSort.entries[item.itemId]
                pendingScroll = null
                getSharedPreferences("file_browser", MODE_PRIVATE).edit {
                    putString("sort", sort.name)
                    putBoolean("folders_first", foldersFirst)
                }
                updateSortButton()
                applySort()
                true
            }
            show()
        }
    }

    private fun applySort(scroll: Bundle? = null) {
        entries.sortWith(sort.comparator(foldersFirst))
        adapter.notifyDataSetChanged()
        if (scroll == null) {
            listView.setSelection(0)
        } else {
            // Follow the same file if the desktop added or removed earlier entries.
            val position = entries.indexOfFirst { it.name == scroll.getString("name") }
                .takeIf { it >= 0 } ?: scroll.getInt("position").coerceIn(0, maxOf(0, entries.lastIndex))
            listView.setSelectionFromTop(position, scroll.getInt("offset"))
        }
    }

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.row_desktop_file, parent, false)
            val entry = getItem(position)
            row.findViewById<ImageView>(R.id.fileIcon).setImageResource(
                if (entry.dir) R.drawable.ic_folder else R.drawable.ic_document)
            row.findViewById<TextView>(R.id.fileName).text = entry.name
            val kind = if (entry.dir) getString(R.string.files_folder) else fmtSize(entry.size)
            row.findViewById<TextView>(R.id.fileMetadata).apply {
                text = if (entry.mtime > 0) getString(R.string.files_metadata, kind, formatDate(entry.mtime)) else kind
                contentDescription = if (entry.mtime > 0) getString(R.string.files_metadata, kind,
                    getString(R.string.files_modified_metadata, formatDate(entry.mtime))) else kind
            }
            row.findViewById<ImageButton>(R.id.fileDetails).apply {
                contentDescription = getString(R.string.files_details_description, entry.name)
                tooltipText = getString(R.string.files_details)
                setOnClickListener { if (!listing) showDetails(entry) }
            }
            return row
        }
    }

    private fun formatDate(mtime: Long, includeTime: Boolean = false): String {
        if (mtime <= 0) return getString(R.string.files_unknown_date)
        val date = Date(mtime)
        return DateFormat.getDateFormat(this).format(date) +
            if (includeTime) " " + DateFormat.getTimeFormat(this).format(date) else ""
    }

    private fun showDetails(entry: DesktopFileEntry) {
        val fullPath = join(path, entry.name)
        val type = if (entry.dir) getString(R.string.files_folder) else {
            val extension = entry.name.substringAfterLast('.', "").takeIf { entry.name.lastIndexOf('.') > 0 }
            if (extension.isNullOrEmpty()) getString(R.string.files_file)
            else getString(R.string.files_file_type, extension.uppercase(Locale.getDefault()))
        }
        val size = if (entry.dir) getString(R.string.files_folder_size) else
            getString(R.string.files_exact_size, fmtSize(entry.size), NumberFormat.getIntegerInstance().format(entry.size))
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_RemoteDesktop_FileDialog)
            .setTitle(R.string.files_details)
            .setView(detailContent(listOf(
                getString(R.string.files_detail_name) to entry.name,
                getString(R.string.files_detail_type) to type,
                getString(R.string.files_detail_size) to size,
                getString(R.string.files_detail_modified) to formatDate(entry.mtime, includeTime = true),
                getString(R.string.files_detail_location) to fullPath,
            )))
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(if (entry.dir) R.string.files_open_folder else R.string.files_download) { _, _ ->
                if (entry.dir) requestList(fullPath) else download(fullPath, entry.name)
            }
            .show()
    }

    private fun detailContent(fields: List<Pair<String, String>>): View {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        fields.forEach { (label, value) ->
            content.addView(TextView(this).apply {
                text = label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(getColor(R.color.rd_on_surface_variant))
                setPadding(0, pad / 2, 0, pad / 6)
            })
            content.addView(TextView(this).apply {
                text = value
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextIsSelectable(true)
            })
        }
        return ScrollView(this).apply { addView(content) }
    }

    private fun onEntryTap(pos: Int) {
        if (listing) return
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
            { errMsg -> runOnUiThread { setStatus("Failed: $errMsg") } },
            from = winId)
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
        if (!fileAccessAllowed) {
            setStatus("File access is disabled on the desktop.")
            return
        }
        val peerId = winId ?: return
        ConnectionManager.fs.sendUriAsync(peerId, uri, ConnectionManager.fs.nextXferId(),
            { fileAccessAllowed && PeerAuth.isTrusted(peerId) && PeerAuth.allPeersTrusted &&
                ConnectionManager.peers.any { it.id == peerId } },
            { progress -> runOnUiThread { setStatus(progress) } },
            { ok, result -> runOnUiThread {
                if (ok) Toast.makeText(
                    this, "“$result” sent to the desktop's Downloads folder" +
                        if (isDownloads(shownPath)) "" else " (not the folder you are browsing)", Toast.LENGTH_LONG
                ).show()
                else setStatus(result ?: "Could not send the selected file")
            } })
        setStatus("Queued selected file for upload...")
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
