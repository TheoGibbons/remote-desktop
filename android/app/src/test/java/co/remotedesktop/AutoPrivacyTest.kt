package co.remotedesktop

import android.content.Context
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoPrivacyTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }
    private var previousContext: Any? = null
    private var previousKey: Any? = null
    private var previousSocket: Any? = null

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        previousContext = connectionField("appContext").get(ConnectionManager)
        previousKey = connectionField("encKey").get(ConnectionManager)
        previousSocket = connectionField("ws").get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
    }

    @After
    fun cleanup() {
        connectionField("ws").set(ConnectionManager, previousSocket)
        connectionField("encKey").set(ConnectionManager, previousKey)
        connectionField("appContext").set(ConnectionManager, previousContext)
        ConnectionManager.peers.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun settingsDefaultOffAndPersistIndependently() {
        fun checkSettings(privacy: Boolean, unprivacy: Boolean, change: Boolean) {
            val controller = Robolectric.buildActivity(PrivacySettingsActivity::class.java).setup()
            try {
                val activity = controller.get()
                val autoPrivacy = activity.findViewById<MaterialSwitch>(R.id.autoPrivacySwitch)
                val autoUnprivacy = activity.findViewById<MaterialSwitch>(R.id.autoUnprivacySwitch)
                if (change) {
                    autoPrivacy.isChecked = privacy
                    autoUnprivacy.isChecked = unprivacy
                }
                assertEquals(privacy, autoPrivacy.isChecked)
                assertEquals(unprivacy, autoUnprivacy.isChecked)
                assertEquals(privacy, Prefs(context).autoPrivacy)
                assertEquals(unprivacy, Prefs(context).autoUnprivacy)
            } finally {
                controller.pause().stop().destroy()
            }
        }
        checkSettings(false, false, false)
        for ((privacy, unprivacy) in listOf(true to false, false to true, true to true, false to false)) {
            checkSettings(privacy, unprivacy, true)
            checkSettings(privacy, unprivacy, false)
        }
    }

    @Test
    fun rememberingReplacesTheAutoOptions() {
        val controller = Robolectric.buildActivity(PrivacySettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val autoPrivacy = activity.findViewById<MaterialSwitch>(R.id.autoPrivacySwitch)
            val autoUnprivacy = activity.findViewById<MaterialSwitch>(R.id.autoUnprivacySwitch)
            val remember = activity.findViewById<MaterialSwitch>(R.id.rememberPrivacySwitch)
            autoPrivacy.isChecked = true
            autoUnprivacy.isChecked = true
            remember.isChecked = true
            assertFalse(autoPrivacy.isChecked || autoUnprivacy.isChecked)
            assertTrue(Prefs(context).rememberPrivacy)
            assertFalse(Prefs(context).autoPrivacy || Prefs(context).autoUnprivacy)
            autoUnprivacy.isChecked = true
            assertFalse(remember.isChecked)
            assertFalse(Prefs(context).rememberPrivacy)
            assertTrue(Prefs(context).autoUnprivacy)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun settingsRowSummarisesAndOpensAutoPrivacy() {
        Prefs(context).rememberPrivacy = true
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(activity.getString(R.string.auto_privacy_remembered),
                activity.findViewById<TextView>(R.id.autoPrivacyValue).text.toString())
            activity.findViewById<View>(R.id.autoPrivacyRow).performClick()
            assertEquals(PrivacySettingsActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun rememberedPrivacyFollowsEachPcAcrossConnections() {
        Prefs(context).rememberPrivacy = true
        pcFingerprints()["pc"] = "pc-a"
        val controller = Robolectric.buildActivity(ViewerActivity::class.java).setup()
        try {
            val activity = controller.get()
            // The desktop must not restore its screens when this viewer leaves.
            assertFalse(socket.messages.first { it.optString("type") == "start-view" }
                .getBoolean("autoUnprivacy"))
            // Nothing set for this PC yet: leave it as it is.
            status(activity)
            assertTrue(privacyCommands().isEmpty())

            // Turned on in the viewer, it stays on when viewing stops...
            clickPrivacy(activity)
            assertEquals(listOf(true), privacyCommands())
            status(activity, enabled = true)
            socket.messages.clear()
            controller.pause()
            assertTrue(privacyCommands().isEmpty())
            // ...and the next connection turns it back on if it was turned off since.
            controller.resume()
            status(activity, enabled = false)
            assertEquals(listOf(true), privacyCommands())

            // Turned off in the viewer, the next connection turns it off again.
            status(activity, enabled = true)
            clickPrivacy(activity)
            status(activity, enabled = false)
            socket.messages.clear()
            controller.pause().resume()
            status(activity, enabled = true)
            assertEquals(listOf(false), privacyCommands())

            // Another PC keeps its own setting.
            pcFingerprints()["pc"] = "pc-b"
            socket.messages.clear()
            controller.pause().resume()
            status(activity, enabled = true)
            assertTrue(privacyCommands().isEmpty())
        } finally {
            controller.pause().stop().destroy()
            pcFingerprints().remove("pc")
        }
    }

    @Test
    fun viewerAppliesEachCombinationAndRestoresBeforeStopping() {
        for (autoPrivacy in listOf(false, true)) for (autoUnprivacy in listOf(false, true)) {
            Prefs(context).apply {
                this.autoPrivacy = autoPrivacy
                this.autoUnprivacy = autoUnprivacy
            }
            socket.messages.clear()
            val controller = Robolectric.buildActivity(ViewerActivity::class.java).setup()
            try {
                val activity = controller.get()
                assertEquals(autoUnprivacy, socket.messages.first { it.optString("type") == "start-view" }
                    .getBoolean("autoUnprivacy"))
                status(activity, from = "other")
                status(activity, supported = false)
                status(activity, allowed = false)
                assertTrue(privacyCommands().isEmpty())
                status(activity)
                assertEquals(if (autoPrivacy) listOf(true) else emptyList<Boolean>(), privacyCommands())
                // Acknowledgement followed by local unlock must not enable it again.
                status(activity, enabled = true)
                status(activity, enabled = false)
                assertEquals(if (autoPrivacy) listOf(true) else emptyList<Boolean>(), privacyCommands())
                socket.messages.clear()
                controller.pause()
                assertEquals(if (autoUnprivacy) listOf(false) else emptyList<Boolean>(), privacyCommands())
                val lifecycleMessages = socket.messages.filter { it.optString("type") in listOf("privacy-mode", "stop-view") }
                assertEquals(if (autoUnprivacy) listOf("privacy-mode", "stop-view") else listOf("stop-view"),
                    lifecycleMessages.map { it.getString("type") })
                assertTrue(lifecycleMessages.all { it.getString("to") == "pc" })
                controller.stop().destroy()
            } catch (failure: Throwable) {
                controller.pause().stop().destroy()
                throw failure
            }
        }
    }

    @Test
    fun alreadyPrivateDesktopIsLeftAloneAndNextConnectionAppliesAgain() {
        Prefs(context).autoPrivacy = true
        val controller = Robolectric.buildActivity(ViewerActivity::class.java).setup()
        try {
            status(controller.get(), enabled = true)
            status(controller.get(), enabled = false)
            assertTrue(privacyCommands().isEmpty())
            controller.pause().resume()
            status(controller.get())
            assertEquals(listOf(true), privacyCommands())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun status(activity: ViewerActivity, from: String = "pc", enabled: Boolean = false,
                       supported: Boolean = true, allowed: Boolean = true) {
        @Suppress("UNCHECKED_CAST")
        val listener = ViewerActivity::class.java.getDeclaredField("jsonListener")
            .apply { isAccessible = true }.get(activity) as (JSONObject) -> Unit
        listener(JSONObject().put("type", "privacy-state").put("from", from)
            .put("enabled", enabled).put("supported", supported).put("allowed", allowed))
    }

    private fun privacyCommands() = socket.messages.filter { it.optString("type") == "privacy-mode" }
        .map { it.getBoolean("enabled") }

    private fun clickPrivacy(activity: ViewerActivity) {
        val button = ViewerActivity::class.java.getDeclaredField("privacyButton")
            .apply { isAccessible = true }.get(activity) as Button
        assertTrue(button.isEnabled)
        button.performClick()
    }

    /** Peer id to verified device fingerprint, as the auth handshake records it. */
    @Suppress("UNCHECKED_CAST")
    private fun pcFingerprints() = PeerAuth::class.java.getDeclaredField("peerFingerprint")
        .apply { isAccessible = true }.get(PeerAuth) as MutableMap<String, String>

    private class RecordingSocket : WebSocket {
        val key = ByteArray(32) { 7 }
        val messages = mutableListOf<JSONObject>()
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
