package co.remotedesktop

import android.content.Intent
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

/** The file browser opened on another phone rather than a PC. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneFileBrowsingTest {
    private lateinit var controller: ActivityController<FileExplorerActivity>
    private lateinit var activity: FileExplorerActivity
    private lateinit var executor: ExecutorService
    private val contextField = ConnectionManager::class.java.getDeclaredField("appContext").apply { isAccessible = true }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("file_browser", 0).edit().clear().commit()
        contextField.set(ConnectionManager, context)
        val fs = FsHandler(context)
        ConnectionManager::class.java.getDeclaredField("fs").apply { isAccessible = true }.set(ConnectionManager, fs)
        executor = FsHandler::class.java.getDeclaredField("exec").apply { isAccessible = true }.get(fs) as ExecutorService
        // A PC first, so only the peer id can pick the phone.
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("phone", "android", "Pixel"))
        val intent = Intent(context, FileExplorerActivity::class.java).putExtra(ConnectionManager.EXTRA_PEER_ID, "phone")
        controller = Robolectric.buildActivity(FileExplorerActivity::class.java, intent).setup().visible()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        executor.shutdownNow()
        ConnectionManager.peers.clear()
        contextField.set(ConnectionManager, null)
    }

    private fun field(name: String): Any? = FileExplorerActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun deliver(message: JSONObject) {
        @Suppress("UNCHECKED_CAST")
        (field("jsonListener") as (JSONObject) -> Unit)(message)
    }

    private val path get() = activity.findViewById<TextView>(R.id.filesPath).text.toString()
    private val up get() = activity.findViewById<ImageButton>(R.id.filesUp)
    private val status get() = activity.findViewById<TextView>(R.id.filesStatus).text.toString()

    /** The phone's answer to the browser's latest request. */
    private fun answer(path: String, vararg entries: JSONObject, downloads: String = "/storage/emulated/0/Download") =
        deliver(JSONObject().put("type", "fs-list-result").put("from", "phone").put("reqId", field("lastReqId"))
            .put("path", path).put("downloadsPath", downloads).put("entries", JSONArray(entries.toList())))

    private fun folder(name: String, label: String? = null) =
        JSONObject().put("name", name).put("dir", true).put("size", 0).put("mtime", 0).apply { label?.let { put("label", it) } }

    private fun titles(): List<String> {
        val list = activity.findViewById<ListView>(R.id.filesList)
        return (0 until list.adapter.count).map { (list.adapter.getItem(it) as DesktopFileEntry).title }
    }

    private fun requested(): String = field("requestedPath") as String

    @Test
    fun itNamesThePhoneInsteadOfADesktop() {
        assertEquals("Files on Pixel", activity.findViewById<MaterialToolbar>(R.id.filesToolbar).title.toString())
        assertEquals("Upload files to Downloads on Pixel",
            activity.findViewById<TextView>(R.id.filesUpload).contentDescription.toString())
        // Before its first answer the path shows the phone, not "This PC".
        assertEquals("Pixel", path)
    }

    @Test
    fun upStopsAtInternalStorageOnAPhoneWithOneVolume() {
        answer("/storage/emulated/0")
        assertEquals("/storage/emulated/0/Download", requested())
        answer("/storage/emulated/0/Download")
        up.performClick()
        assertEquals("/storage/emulated/0", requested())
        answer("/storage/emulated/0")
        assertTrue(up.isEnabled)
        // The phone can't list what is above, and answers with the same folder.
        up.performClick()
        assertEquals("/storage/emulated", requested())
        answer("/storage/emulated/0")
        assertEquals("/storage/emulated/0", path)
        assertFalse(up.isEnabled)

        // Survives rotation.
        controller.recreate()
        activity = controller.get()
        answer("/storage/emulated/0")
        assertFalse(up.isEnabled)
    }

    @Test
    fun aPhoneWithAnSdCardShowsItsVolumesByNameAndUpStopsThere() {
        answer("/storage/emulated/0")
        answer("/storage/emulated/0/Download")
        up.performClick()
        answer("/storage/emulated/0")
        up.performClick()
        answer("/storage", folder("emulated/0", "Internal shared storage"), folder("0000-0000", "SD card"))
        assertEquals("/storage", path)
        assertEquals(listOf("Internal shared storage", "SD card"), titles().sorted())
        assertFalse(up.isEnabled)

        // Opening a volume still goes by its path.
        val list = activity.findViewById<ListView>(R.id.filesList)
        val sdCard = titles().indexOf("SD card")
        list.performItemClick(list.getChildAt(sdCard), sdCard, sdCard.toLong())
        assertEquals("/storage/0000-0000", requested())
        answer("/storage/0000-0000", folder("DCIM"))
        assertTrue(up.isEnabled)
        up.performClick()
        assertEquals("/storage", requested())
        answer("/storage", folder("emulated/0", "Internal shared storage"), folder("0000-0000", "SD card"))
        assertFalse(up.isEnabled)
    }

    @Test
    fun aFileThePhoneCouldNotSaveIsReported() {
        answer("/storage/emulated/0")
        answer("/storage/emulated/0/Download")
        deliver(JSONObject().put("type", "fs-saved").put("from", "phone").put("xferId", 3)
            .put("error", "Only 10 of 20 bytes arrived. Try again on a faster connection."))
        assertEquals("Pixel couldn't save the file: Only 10 of 20 bytes arrived. Try again on a faster connection.", status)
        // Another device's reply is not about this upload.
        deliver(JSONObject().put("type", "fs-saved").put("from", "pc").put("xferId", 4).put("error", "Disk full"))
        assertFalse(status.contains("Disk full"))
    }
}
