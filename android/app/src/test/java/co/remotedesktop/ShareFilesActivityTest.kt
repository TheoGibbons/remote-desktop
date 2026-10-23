package co.remotedesktop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], shadows = [ShadowSessionPeerAuth::class], instrumentedPackages = ["co.remotedesktop"])
class ShareFilesActivityTest {
    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        ConnectionManager.peers.clear()
        ConnectionManager::class.java.getDeclaredField("appContext")
            .apply { isAccessible = true }.set(ConnectionManager, null)
    }

    @After
    fun tearDown() {
        ConnectionManager::class.java.getDeclaredField("appContext")
            .apply { isAccessible = true }.set(ConnectionManager, null)
    }

    private fun field(activity: ShareFilesActivity, name: String): Any = ShareFilesActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)!!

    @Test
    fun shareScreenKeepsFilesAcrossRotationAndProvidesSetupWithoutSending() {
        val files = arrayListOf(Uri.parse("content://gallery/one"), Uri.parse("content://gallery/two"))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, files)
        val controller = Robolectric.buildActivity(ShareFilesActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShareFilesModel::class.java]
            assertEquals(files, model.remaining)
            assertEquals("2 files to send", (field(activity, "countText") as TextView).text.toString())
            assertFalse((field(activity, "sendButton") as MaterialButton).isEnabled)
            (field(activity, "connectButton") as MaterialButton).performClick()
            assertEquals(SessionIdActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            controller.recreate()
            val recreated = controller.get()
            assertSame(model, ViewModelProvider(recreated)[ShareFilesModel::class.java])
            assertEquals(files, model.remaining)
            assertFalse(model.busy)
            assertEquals("2 files to send", (field(recreated, "countText") as TextView).text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun textOnlyShareGoesToThePcClipboard() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")
        val controller = Robolectric.buildActivity(ShareFilesActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val next = shadowOf(activity).nextStartedActivity
            assertEquals(CopyToPcActivity::class.java.name, next.component?.className)
            assertEquals("hello", next.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun tooMuchSharedTextIsRefusedBeforeItIsPassedOn() {
        val text = "x".repeat(PcClipboard.MAX_CHARS + 1)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val controller = Robolectric.buildActivity(ShareFilesActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Too much text"))
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun shareWithNothingReadableShowsAnEmptyStateWithSendingDisabled() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg")
        val controller = Robolectric.buildActivity(ShareFilesActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertFalse((field(activity, "sendButton") as MaterialButton).isEnabled)
            assertTrue((field(activity, "statusText") as TextView).text.toString().contains("No readable files"))
        } finally { controller.pause().stop().destroy() }
    }
}
