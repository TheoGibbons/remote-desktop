package co.remotedesktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowPopupMenu
import java.io.File
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h750dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileExplorerActivityTest {
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
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        controller = Robolectric.buildActivity(FileExplorerActivity::class.java).setup().visible()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
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

    private fun result(entries: List<DesktopFileEntry>, path: String = "C:\\Work", reqId: String = field("lastReqId") as String,
                       downloads: String? = null) {
        deliver(JSONObject().put("type", "fs-list-result").put("from", "pc").put("reqId", reqId).put("path", path)
            .put("downloadsPath", downloads)
            .put("entries", JSONArray().apply {
                entries.forEach { put(JSONObject().put("name", it.name).put("dir", it.dir)
                    .put("size", it.size).put("mtime", it.mtime)) }
            }))
    }

    private val sample = listOf(
        DesktopFileEntry("report.pdf", false, 1536, 1_791_998_400_000),
        DesktopFileEntry("Projects", true, 0, 1_791_912_000_000),
        DesktopFileEntry("archive.zip", false, 4_194_304, 1_791_825_600_000),
        DesktopFileEntry("Backups", true, 0, 1_791_739_200_000),
        DesktopFileEntry("empty.txt", false, 0, 0),
    )

    private fun selectSort(sort: DesktopFileSort) {
        activity.findViewById<MaterialButton>(R.id.filesSort).performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        assertEquals(1, (0 until popup.menu.size()).count { popup.menu.getItem(it).run { groupId == 0 && isChecked } })
        assertTrue(popup.menu.performIdentifierAction(sort.ordinal, 0))
        popup.dismiss()
    }

    private fun toggleFoldersFirst(wasTicked: Boolean) {
        activity.findViewById<MaterialButton>(R.id.filesSort).performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        val item = popup.menu.findItem(DesktopFileSort.entries.size)
        assertEquals("Folders first", item.title.toString())
        assertTrue(item.isCheckable)
        assertEquals(wasTicked, item.isChecked)
        assertTrue(popup.menu.performIdentifierAction(item.itemId, 0))
        popup.dismiss()
    }

    private fun names(): List<String> {
        val adapter = activity.findViewById<ListView>(R.id.filesList).adapter
        return (0 until adapter.count).map { (adapter.getItem(it) as DesktopFileEntry).name }
    }

    @Test
    fun allSortOrdersMixFoldersWithFilesAndSelectionSurvivesNewListingsAndRecreation() {
        result(sample)
        val expected = mapOf(
            DesktopFileSort.NAME_ASC to listOf("archive.zip", "Backups", "empty.txt", "Projects", "report.pdf"),
            DesktopFileSort.NAME_DESC to listOf("report.pdf", "Projects", "empty.txt", "Backups", "archive.zip"),
            DesktopFileSort.SIZE_DESC to listOf("archive.zip", "report.pdf", "empty.txt", "Backups", "Projects"),
            DesktopFileSort.SIZE_ASC to listOf("Backups", "Projects", "empty.txt", "report.pdf", "archive.zip"),
            DesktopFileSort.MODIFIED_DESC to listOf("report.pdf", "Projects", "archive.zip", "Backups", "empty.txt"),
            DesktopFileSort.MODIFIED_ASC to listOf("empty.txt", "Backups", "archive.zip", "Projects", "report.pdf"),
        )
        expected.forEach { (sort, ordered) ->
            selectSort(sort)
            assertEquals(sort.name, ordered, names())
        }
        selectSort(DesktopFileSort.SIZE_DESC)
        result(sample.reversed())
        assertEquals(expected[DesktopFileSort.SIZE_DESC], names())

        controller.recreate()
        activity = controller.get()
        assertEquals("C:\\Work", activity.findViewById<TextView>(R.id.filesPath).text.toString())
        assertEquals(DesktopFileSort.SIZE_DESC, field("sort"))
        result(sample)
        assertEquals(expected[DesktopFileSort.SIZE_DESC], names())
    }

    @Test
    fun startsNewestFirstWithFoldersMixedIn() {
        result(sample)
        assertEquals(listOf("report.pdf", "Projects", "archive.zip", "Backups", "empty.txt"), names())
        val sortButton = activity.findViewById<MaterialButton>(R.id.filesSort)
        assertEquals("Modified ↓", sortButton.text.toString())
        assertEquals("Sort: Modified (newest first)", sortButton.contentDescription)
    }

    @Test
    fun foldersFirstTickboxGroupsFoldersInEveryOrderAndIsRemembered() {
        result(sample)
        toggleFoldersFirst(wasTicked = false)
        val expected = mapOf(
            DesktopFileSort.NAME_ASC to listOf("Backups", "Projects", "archive.zip", "empty.txt", "report.pdf"),
            DesktopFileSort.NAME_DESC to listOf("Projects", "Backups", "report.pdf", "empty.txt", "archive.zip"),
            DesktopFileSort.SIZE_DESC to listOf("Backups", "Projects", "archive.zip", "report.pdf", "empty.txt"),
            DesktopFileSort.SIZE_ASC to listOf("Backups", "Projects", "empty.txt", "report.pdf", "archive.zip"),
            DesktopFileSort.MODIFIED_DESC to listOf("Projects", "Backups", "report.pdf", "archive.zip", "empty.txt"),
            DesktopFileSort.MODIFIED_ASC to listOf("Backups", "Projects", "empty.txt", "archive.zip", "report.pdf"),
        )
        expected.forEach { (sort, ordered) ->
            selectSort(sort)
            assertEquals(sort.name, ordered, names())
        }
        val sortButton = { activity.findViewById<MaterialButton>(R.id.filesSort) }
        assertEquals("Sort: Modified (oldest first), folders first", sortButton().contentDescription)

        controller.recreate()
        activity = controller.get()
        result(sample)
        assertEquals(expected[DesktopFileSort.MODIFIED_ASC], names())
        toggleFoldersFirst(wasTicked = true)
        assertEquals(listOf("empty.txt", "Backups", "archive.zip", "Projects", "report.pdf"), names())
        assertEquals("Sort: Modified (oldest first)", sortButton().contentDescription)
    }

    @Test
    fun opensInTheDesktopDownloadsFolderWithoutDrawingTheDrivesFirst() {
        val downloads = "D:\\Owner\\Downloads"
        assertEquals("", field("requestedPath"))
        result(listOf(DesktopFileEntry("C:\\", true, 0, 0)), path = "", downloads = downloads)
        assertEquals(downloads, field("requestedPath"))
        assertEquals(emptyList<String>(), names())
        assertFalse(activity.findViewById<ListView>(R.id.filesList).isEnabled)

        result(sample, path = downloads, downloads = downloads)
        assertEquals(downloads, activity.findViewById<TextView>(R.id.filesPath).text.toString())
        assertEquals(sample.size, names().size)
        assertTrue(activity.findViewById<TextView>(R.id.filesStatus).text.contains("5 items"))

        // Rotating or going back to the drives later must not jump to Downloads again.
        controller.recreate()
        activity = controller.get()
        assertEquals(downloads, field("requestedPath"))
        result(sample, path = downloads, downloads = downloads)
        activity.findViewById<ImageButton>(R.id.filesUp).performClick()
        result(emptyList(), path = "D:\\Owner", downloads = downloads)
        activity.findViewById<ImageButton>(R.id.filesUp).performClick()
        result(emptyList(), path = "D:\\", downloads = downloads)
        activity.findViewById<ImageButton>(R.id.filesUp).performClick()
        assertEquals("", field("requestedPath"))
        result(listOf(DesktopFileEntry("C:\\", true, 0, 0)), path = "", downloads = downloads)
        assertEquals("This PC", activity.findViewById<TextView>(R.id.filesPath).text.toString())
        assertEquals(listOf("C:\\"), names())
    }

    @Test
    fun olderDesktopsWithoutADownloadsPathOpenAtTheDrives() {
        result(listOf(DesktopFileEntry("C:\\", true, 0, 0)), path = "")
        assertEquals("This PC", activity.findViewById<TextView>(R.id.filesPath).text.toString())
        assertEquals(listOf("C:\\"), names())
        assertTrue(activity.findViewById<ListView>(R.id.filesList).isEnabled)
    }

    @Test
    fun fileRowsShowSizeAndDetailsDoNotDownloadUntilRequested() {
        result(sample)
        val list = activity.findViewById<ListView>(R.id.filesList)
        val row = list.adapter.getView(names().indexOf("report.pdf"), null, list)
        val metadata = row.findViewById<TextView>(R.id.fileMetadata).text.toString()
        assertTrue(metadata.contains("1.5 KB"))
        assertTrue(row.findViewById<TextView>(R.id.fileMetadata).contentDescription.contains("Modified"))
        row.findViewById<ImageButton>(R.id.fileDetails).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val text = allText(dialog.window!!.decorView)
        assertTrue(text.contains("report.pdf"))
        assertTrue(text.contains("PDF file"))
        assertTrue(text.contains("1.5 KB (1,536 bytes)"))
        assertTrue(text.contains("C:\\Work\\report.pdf"))
        assertFalse(activity.findViewById<TextView>(R.id.filesStatus).text.startsWith("Downloading"))
        preview("details", dialog.window!!.decorView)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Downloading report.pdf...", activity.findViewById<TextView>(R.id.filesStatus).text.toString())
    }

    @Test
    fun foldersWithNoMetadataDoNotClaimZeroBytesAndCanBeOpenedFromDetails() {
        result(listOf(DesktopFileEntry("C:\\", true, 0, 0)), path = "")
        assertFalse(activity.findViewById<ImageButton>(R.id.filesUp).isEnabled)
        val list = activity.findViewById<ListView>(R.id.filesList)
        val row = list.adapter.getView(0, null, list)
        row.findViewById<ImageButton>(R.id.fileDetails).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val text = allText(dialog.window!!.decorView)
        assertTrue(text.contains("Not calculated"))
        assertTrue(text.contains("Unavailable"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("C:\\", field("requestedPath"))
        assertFalse(list.isEnabled)
        result(emptyList(), path = "C:\\")
        assertTrue(activity.findViewById<ImageButton>(R.id.filesUp).isEnabled)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.filesEmpty).visibility)
        assertEquals("This folder is empty", activity.findViewById<TextView>(R.id.filesEmpty).text.toString())
    }

    @Test
    fun staleResultsAreIgnoredAndErrorsLeaveTheCurrentFolderUsable() {
        result(sample)
        val original = names()
        result(emptyList(), path = "C:\\Stale", reqId = "stale")
        assertEquals(original, names())
        activity.findViewById<ImageButton>(R.id.filesUp).performClick()
        assertEquals("C:\\", field("requestedPath"))
        assertFalse(activity.findViewById<ListView>(R.id.filesList).isEnabled)
        deliver(JSONObject().put("type", "fs-list-result").put("reqId", field("lastReqId"))
            .put("error", "Access denied"))
        assertTrue(activity.findViewById<ListView>(R.id.filesList).isEnabled)
        assertEquals("C:\\Work", field("path"))
        assertEquals(original, names())
        assertTrue(activity.findViewById<TextView>(R.id.filesStatus).text.contains("Access denied"))
    }

    @Test
    @Config(qualifiers = "en-rUS-w780dp-h330dp-land-night")
    fun landscapeKeepsRoomForFilesAndLongPathsDoNotPushControlsOffScreen() {
        result(sample + listOf(DesktopFileEntry("A very long file name that still keeps its extension visible.pdf", false, 12, 0)),
            path = "C:\\Users\\Desktop Owner\\Documents\\A very long folder name\\Work")
        deliver(JSONObject().put("type", "file-access").put("from", "pc").put("allowed", true))
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        layout(root, 780, 330)
        val list = activity.findViewById<ListView>(R.id.filesList)
        assertTrue("At least three rows should fit in landscape", list.height >= dp(192))
        assertTrue(activity.findViewById<View>(R.id.filesSort).right <= root.width)
        assertTrue(activity.findViewById<View>(R.id.filesUpload).width >= dp(48))
        preview("landscape-dark", root)
    }

    @Test
    fun portraitShowsSeveralFilesAndFullPathIsAvailable() {
        result(sample)
        deliver(JSONObject().put("type", "file-access").put("from", "pc").put("allowed", true))
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        layout(root, 390, 750)
        assertTrue(activity.findViewById<ListView>(R.id.filesList).height >= dp(320))
        preview("portrait-light", root)
        activity.findViewById<TextView>(R.id.filesPath).performClick()
        assertTrue(allText(ShadowDialog.getLatestDialog()!!.window!!.decorView).contains("C:\\Work"))
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun largeTextStillLeavesUsableRowsAndVisibleActionsOnSmallPhones() {
        RuntimeEnvironment.setFontScale(1.5f)
        controller.recreate()
        activity = controller.get()
        result(sample)
        selectSort(DesktopFileSort.MODIFIED_DESC)
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        layout(root, 320, 640)
        val list = activity.findViewById<ListView>(R.id.filesList)
        assertTrue(list.height >= dp(320))
        val row = list.adapter.getView(0, null, list)
        row.measure(View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        val name = row.findViewById<TextView>(R.id.fileName)
        val details = row.findViewById<View>(R.id.fileDetails)
        assertTrue(name.height >= name.lineHeight)
        assertTrue(details.width >= dp(48))
        assertTrue(details.right <= list.width)
        preview("portrait-large-text", root)
    }

    @Test
    fun rotationRestoresTheSameFileAndOffsetAfterTheFolderReplyArrives() {
        val files = (0 until 60).map { DesktopFileEntry("file-%02d.txt".format(it), false, it.toLong(), 0) }
        result(files)
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 390, 750)
        var list = activity.findViewById<ListView>(R.id.filesList)
        list.setSelectionFromTop(24, -dp(17))
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 390, 750)
        assertEquals(24, list.firstVisiblePosition)
        val offset = list.getChildAt(0).top - list.paddingTop
        val previousRequestId = field("lastReqId") as String

        RuntimeEnvironment.setQualifiers("en-rUS-w780dp-h330dp-land")
        controller.recreate()
        activity = controller.get()
        list = activity.findViewById(R.id.filesList)
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 780, 330)
        assertEquals("The folder response is asynchronous", 0, list.adapter.count)
        result(files, reqId = previousRequestId)
        assertEquals("A reply sent before rotation must be ignored", 0, list.adapter.count)
        result(files)
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 780, 330)
        assertEquals(24, list.firstVisiblePosition)
        assertEquals(offset, list.getChildAt(0).top - list.paddingTop)

        RuntimeEnvironment.setQualifiers("en-rUS-w390dp-h750dp-port")
        controller.recreate()
        activity = controller.get()
        result(files)
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 390, 750)
        list = activity.findViewById(R.id.filesList)
        assertEquals(24, list.firstVisiblePosition)
        assertEquals(offset, list.getChildAt(0).top - list.paddingTop)
    }

    @Test
    fun repeatedRotationKeepsTheFileAnchorButOpeningAnotherFolderStartsAtTheTop() {
        val folders = (0 until 60).map { DesktopFileEntry("folder-%02d".format(it), true, 0, 0) }
        result(folders)
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 390, 750)
        var list = activity.findViewById<ListView>(R.id.filesList)
        list.setSelectionFromTop(24, -dp(9))
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 390, 750)
        assertEquals(24, list.firstVisiblePosition)
        val offset = list.getChildAt(0).top - list.paddingTop

        // Rotate twice before either remote listing has arrived.
        RuntimeEnvironment.setQualifiers("en-rUS-w780dp-h330dp-land")
        controller.recreate()
        activity = controller.get()
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 780, 330)
        RuntimeEnvironment.setQualifiers("en-rUS-w390dp-h750dp-port")
        controller.recreate()
        activity = controller.get()
        val changedFolders = folders + DesktopFileEntry("added-folder", true, 0, 0)
        result(changedFolders)
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 390, 750)
        list = activity.findViewById(R.id.filesList)
        assertEquals(25, list.firstVisiblePosition)
        assertEquals("folder-24", (list.adapter.getItem(list.firstVisiblePosition) as DesktopFileEntry).name)
        assertEquals(offset, list.getChildAt(0).top - list.paddingTop)

        list.performItemClick(list.getChildAt(0), list.firstVisiblePosition, list.firstVisiblePosition.toLong())
        val childPath = "C:\\Work\\folder-24"
        assertEquals(childPath, field("requestedPath"))
        controller.recreate()
        activity = controller.get()
        result(folders, path = childPath)
        layout(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), 390, 750)
        list = activity.findViewById(R.id.filesList)
        assertEquals(0, list.firstVisiblePosition)
        assertEquals(0, list.getChildAt(0).top - list.paddingTop)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun allText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { allText(view.getChildAt(it)) }
        else -> ""
    }

    private fun preview(name: String, view: View) {
        if (view.width <= 0 || view.height <= 0) return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(activity.getColor(R.color.rd_background))
        view.draw(canvas)
        val target = File("build/reports/file-browser/$name.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
