package co.remotedesktop

import android.widget.Button
import android.graphics.Color
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
class PrivacyModeTest {
    @Test
    fun buttonTracksHostAcknowledgementsAndLocalUnlock() {
        val context = ConnectionManager::class.java.getDeclaredField("appContext").apply { isAccessible = true }
        context.set(ConnectionManager, RuntimeEnvironment.getApplication())
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        val controller = Robolectric.buildActivity(ViewerActivity::class.java)
        try {
            val activity = controller.setup().get()
            val button = ViewerActivity::class.java.getDeclaredField("privacyButton")
                .apply { isAccessible = true }.get(activity) as Button
            val normalTint = button.backgroundTintList
            val normalTextColors = button.textColors
            @Suppress("UNCHECKED_CAST")
            val listener = ViewerActivity::class.java.getDeclaredField("jsonListener")
                .apply { isAccessible = true }.get(activity) as (JSONObject) -> Unit
            fun status(from: String = "pc", enabled: Boolean = false, supported: Boolean = true,
                       allowed: Boolean = true, error: String? = null) = listener(JSONObject()
                .put("type", "privacy-state").put("from", from).put("enabled", enabled)
                .put("supported", supported).put("allowed", allowed).put("error", error))
            assertFalse(button.isEnabled) // older hosts never advertise support
            status(from = "other", enabled = true)
            assertFalse(button.isEnabled)
            status()
            assertTrue(button.isEnabled)
            button.performClick()
            assertFalse(button.isEnabled)
            assertEquals("Privacy", button.text.toString()) // waits for acknowledgement
            assertEquals(normalTint, button.backgroundTintList)
            status(enabled = true)
            assertTrue(button.isEnabled)
            assertEquals("Show PC", button.text.toString())
            assertEquals(Color.rgb(179, 38, 30), button.backgroundTintList!!.defaultColor)
            assertEquals(Color.WHITE, button.currentTextColor)
            button.performClick()
            assertFalse(button.isEnabled)
            val timeout = ViewerActivity::class.java.getDeclaredField("privacyTimeout")
                .apply { isAccessible = true }.get(activity) as Runnable
            timeout.run()
            assertTrue(button.isEnabled)
            assertEquals("Show PC", button.text.toString()) // no optimistic change on timeout
            status(enabled = false) // local "unlock" notification
            assertEquals("Privacy", button.text.toString())
            assertEquals(normalTint, button.backgroundTintList)
            assertEquals(normalTextColors, button.textColors)
            status(enabled = true)
            @Suppress("UNCHECKED_CAST")
            val stateListener = ViewerActivity::class.java.getDeclaredField("stateListener")
                .apply { isAccessible = true }.get(activity) as (String) -> Unit
            stateListener("disconnected")
            assertFalse(button.isEnabled)
            assertEquals("Privacy", button.text.toString())
            assertEquals(normalTint, button.backgroundTintList)
            status(allowed = false)
            assertFalse(button.isEnabled)
            status(supported = false)
            assertFalse(button.isEnabled)
            status(error = "Cannot exclude overlay from capture")
            assertTrue(button.isEnabled) // failed activation can be retried
            assertEquals("Privacy", button.text.toString())
        } finally {
            controller.pause().stop().destroy()
            ConnectionManager.peers.clear()
            context.set(ConnectionManager, null)
        }
    }
}
