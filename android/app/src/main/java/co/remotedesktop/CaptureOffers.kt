package co.remotedesktop

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Tells approved PCs about each photo, video, screenshot or screen recording
 * this phone saves, so they can offer to download it (fs-offer in PROTOCOL.md).
 *
 * Nothing polls. MediaStore calls back when its contents change and nothing
 * runs in between; the phone looks once a change settles, so a burst of shots
 * goes out as one offer.
 */
object CaptureOffers {
    private const val TAG = "CaptureOffers"

    /** Quiet time after a change before looking: the camera may still be
     *  finishing the file, or taking the rest of a burst. */
    private const val SETTLE_MS = 1000L
    /** A steady stream of changes, like an import, still gets looked at this often. */
    private const val MAX_WAIT_MS = 4000L
    /** A file last written longer ago than this was moved, restored or synced
     *  into the camera's folder, not just taken. */
    private const val FRESH_MS = 10 * 60_000L
    /** Tolerates the clock being set back a little between write and look. */
    private const val CLOCK_SLACK_MS = 60_000L
    /** Newest rows read per look, enough for a long burst. */
    private const val LOOK_BACK = 200
    /** Ids remembered as already looked at; far more than one look reads. */
    private const val SEEN_LIMIT = 1024
    private const val THUMB_PX = 256

    private val FILES = MediaStore.Files.getContentUri("external")
    private const val ID = MediaStore.MediaColumns._ID
    private const val MEDIA_TYPE = MediaStore.Files.FileColumns.MEDIA_TYPE
    private const val MEDIA_TYPES = "$MEDIA_TYPE IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
        "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    private var appContext: Context? = null
    private var enabled = false
    private var watcher: Watcher? = null

    /** The thread changes are looked at on, while watching. */
    internal val looper: Looper? get() = watcher?.looper

    /** Watches while the connection is on, if the switches allow it. */
    @Synchronized fun start(context: Context) {
        appContext = context.applicationContext
        enabled = true
        refresh()
    }

    @Synchronized fun stop() {
        enabled = false
        refresh()
    }

    /** Starts or stops watching to match the connection and the switches. */
    @Synchronized fun refresh() {
        val context = appContext ?: return
        val prefs = Prefs(context)
        // Offers are only worth sending while the PC is allowed to download them.
        val wanted = enabled && prefs.offerCaptures && prefs.allowFileAccess
        if (wanted && watcher == null) watcher = Watcher(context)
        if (!wanted) {
            watcher?.close()
            watcher = null
        }
    }

    /**
     * What kind of capture a new image or video is, judged by the folder the
     * camera or the system saved it in, or null for anything else: downloads,
     * chat apps, edits. [folder] is relative to the storage root, like "DCIM/Camera/".
     */
    internal fun kindOf(folder: String, name: String, video: Boolean): String? {
        val parts = folder.split('/').filter { it.isNotEmpty() }
        // Thumbnail caches and trash live in hidden folders.
        if (parts.any { it.startsWith('.') }) return null
        val words = parts.map { part -> part.filter { it.isLetter() }.lowercase() }
        // The system and camera apps save at most one folder into DCIM, Pictures
        // or Movies. Anything deeper, like DCIM/Copied to computer/Camera, is
        // someone's archive.
        val top = words.firstOrNull() ?: return null
        if (words.size > 2 || (words.size == 2 && top !in setOf("dcim", "pictures", "movies"))) return null
        val last = words.last()
        return when {
            // Pictures/Screenshots on most phones, DCIM/Screenshots on Samsung's.
            last == "screenshots" -> "screenshot"
            // DCIM/Screen recordings (Samsung), DCIM/ScreenRecorder (Xiaomi), and
            // the stock recorder's Movies/screen-<date>.mp4.
            last.startsWith("screenrecord") ||
                (video && words == listOf("movies") && name.startsWith("screen-", ignoreCase = true)) ->
                "screen-recording"
            // DCIM/Camera on nearly every phone, though some name their folder differently.
            top == "dcim" -> if (video) "video" else "photo"
            else -> null
        }
    }

    private class Capture(val id: Long, val path: String, val name: String, val size: Long, val kind: String, val video: Boolean)

    private class Watcher(private val context: Context) {
        private val thread = HandlerThread("capture-offers").apply { start() }
        val looper: Looper = thread.looper
        private val handler = Handler(looper)
        @Volatile private var closed = false

        // Only used on the watcher's thread.
        /** The newest id when watching began: anything at or below it is not new. */
        private var baseline = Long.MAX_VALUE
        /** Ids already looked at, oldest first, so nothing is offered twice. */
        private val seen = LinkedHashSet<Long>()
        private var firstChangeAt = 0L
        private val look = Runnable {
            firstChangeAt = 0L
            if (!closed) offer(newCaptures())
        }

