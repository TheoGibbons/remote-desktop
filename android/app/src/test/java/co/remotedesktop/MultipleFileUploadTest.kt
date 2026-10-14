package co.remotedesktop

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Button
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
import org.robolectric.android.controller.ActivityController
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultipleFileUploadTest {
    private lateinit var controller: ActivityController<FileExplorerActivity>
    private lateinit var activity: FileExplorerActivity
    private lateinit var executor: ExecutorService

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        ConnectionManager::class.java.getDeclaredField("appContext")
            .apply { isAccessible = true }.set(ConnectionManager, context)
        val fs = FsHandler(context)
        ConnectionManager::class.java.getDeclaredField("fs")
            .apply { isAccessible = true }.set(ConnectionManager, fs)
        executor = FsHandler::class.java.getDeclaredField("exec")
            .apply { isAccessible = true }.get(fs) as ExecutorService
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        @Suppress("UNCHECKED_CAST")
        val trustedPeers = PeerAuth::class.java.getDeclaredField("trustedPeers")
            .apply { isAccessible = true }.get(PeerAuth) as MutableSet<String>
        trustedPeers.add("pc")
        controller = Robolectric.buildActivity(FileExplorerActivity::class.java).setup()
        activity = controller.get()
        fileAccess(true)
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        controller.pause().stop().destroy()
        ConnectionManager.peers.clear()
        PeerAuth.resetConnection()
        ConnectionManager::class.java.getDeclaredField("appContext")
            .apply { isAccessible = true }.set(ConnectionManager, null)
    }

    private fun field(name: String): Any? = FileExplorerActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun fileAccess(allowed: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val listener = field("jsonListener") as (JSONObject) -> Unit
        listener(JSONObject().put("type", "file-access").put("from", "pc").put("allowed", allowed))
    }

    private fun selectFiles(result: Intent?, resultCode: Int = Activity.RESULT_OK) {
        (field("uploadButton") as Button).performClick()
        val picker = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_GET_CONTENT, picker.action)
        assertEquals("*/*", picker.type)
        assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        shadowOf(activity).receiveResult(picker, resultCode, result)
    }

    private class SelectedStream : ByteArrayInputStream(ByteArray(0)) {
        var closeCount = 0
        override fun close() {
            closeCount++
            super.close()
        }
    }

    @Test
    fun everySelectedFileIsSentOnceAfterDesktopAccessIsRechecked() {
        val first = Uri.parse("content://uploads/first")
        val second = Uri.parse("content://uploads/second")
        val streams = listOf(SelectedStream(), SelectedStream())
        shadowOf(activity.contentResolver).registerInputStream(first, streams[0])
        shadowOf(activity.contentResolver).registerInputStream(second, streams[1])
        val sent = CountDownLatch(2)
        ConnectionManager.fs.transferStatus.add { if (it.startsWith("Sent ")) sent.countDown() }
        // Some pickers return the first URI in both data and ClipData.
        selectFiles(Intent().setData(first).apply {
            clipData = ClipData.newRawUri("files", first).apply { addItem(ClipData.Item(second)) }
        })
        assertEquals(listOf(first, second), field("pendingUploadUris"))
        assertTrue(streams.all { it.closeCount == 0 })

        fileAccess(true)
        assertTrue("Both selected files should be sent", sent.await(5, TimeUnit.SECONDS))
        executor.submit {}.get(5, TimeUnit.SECONDS)
        assertTrue(streams.all { it.closeCount == 1 })
        assertEquals(emptyList<Uri>(), field("pendingUploadUris"))
    }

    @Test
    fun singleSelectionStillUploads() {
        val uri = Uri.parse("content://uploads/single")
        val stream = SelectedStream()
        shadowOf(activity.contentResolver).registerInputStream(uri, stream)
        selectFiles(Intent().setData(uri))
        fileAccess(true)
        executor.submit {}.get(5, TimeUnit.SECONDS)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun revokedAccessDiscardsTheWholeSelectionAndCancelledPickerDoesNothing() {
        val first = Uri.parse("content://uploads/first")
        val second = Uri.parse("content://uploads/second")
        val streams = listOf(SelectedStream(), SelectedStream())
        shadowOf(activity.contentResolver).registerInputStream(first, streams[0])
        shadowOf(activity.contentResolver).registerInputStream(second, streams[1])
        selectFiles(Intent().apply {
            clipData = ClipData.newRawUri("files", first).apply { addItem(ClipData.Item(second)) }
        })
        fileAccess(false)
        assertEquals(emptyList<Uri>(), field("pendingUploadUris"))
        fileAccess(true)
        selectFiles(Intent().setData(first), Activity.RESULT_CANCELED)
        assertEquals(emptyList<Uri>(), field("pendingUploadUris"))
        executor.submit {}.get(5, TimeUnit.SECONDS)
        assertTrue(streams.all { it.closeCount == 0 })
    }
}
