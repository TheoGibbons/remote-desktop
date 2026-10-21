package co.remotedesktop

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ReceivedFileNotificationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun savedPath(name: String) = File(
        Environment.getExternalStorageDirectory(), "Download/$name"
    ).path

    @Test
    fun tapOpensEachFileAndActionShowsDownloadsThroughTheRelay() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val first = savedPath("report (1).pdf")
        val second = savedPath("photo #2.jpg")
        val firstUri = Uri.parse("content://media/external/downloads/41")
        val secondUri = Uri.parse("content://media/external/downloads/42")
        ReceivedFileNotification.show(context, first, firstUri, 100)
        ReceivedFileNotification.show(context, second, secondUri, 101)

        val notifications = shadowOf(manager).allNotifications
        assertEquals(2, notifications.size)
        for ((path, uri) in listOf(first to firstUri, second to secondUri)) {
            val notification = shadowOf(manager).getNotification("received:$path", 0)!!
            assertEquals("File received", notification.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification.extras.getString(Notification.EXTRA_TEXT)!!.contains(File(path).name))
            val open = shadowOf(notification.contentIntent).savedIntent
            assertEquals(ReceivedFileActivity::class.java.name, open.component!!.className)
            assertEquals(Intent.ACTION_VIEW, open.action)
            assertEquals(uri, open.data)
            val action = notification.actions.single()
            assertEquals("Show in Downloads", action.title.toString())
            val downloads = shadowOf(action.actionIntent).savedIntent
            assertEquals(ReceivedFileActivity::class.java.name, downloads.component!!.className)
            assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, downloads.action)
        }
        assertNotEquals(notifications[0].contentIntent, notifications[1].contentIntent)
    }

    // Robolectric's fake MediaStore throws on unknown items, so the relay is fed
    // a FileProvider URI; it treats every content URI the same way.
    private val fileUri get() = Uri.parse("content://${context.packageName}.fileprovider/downloads/report.pdf")

    @Test
    fun relayOpensFileWithReadGrantAndDownloadsInTheirOwnTasks() {
        val uri = fileUri
        val opened = Robolectric.buildActivity(
            ReceivedFileActivity::class.java, ReceivedFileNotification.openIntent(context, uri)
        ).create().get()
        val view = shadowOf(opened).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(uri, view.data)
        assertNull("Must not target this app", view.component)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(view.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(opened.isFinishing)

        val shown = Robolectric.buildActivity(
            ReceivedFileActivity::class.java, ReceivedFileNotification.showDownloadsIntent(context)
        ).create().get()
        val downloads = shadowOf(shown).nextStartedActivity
        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, downloads.action)
        assertNull(downloads.component)
        assertTrue(downloads.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(shown.isFinishing)
    }

    @Test
    fun relaySaysSoWhenNoAppCanHandleTheTap() {
        // Unresolvable intents throw, as on a phone with its file managers disabled.
        shadowOf(context).checkActivities(true)
        val shown = Robolectric.buildActivity(
            ReceivedFileActivity::class.java, ReceivedFileNotification.showDownloadsIntent(context)
        ).create().get()
        assertEquals("No app can show Downloads", ShadowToast.getTextOfLatestToast())
        assertTrue(shown.isFinishing)

        val opened = Robolectric.buildActivity(
            ReceivedFileActivity::class.java,
            ReceivedFileNotification.openIntent(context, fileUri)
        ).create().get()
        assertEquals("No app can open this file", ShadowToast.getTextOfLatestToast())
        assertTrue(opened.isFinishing)
    }

    @Test
    fun fileProviderForReceivedFilesCanGrantReadAccess() {
        @Suppress("DEPRECATION")
        val provider = context.packageManager.resolveContentProvider(
            "${context.packageName}.fileprovider", PackageManager.GET_META_DATA
        )!!
        assertEquals("androidx.core.content.FileProvider", provider.name)
        assertTrue(provider.grantUriPermissions)
        assertFalse(provider.exported)
        assertEquals(R.xml.received_file_paths, provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
    }

    @Test
    @Config(sdk = [28])
    fun preScopedStorageReceiveSharesTheSavedFileThroughFileProvider() {
        // FileProvider matches roots with '/', so it can't resolve Windows paths (CI and dev boxes).
        assumeTrue(File.separatorChar == '/')
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val fs = FsHandler(context)
        val statuses = mutableListOf<String>()
        fs.transferStatus.add { statuses.add(it) }
        val xferId = 7
        fs.handleJson(JSONObject().put("type", "fs-begin").put("xferId", xferId).put("name", "report.pdf"), true)
        fs.handleFileChunk(byteArrayOf(2, 0, 0, 0, xferId.toByte()) + "hello".toByteArray())
        fs.handleJson(JSONObject().put("type", "fs-end").put("xferId", xferId).put("ok", true), true)

        val saved = savedPath("report.pdf")
        assertEquals("Saved $saved", statuses.last())
        assertEquals("hello", File(saved).readText())
        val open = shadowOf(shadowOf(manager).getNotification("received:$saved", 0)!!.contentIntent).savedIntent
        assertEquals(Uri.parse("content://${context.packageName}.fileprovider/downloads/report.pdf"), open.data)
    }

    @Test
    @Config(sdk = [34])
    fun deniedNotificationPermissionDoesNotThrowOrPost() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReceivedFileNotification.show(
            context, savedPath("report.pdf"), Uri.parse("content://media/external/downloads/42"), 102
        )
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
