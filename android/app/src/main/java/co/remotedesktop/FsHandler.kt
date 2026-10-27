package co.remotedesktop

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

/**
 * Serves this phone's filesystem to peers (listing + transfers) and receives
 * files pushed from the desktop into the Downloads collection.
 */
class FsHandler(private val context: Context) {

    private val exec = Executors.newSingleThreadExecutor()

    val transferStatus = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    private fun status(s: String) = transferStatus.forEach { it(s) }

    // Random id in the positive int range so it round-trips through JSON and the
    // 4-byte chunk header identically on the C# side (which reads it as uint).
    fun nextXferId(): Int = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)

    private class Incoming(
        val temp: File,
        val out: FileOutputStream,
        val name: String,
        /** What fs-begin announced, or -1 when the sender didn't know. */
        val size: Long,
        val peer: String,
        val onDone: ((String) -> Unit)?,
        val onError: ((String) -> Unit)?,
    ) {
        var received = 0L
    }

    /** A download this phone asked for with fs-get, until its fs-begin arrives. */
    private class PendingGet(val peer: String?, val onDone: (String) -> Unit, val onError: (String) -> Unit)

    /** A file this phone is sending, until its fs-end. */
    private class Outgoing(val peer: String) {
        /** What fs-end tells the receiver, once the transfer has been cancelled. */
        @Volatile var cancelled: String? = null
    }

    private val incoming = ConcurrentHashMap<Int, Incoming>()
    private val pendingGets = ConcurrentHashMap<Int, PendingGet>()
    private val outgoing = ConcurrentHashMap<Int, Outgoing>()
    // Downloads given up before their fs-begin. An older sender ignores
    // fs-cancel and sends the file anyway, which must not be kept as a push.
    private val cancelledGets = ConcurrentHashMap.newKeySet<Int>()

    private val root: File get() = Environment.getExternalStorageDirectory()

    // ---------- messages ----------

    /** @param peerAccessAllowed whether the user consents to peers reading this
     *  phone's files or pushing files to it. Replies to transfers this device
     *  initiated itself (fs-get downloads) are always processed. */
    fun handleJson(msg: JSONObject, peerAccessAllowed: Boolean) {
        when (msg.optString("type")) {
            "fs-list" -> if (peerAccessAllowed) handleList(msg) else denyList(msg)
            "fs-get" -> if (peerAccessAllowed) handleGet(msg) else denyGet(msg)
            "fs-begin" -> handleBegin(msg, peerAccessAllowed)
            "fs-end" -> handleEnd(msg)
            // Only the device receiving the file can call it off.
            "fs-cancel" -> outgoing[msg.optInt("xferId")]?.takeIf { it.peer == msg.optString("from") }
                ?.cancelled = "Cancelled by the receiver"
        }
    }

    /** Whether a transfer is still waiting to begin, sending or receiving. */
    fun isActive(xferId: Int) =
        pendingGets.containsKey(xferId) || incoming.containsKey(xferId) || outgoing.containsKey(xferId)

    /**
     * Stops a transfer this phone started. A download is dropped and its sender
     * asked to stop with fs-cancel; an upload ends with a failed fs-end.
     */
    fun cancel(xferId: Int) {
        outgoing[xferId]?.let { it.cancelled = "Cancelled by the sender"; return }
        val get: PendingGet?
        val t: Incoming?
        synchronized(cancelledGets) {
            get = pendingGets.remove(xferId)
            if (get != null) cancelledGets.add(xferId)
            t = incoming.remove(xferId)
        }
        if (t != null) {
            try { t.out.close() } catch (_: IOException) {}
            t.temp.delete()
            status("Cancelled ${t.name}")
        }
        val peer = t?.peer ?: get?.peer ?: return
        ConnectionManager.sendJson(JSONObject().put("type", "fs-cancel").put("to", peer).put("xferId", xferId))
    }

    private val deniedError = "File access is disabled on the remote device"

    private fun denyList(msg: JSONObject) {
        ConnectionManager.sendJson(
            JSONObject()
                .put("type", "fs-list-result")
                .put("to", msg.optString("from"))
                .put("reqId", msg.optString("reqId"))
                .put("path", msg.optString("path"))
                .put("entries", JSONArray())
                .put("error", deniedError)
        )
    }

    private fun denyGet(msg: JSONObject) {
        ConnectionManager.sendJson(
            JSONObject().put("type", "fs-end").put("to", msg.optString("from"))
                .put("xferId", msg.optInt("xferId")).put("ok", false).put("error", deniedError)
        )
    }

    private fun resolve(path: String): File =
        if (path.isEmpty()) root else File(path)

    private fun handleList(msg: JSONObject) {
        val from = msg.optString("from")
        val reqId = msg.optString("reqId")
        val pathStr = msg.optString("path")
        val entries = JSONArray()
        var error: String? = null
        try {
            val dir = resolve(pathStr)
            val files = dir.listFiles()
                ?: throw Exception("Cannot read ${dir.path} — grant 'All files access' in the app")
            for (f in files) {
                entries.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("dir", f.isDirectory)
                        .put("size", if (f.isFile) f.length() else 0)
                        .put("mtime", f.lastModified())
                )
            }
        } catch (e: Exception) {
            error = e.message
        }
        ConnectionManager.sendJson(
            JSONObject()
                .put("type", "fs-list-result")
                .put("to", from)
                .put("reqId", reqId)
                .put("path", if (pathStr.isEmpty()) root.path else pathStr)
                .put("downloadsPath", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
                .put("entries", entries)
                .put("error", error ?: JSONObject.NULL)
        )
    }

    private fun handleGet(msg: JSONObject) {
        val from = msg.optString("from")
        val path = msg.optString("path")
        val xferId = msg.optInt("xferId")
        // Tracked before the worker reaches it, so an fs-cancel right behind the request still stops it.
        outgoing[xferId] = Outgoing(from)
        exec.execute {
            try {
                val f = File(path)
                sendFile(from, f.inputStream(), f.name, f.length(), xferId)
            } catch (e: Exception) {
                outgoing.remove(xferId)
                ConnectionManager.sendJson(
                    JSONObject().put("type", "fs-end").put("to", from)
                        .put("xferId", xferId).put("ok", false).put("error", e.message ?: "open failed")
                )
                status("Send failed: ${e.message}")
            }
        }
    }

    /** Streams any InputStream to a peer as a file transfer.
     *  @return true if the whole file was sent and fs-end ok was signalled. */
    fun sendFile(
        toPeer: String, input: InputStream, name: String, size: Long, xferId: Int,
        canContinue: () -> Boolean = { true },
        onProgress: ((String) -> Unit)? = null,
    ): Boolean {
        val transfer = outgoing.getOrPut(xferId) { Outgoing(toPeer) }
        fun checkContinue() {
            transfer.cancelled?.let { throw IOException(it) }
            if (!canContinue()) throw IOException("Transfer cancelled or device unavailable")
        }
        try {
            input.use { ins ->
                checkContinue()
                ConnectionManager.sendJson(
                    JSONObject()
                        .put("type", "fs-begin").put("to", toPeer)
                        .put("xferId", xferId).put("name", name).put("size", size)
                )
                val buf = ByteArray(256 * 1024)
                val recipient = listOf(toPeer) // only the device the file is for needs its chunks
                var sent = 0L
                while (true) {
                    checkContinue()
                    val n = ins.read(buf)
                    if (n <= 0) break
                    // Payload (before encryption): [xferId(4 BE)][chunk]. The frame-type
                    // byte (2) is prepended by ConnectionManager.sendBinary.
                    val payload = ByteArray(4 + n)
                    payload[0] = (xferId ushr 24).toByte()
                    payload[1] = (xferId ushr 16).toByte()
                    payload[2] = (xferId ushr 8).toByte()
                    payload[3] = xferId.toByte()
                    System.arraycopy(buf, 0, payload, 4, n)
                    // Back-pressure: wait while the socket queue is full.
                    var tries = 0
                    while (!ConnectionManager.sendBinary(Crypto.CH_FILE, payload, recipient)) {
                        checkContinue()
                        if (++tries >= 300) throw IOException("Connection timed out while sending")
                        Thread.sleep(100)
                    }
                    sent += n
                    val progress = if (size > 0) "Sending $name: ${sent * 100 / size}%" else "Sending $name..."
                    status(progress)
                    onProgress?.invoke(progress)
                }
                checkContinue()
                outgoing.remove(xferId)
                ConnectionManager.sendJson(
                    JSONObject().put("type", "fs-end").put("to", toPeer).put("xferId", xferId).put("ok", true)
                )
            }
            status("Sent $name")
            return true
        } catch (e: Exception) {
            outgoing.remove(xferId)
            ConnectionManager.sendJson(
                JSONObject().put("type", "fs-end").put("to", toPeer)
                    .put("xferId", xferId).put("ok", false).put("error", e.message ?: "send failed")
            )
            status(if (transfer.cancelled != null) "Cancelled $name" else "Send failed: ${e.message}")
            return false
        }
    }

    fun sendFileAsync(
        toPeer: String, input: InputStream, name: String, size: Long,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val id = nextXferId()
        exec.execute {
            val ok = sendFile(toPeer, input, name, size, id)
            onDone?.invoke(ok)
        }
    }

    /** Open only the current file, on the transfer worker, while its share grant is valid. */
    fun sendUriAsync(
        toPeer: String, uri: Uri, xferId: Int, canContinue: () -> Boolean,
        onProgress: (String) -> Unit, onDone: (Boolean, String?) -> Unit,
    ) {
        // Tracked now, so cancel() also stops a file still queued behind another.
        outgoing[xferId] = Outgoing(toPeer)
        exec.execute {
            try {
                outgoing[xferId]?.cancelled?.let { throw IOException(it) }
                if (!canContinue()) throw IOException("Transfer cancelled or device unavailable")
                var name = "shared-file"
                var size = -1L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = it.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = it.getString(ni)?.takeIf { value -> value.isNotBlank() } ?: name
                        if (si >= 0 && !it.isNull(si)) size = it.getLong(si)
                    }
                }
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open the shared file")
                onProgress("Sending $name...")
                val ok = sendFile(toPeer, input, name, size, xferId, canContinue, onProgress)
                onDone(ok, if (ok) name else "Could not send $name. Check the connection and desktop file access.")
            } catch (e: Exception) {
                outgoing.remove(xferId)
                onDone(false, "Cannot read the shared file: ${e.message ?: "access expired"}. Share it again from the source app.")
            }
        }
    }

    // ---------- receiving ----------

    /** @param from the peer asked for the file, so its leaving fails the download. */
    fun expectDownload(xferId: Int, onDone: (String) -> Unit, onError: (String) -> Unit, from: String? = null) {
        pendingGets[xferId] = PendingGet(from, onDone, onError)
    }

    /**
     * Fails transfers that can no longer finish: every one when the connection
     * has dropped ([peer] null), or one peer's when it leaves. Without this a
     * download waits forever and its temp file stays behind.
     */
    fun abort(peer: String? = null, reason: String) {
        for ((id, t) in incoming) {
            if (peer != null && t.peer != peer) continue
            if (incoming.remove(id) == null) continue
            try { t.out.close() } catch (_: IOException) {}
            t.temp.delete()
            status("Receive failed: $reason")
            t.onError?.invoke(reason)
        }
        for ((id, get) in pendingGets) {
            if (peer != null && get.peer != peer) continue
            pendingGets.remove(id)?.onError?.invoke(reason)
        }
    }

    private fun handleBegin(msg: JSONObject, peerAccessAllowed: Boolean) {
        val xferId = msg.optInt("xferId")
        val name = msg.optString("name").replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "file.bin" }
        try {
            // Together with cancel(), so a download is either cancelled or begun.
            synchronized(cancelledGets) {
                if (cancelledGets.remove(xferId)) return
                // An unsolicited push (no matching fs-get from us) needs consent;
                // without a registered transfer its chunks are dropped on arrival.
                if (!peerAccessAllowed && !pendingGets.containsKey(xferId)) return
                val temp = File.createTempFile("xfer", ".part", context.cacheDir)
                val get = pendingGets.remove(xferId)
                incoming[xferId] = Incoming(temp, FileOutputStream(temp), name, msg.optLong("size", -1L),
                    msg.optString("from"), get?.onDone, get?.onError)
            }
            status("Receiving $name...")
        } catch (e: Exception) {
            status("Receive failed: ${e.message}")
        }
    }

    fun handleFileChunk(frame: ByteArray) {
        if (frame.size < 5) return
        val id = ((frame[1].toInt() and 0xFF) shl 24) or
                ((frame[2].toInt() and 0xFF) shl 16) or
                ((frame[3].toInt() and 0xFF) shl 8) or
                (frame[4].toInt() and 0xFF)
        val t = incoming[id] ?: return
        try {
            t.out.write(frame, 5, frame.size - 5)
            t.received += frame.size - 5
        } catch (_: IOException) {
            // Aborted by a disconnect on another thread; the transfer has already failed.
        }
    }

    private fun handleEnd(msg: JSONObject) {
        val xferId = msg.optInt("xferId")
        val ok = msg.optBoolean("ok")
        cancelledGets.remove(xferId)
        val t = incoming.remove(xferId) ?: run {
            // A refused or missing file ends without fs-begin. Tell whoever asked
            // for it, or its download waits forever.
            if (!ok) pendingGets.remove(xferId)?.let { get ->
                val err = msg.optString("error", "unknown error")
                status("Receive failed: $err")
                get.onError(err)
            }
            return
        }
        t.out.close()
        if (!ok) {
            t.temp.delete()
            val err = msg.optString("error", "unknown error")
            status("Receive failed: $err")
            t.onError?.invoke(err)
            return
        }
        // The relay drops file chunks for a receiver that falls too far behind,
        // so a transfer can end "ok" with pieces missing. Never save that.
        if (t.size >= 0 && t.received != t.size) {
            t.temp.delete()
            val err = "Only ${t.received} of ${t.size} bytes arrived. Try again on a faster connection."
            ConnectionManager.sendJson(
                JSONObject().put("type", "fs-saved").put("to", msg.optString("from"))
                    .put("xferId", xferId).put("error", err)
            )
            status("Receive failed: $err")
            t.onError?.invoke(err)
            return
        }
        try {
            val (saved, uri) = saveToDownloads(t.temp, t.name)
            t.temp.delete()
            ConnectionManager.sendJson(
                JSONObject().put("type", "fs-saved").put("to", msg.optString("from"))
                    .put("xferId", xferId).put("path", saved)
            )
            status("Saved $saved")
            t.onDone?.invoke(saved)
            ReceivedFileNotification.show(context, saved, uri, xferId)
        } catch (e: Exception) {
            status("Save failed: ${e.message}")
            t.onError?.invoke(e.message ?: "save failed")
        }
    }

    /** Copies a finished temp file into the public Downloads collection.
     *  @return the saved path and a content URI other apps can be granted to open it. */
    private fun saveToDownloads(src: File, name: String): Pair<String, Uri> {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                // Downloads itself, not a subfolder of it. MediaStore renames
                // duplicates, so landing alongside existing files is safe.
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)!!.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            // Read back the actual location: MediaStore may rename duplicate files.
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Downloads.DATA), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val path = cursor.getString(0)
                    if (!path.isNullOrEmpty()) return path to uri
                }
            }
            throw Exception("Cannot resolve saved file path")
        } else {
            // The same root as received_file_paths.xml, so FileProvider below always resolves it.
            val dir = File(root, Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            var dest = File(dir, name)
            var i = 1
            while (dest.exists()) dest = File(dir, "${name.substringBeforeLast('.')} (${i++}).${name.substringAfterLast('.', "bin")}")
            src.inputStream().use { ins -> FileOutputStream(dest).use { ins.copyTo(it) } }
            return dest.path to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
        }
    }
}
