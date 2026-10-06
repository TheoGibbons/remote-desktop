package co.remotedesktop

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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
    fun notificationLocatesActualRenamedFileAndEachTransferHasItsOwnAction() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val first = savedPath("report (1).pdf")
        val second = savedPath("photo #2.jpg")
        ReceivedFileNotification.show(context, first, 100)
        ReceivedFileNotification.show(context, second, 101)

        val notifications = shadowOf(manager).allNotifications
        assertEquals(2, notifications.size)
        for (path in listOf(first, second)) {
            val notification = shadowOf(manager).getNotification("received:$path", 0)!!
            assertEquals("File received", notification.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification.extras.getString(Notification.EXTRA_TEXT)!!.contains(File(path).name))
            val action = notification.actions.single()
            assertEquals("Locate file", action.title.toString())
            assertEquals(notification.contentIntent, action.actionIntent)
            val picker = shadowOf(action.actionIntent).savedIntent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
            assertEquals("*/*", picker.type)
            assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE))
            @Suppress("DEPRECATION")
            val uri = picker.getParcelableExtra<android.net.Uri>(DocumentsContract.EXTRA_INITIAL_URI)!!
            assertEquals("primary:Download/${File(path).name}", DocumentsContract.getDocumentId(uri))
        }
        assertNotEquals(notifications[0].contentIntent, notifications[1].contentIntent)
    }

    @Test
    @Config(sdk = [34])
    fun deniedNotificationPermissionDoesNotThrowOrPost() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReceivedFileNotification.show(context, savedPath("report.pdf"), 102)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