        private val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val now = SystemClock.uptimeMillis()
                if (firstChangeAt == 0L) firstChangeAt = now
                handler.removeCallbacks(look)
                handler.postDelayed(look, (firstChangeAt + MAX_WAIT_MS - now).coerceIn(0L, SETTLE_MS))
            }
        }

        init {
            // Queued first, so it runs before any change is looked at.
            handler.post { baseline = newestId() }
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        }

        fun close() {
            closed = true
            context.contentResolver.unregisterContentObserver(observer)
            thread.quitSafely()
        }

        private fun newestId(): Long =
            query(arrayOf(ID), MEDIA_TYPES, emptyArray(), 1)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

        /** Photos, videos and screenshots saved since the last look, oldest first.
         *  MediaStore leaves out files still being written until they are done. */
        @Suppress("DEPRECATION") // DATA is the path fs-get asks for.
        private fun newCaptures(): List<Capture> {
            val found = mutableListOf<Capture>()
            val columns = arrayOf(ID, MEDIA_TYPE, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE) +
                if (Build.VERSION.SDK_INT >= 29) arrayOf(MediaStore.MediaColumns.RELATIVE_PATH) else emptyArray()
            val now = System.currentTimeMillis()
            query(columns, "$MEDIA_TYPES AND $ID > ?", arrayOf(baseline.toString()), LOOK_BACK)?.use { c ->
                var rows = 0
                // Older providers ignore the limit, so stop reading at it.
                while (rows++ < LOOK_BACK && c.moveToNext()) {
                    if (!remember(c.getLong(0))) continue
                    val path = c.getString(2) ?: continue
                    val file = File(path)
                    val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val folder = (if (columns.size > 4) c.getString(4) else null) ?: folderOf(path) ?: continue
                    val kind = kindOf(folder, file.name, video) ?: continue
                    if (now - file.lastModified() !in -CLOCK_SLACK_MS..FRESH_MS) continue
                    val size = c.getLong(3).takeIf { it > 0 } ?: file.length()
                    found += Capture(c.getLong(0), path, file.name, size, kind, video)
                }
            }
            return found.asReversed()
        }

        /** @return false if this id was looked at already. */
        private fun remember(id: Long): Boolean {
            if (!seen.add(id)) return false
            if (seen.size > SEEN_LIMIT) seen.remove(seen.first())
            return true
        }

        /** Sent only where the PC could download it straight away, which is why
         *  a capture taken while no PC was online is never offered later. */
        private fun offer(captures: List<Capture>) {
            if (captures.isEmpty()) return
            val prefs = Prefs(context)
            if (closed || ConnectionManager.state != "connected" || !prefs.offerCaptures ||
                !prefs.allowFileAccess || !PeerAuth.allPeersTrusted) return
            val pcs = ConnectionManager.peers.filter {
                it.device == "windows" && PeerAuth.isTrusted(it.id) && PeerAuth.trustedBy(it.id)
            }
            if (pcs.isEmpty()) return
            val files = JSONArray()
            for (c in captures) {
                files.put(JSONObject().put("path", c.path).put("name", c.name).put("size", c.size).put("kind", c.kind))
            }
            val thumb = thumbnail(captures.last())
            for (pc in pcs) {
                val msg = JSONObject().put("type", "fs-offer").put("to", pc.id).put("files", files)
                if (thumb != null) msg.put("thumb", thumb)
                ConnectionManager.sendJson(msg)
            }
        }

        /** A small JPEG of the newest file, base64; null where Android can't make one. */
        private fun thumbnail(capture: Capture): String? {
            if (Build.VERSION.SDK_INT < 29) return null
            return try {
                val collection = if (capture.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                var bitmap = context.contentResolver.loadThumbnail(
                    ContentUris.withAppendedId(collection, capture.id), Size(THUMB_PX, THUMB_PX), null)
                // Providers may hand back more than was asked for.
                val scale = THUMB_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
                if (scale < 1f) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                        (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
                    bitmap.recycle()
                    bitmap = scaled
                }
                val jpeg = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpeg)
                bitmap.recycle()
                Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.w(TAG, "No preview for ${capture.name}", e)
                null // The offer goes without one.
            }
        }

        private fun query(columns: Array<String>, selection: String, args: Array<String>, limit: Int): Cursor? = try {
            context.contentResolver.query(FILES, columns, Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$ID DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }, null)
        } catch (e: Exception) {
            // No storage access yet, or MediaStore is still starting up.
            Log.w(TAG, "Couldn't read MediaStore", e)
            null
        }

        /** The folder relative to the storage root, from the path, before
         *  Android 10 gave MediaStore a column for it. */
        private fun folderOf(path: String): String? {
            val dir = File(path).parentFile?.relativeToOrNull(Environment.getExternalStorageDirectory()) ?: return null
            return dir.invariantSeparatorsPath.takeUnless { it.startsWith("..") }?.plus("/")
        }
    }
}
