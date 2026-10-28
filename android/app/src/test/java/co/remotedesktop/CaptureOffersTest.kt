package co.remotedesktop

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.Collections

/** Just enough of MediaStore's files table for CaptureOffers to read. */
class FakeMediaStore : ContentProvider() {
    private lateinit var db: SQLiteDatabase

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT, media_type INTEGER, " +
            "_data TEXT, relative_path TEXT, _size INTEGER)")
        return true
    }

    fun add(file: File, folder: String, video: Boolean): Long = db.insert("files", null, ContentValues().apply {
        put("media_type", if (video) 3 else 1)
        put("_data", file.path)
        put("relative_path", folder)
        put("_size", file.length())
    })

    override fun query(uri: Uri, projection: Array<String>?, args: Bundle?, signal: CancellationSignal?): Cursor =
        db.query("files", projection, args?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
            args?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), null, null,
            args?.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
            args?.getInt(ContentResolver.QUERY_ARG_LIMIT)?.takeIf { it > 0 }?.toString())

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?,
                       sortOrder: String?): Cursor = db.query("files", projection, selection, selectionArgs, null, null, sortOrder)

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class CaptureOffersTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private lateinit var media: FakeMediaStore

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun authField(name: String) = PeerAuth::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = authField("trustedPeers").get(PeerAuth) as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private val resultFrom get() = authField("resultFrom").get(PeerAuth) as MutableMap<String, String>

    private fun setAllPeersTrusted(trusted: Boolean) = authField("allPeersTrusted").setBoolean(PeerAuth, trusted)

    private fun setState(state: String) = connectionField("state").set(ConnectionManager, state)

    /** Online, and approved both ways. */
    private fun pair(id: String, device: String) {
        ConnectionManager.peers.add(ConnectionManager.Peer(id, device, id))
        trustedPeers.add(id)
        resultFrom[id] = "trusted"
    }

    @Before fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        media = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
        for (name in listOf("encKey", "ws", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        setState("connected")
        pair("pc", "windows")
    }

    @After fun cleanup() {
        CaptureOffers.stop()
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        resultFrom.clear()
        setAllPeersTrusted(true)
    }

    /** Saves a file where a camera or the system would, and tells MediaStore's observers. */
    private fun save(folder: String, name: String, video: Boolean = false, writtenAgoMs: Long = 0): File {
        val file = File(Environment.getExternalStorageDirectory(), folder + name)
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(42))
        file.setLastModified(System.currentTimeMillis() - writtenAgoMs)
        val id = media.add(file, folder, video)
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.notifyChange(ContentUris.withAppendedId(collection, id), null)
        return file
    }

    /** Lets a change settle and be looked at. */
    private fun settle() = shadowOf(CaptureOffers.looper!!).idleFor(Duration.ofSeconds(5))

    private fun offers() = synchronized(socket.messages) { socket.messages.filter { it.optString("type") == "fs-offer" } }

    private fun offeredPaths(offer: JSONObject) =
        offer.getJSONArray("files").let { files -> (0 until files.length()).map { files.getJSONObject(it).getString("path") } }

    @Test fun capturesAreTheirKindByFolderAndEverythingElseIsNot() {
        assertEquals("photo", CaptureOffers.kindOf("DCIM/Camera/", "20261027_101010.jpg", video = false))
        assertEquals("video", CaptureOffers.kindOf("DCIM/Camera/", "20261027_101010.mp4", video = true))
        assertEquals("photo", CaptureOffers.kindOf("DCIM/100ANDRO/", "DSC_0001.JPG", video = false))
        assertEquals("screenshot", CaptureOffers.kindOf("DCIM/Screenshots/", "Screenshot_20261027_101010_Chrome.jpg", video = false))
        assertEquals("screenshot", CaptureOffers.kindOf("Pictures/Screenshots/", "Screenshot_20261027-101010.png", video = false))
        assertEquals("screen-recording", CaptureOffers.kindOf("DCIM/Screen recordings/", "Screen_Recording_20261027.mp4", video = true))
        assertEquals("screen-recording", CaptureOffers.kindOf("DCIM/ScreenRecorder/", "Screenrecorder-2026.mp4", video = true))
        assertEquals("screen-recording", CaptureOffers.kindOf("Movies/", "screen-20261027-101010.mp4", video = true))
        assertNull(CaptureOffers.kindOf("Movies/", "holiday.mp4", video = true))
        assertNull(CaptureOffers.kindOf("Download/", "cat.jpg", video = false))
        assertNull(CaptureOffers.kindOf("Download/Screenshots/", "cat.jpg", video = false))
        assertNull(CaptureOffers.kindOf("Pictures/Messenger/", "received.jpg", video = false))
        assertNull(CaptureOffers.kindOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "IMG-1.jpg", video = false))
        // Seen on a real phone: archives kept further down in DCIM.
        assertNull(CaptureOffers.kindOf("DCIM/Copied to computer/Camera 2026-01-14/Camera/", "a.jpg", video = false))
        assertNull(CaptureOffers.kindOf("DCIM/.thumbnails/", "123.jpg", video = false))
        assertNull(CaptureOffers.kindOf("", "loose.jpg", video = false))
    }

    @Test fun aNewScreenshotIsOfferedToTheApprovedPcOnce() {
        save("DCIM/Camera/", "before.jpg")
        CaptureOffers.start(context)
        settle()
        val shot = save("DCIM/Screenshots/", "Screenshot_1.jpg")
        save("Download/", "cat.jpg")
        settle()
        val offer = offers().single()
        assertEquals("pc", offer.getString("to"))
        val file = offer.getJSONArray("files").getJSONObject(0)
        assertEquals(shot.path, file.getString("path"))
        assertEquals("Screenshot_1.jpg", file.getString("name"))
        assertEquals(42L, file.getLong("size"))
        assertEquals("screenshot", file.getString("kind"))

        // MediaStore reports edits and other apps' files too; none is new.
        context.contentResolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
        save("Pictures/Messenger/", "received.jpg")
        settle()
        assertEquals(1, offers().size)
    }

    @Test fun aBurstGoesOutAsOneOfferAndOldFilesAreNotNew() {
        CaptureOffers.start(context)
        settle()
        val shots = (1..3).map { save("DCIM/Camera/", "burst_$it.jpg") }
        // Moved in from elsewhere, so written long ago: not just taken.
        save("DCIM/Camera/", "restored.jpg", writtenAgoMs = 60 * 60_000L)
        val clip = save("DCIM/Camera/", "clip.mp4", video = true)
        settle()
        val offer = offers().single()
        assertEquals(shots.map { it.path } + clip.path, offeredPaths(offer))
        val kinds = offer.getJSONArray("files").let { f -> (0 until f.length()).map { f.getJSONObject(it).getString("kind") } }
        assertEquals(listOf("photo", "photo", "photo", "video"), kinds)
    }

    @Test fun offersGoOnlyWhereThePcCanDownloadThemAndNeverLater() {
        val prefs = Prefs(context)
        prefs.offerCaptures = false
        CaptureOffers.start(context)
        assertNull("Switched off, nothing watches MediaStore", CaptureOffers.looper)
        prefs.offerCaptures = true
        settle()
        prefs.allowFileAccess = false
        assertNull("A PC can't download without file access", CaptureOffers.looper)
        prefs.allowFileAccess = true
        settle()

        ConnectionManager.peers.add(ConnectionManager.Peer("stranger", "windows", "Unknown"))
        pair("phone", "android")
        setAllPeersTrusted(false)
        save("DCIM/Camera/", "while_a_stranger_is_here.jpg")
        settle()
        assertTrue("Downloads are refused while a device is unapproved", offers().isEmpty())

        setAllPeersTrusted(true)
        setState("disconnected: retrying...")
        save("DCIM/Camera/", "while_offline.jpg")
        settle()
        assertTrue(offers().isEmpty())

        setState("connected")
        val now = save("DCIM/Camera/", "now.jpg")
        settle()
        val offer = offers().single()
        assertEquals("Only the approved PC, never a phone", "pc", offer.getString("to"))
        assertEquals("What was taken while nobody could download it stays unoffered",
            listOf(now.path), offeredPaths(offer))

        CaptureOffers.stop()
        assertNull("Watching ends with the connection", CaptureOffers.looper)
    }

    /** Decrypts what the phone sends. Offers go out on the watcher's thread. */
    private class RecordingSocket : WebSocket {
        val key = ByteArray(32) { 7 }
        val messages: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        override fun request() = Request.Builder().url("https://example.test/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            val blob = Base64.decode(JSONObject(text).getString("d"), Base64.NO_WRAP)
            messages.add(JSONObject(String(Crypto.decrypt(key, blob, Crypto.CH_JSON)!!)))
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
}
