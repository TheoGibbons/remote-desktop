package co.remotedesktop

import android.widget.Button
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DesktopFileAccessTest {
    @Test
    fun uploadIsDisabledUntilDesktopAllowsItAndTracksRevocation() {
        val context = ConnectionManager::class.java.getDeclaredField("appContext").apply { isAccessible = true }
        context.set(ConnectionManager, RuntimeEnvironment.getApplication())
        ConnectionManager::class.java.getDeclaredField("fs").apply { isAccessible = true }
            .set(ConnectionManager, FsHandler(RuntimeEnvironment.getApplication()))
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        val controller = Robolectric.buildActivity(FileExplorerActivity::class.java)
        try {
            val activity = controller.setup().get()
            val button = FileExplorerActivity::class.java.getDeclaredField("uploadButton")
                .apply { isAccessible = true }.get(activity) as Button
            @Suppress("UNCHECKED_CAST")
            val listener = FileExplorerActivity::class.java.getDeclaredField("jsonListener")
                .apply { isAccessible = true }.get(activity) as (JSONObject) -> Unit
            fun status(from: String, allowed: Boolean) = listener(JSONObject()
                .put("type", "file-access").put("from", from).put("allowed", allowed))
            assertFalse(button.isEnabled)
            status("other", true)
            assertFalse(button.isEnabled)
            status("pc", true)
            assertTrue(button.isEnabled)
            status("pc", false)
            assertFalse(button.isEnabled)
        } finally {
            controller.pause().stop().destroy()
            ConnectionManager.peers.clear()
            context.set(ConnectionManager, null)
        }
    }
}
