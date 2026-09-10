package co.remotedesktop

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
        val onDone: ((String) -> Unit)?,
        val onError: ((String) -> Unit)?,
    )

    private val incoming = ConcurrentHashMap<Int, Incoming>()
    private val pendingGets = ConcurrentHashMap<Int, Pair<(String) -> Unit, (String) -> Unit>>()

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
        }
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
                .put("entries", entries)
                .put("error", error ?: JSONObject.NULL)
        )
    }

    private fun handleGet(msg: JSONObject) {
        val from = msg.optString("from")
        val path = msg.optString("path")
        val xferId = msg.optInt("xferId")
        exec.execute {
            try {
                val f = File(path)
                sendFile(from, f.inputStream(), f.name, f.length(), xferId)
            } catch (e: Exception) {
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
    fun sendFile(toPeer: String, input: InputStream, name: String, size: Long, xferId: Int): Boolean {
        try {
            ConnectionManager.sendJson(
                JSONObject()
                    .put("type", "fs-begin").put("to", toPeer)
                    .put("xferId", xferId).put("name", name).put("size", size)
            )
            val buf = ByteArray(256 * 1024)
            var sent = 0L
            input.use { ins ->
                while (true) {
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
                    while (!ConnectionManager.sendBinary(Crypto.CH_FILE, payload) && tries++ < 300) Thread.sleep(100)
                    sent += n
                    if (size > 0) status("Sending $name: ${sent * 100 / size}%")
                }
            }
            ConnectionManager.sendJson(
                JSONObject().put("type", "fs-end").put("to", toPeer).put("xferId", xferId).put("ok", true)
            )
            status("Sent $name")
            return true
        } catch (e: Exception) {
            ConnectionManager.sendJson(
                JSONObject().put("type", "fs-end").put("to", toPeer)
                    .put("xferId", xferId).put("ok", false).put("error", e.message ?: "send failed")
            )
            status("Send failed: ${e.message}")
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

    // ---------- receiving ----------

    fun expectDownload(xferId: Int, onDone: (String) -> Unit, onError: (String) -> Unit) {
        pendingGets[xferId] = Pair(onDone, onError)
    }

    private fun handleBegin(msg: JSONObject, peerAccessAllowed: Boolean) {
        val xferId = msg.optInt("xferId")
        val name = msg.optString("name").replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "file.bin" }
        // An unsolicited push (no matching fs-get from us) needs consent;
        // without a registered transfer its chunks are dropped on arrival.
        if (!peerAccessAllowed && !pendingGets.containsKey(xferId)) return
        try {
            val temp = File.createTempFile("xfer", ".part", context.cacheDir)
            val cbs = pendingGets.remove(xferId)
            incoming[xferId] = Incoming(temp, FileOutputStream(temp), name, cbs?.first, cbs?.second)
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
        t.out.write(frame, 5, frame.size - 5)
    }

    private fun handleEnd(msg: JSONObject) {
        val xferId = msg.optInt("xferId")
        val ok = msg.optBoolean("ok")
        val t = incoming.remove(xferId) ?: return
        t.out.close()
        if (!ok) {
            t.temp.delete()
            val err = msg.optString("error", "unknown error")
            status("Receive failed: $err")
            t.onError?.invoke(err)
            return
        }
        try {
            val saved = saveToDownloads(t.temp, t.name)
            t.temp.delete()
            ConnectionManager.sendJson(
                JSONObject().put("type", "fs-saved").put("to", msg.optString("from"))
                    .put("xferId", xferId).put("path", saved)
            )
            status("Saved $saved")
            t.onDone?.invoke(saved)
        } catch (e: Exception) {
            status("Save failed: ${e.message}")
            t.onError?.invoke(e.message ?: "save failed")
        }
    }

    /** Copies a finished temp file into the public Downloads collection. */
    private fun saveToDownloads(src: File, name: String): String {
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
                    if (!path.isNullOrEmpty()) return path
                }
            }
            throw Exception("Cannot resolve saved file path")
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            var dest = File(dir, name)
            var i = 1
            while (dest.exists()) dest = File(dir, "${name.substringBeforeLast('.')} (${i++}).${name.substringAfterLast('.', "bin")}")
            src.inputStream().use { ins -> FileOutputStream(dest).use { ins.copyTo(it) } }
            return dest.path
        }
    }
}
