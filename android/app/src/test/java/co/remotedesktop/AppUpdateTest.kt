package co.remotedesktop

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The relay names the newest release. The phone says once when it is behind,
 *  and offers to update a PC that is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSessionPeerAuth::class, ShadowSessionEnvironment::class],
    instrumentedPackages = ["co.remotedesktop"])
class AppUpdateTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private val handleJson = ConnectionManager::class.java.getDeclaredMethod("handleJson", JSONObject::class.java)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = PeerAuth::class.java.getDeclaredField("trustedPeers")
        .apply { isAccessible = true }.get(PeerAuth) as MutableSet<String>

    @Before
    fun setup() {
        for (name in listOf("settings", "install")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).autoConnect = false
        for (name in listOf("appContext", "encKey", "ws", "fs", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("fs").set(ConnectionManager, FsHandler(context))
        connectionField("state").set(ConnectionManager, "connected")
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // A test build is never a tagged release, so it would never say.
        AppUpdate.enabledOverride = true
    }

    @After
    fun cleanup() {
        AppUpdate.enabledOverride = null
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        PeerAuth.onPeerLeft("pc")
        for (name in listOf("settings", "install")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun receive(json: String) {
        handleJson.invoke(ConnectionManager, JSONObject(json))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun welcome(latest: String?, vararg peers: JSONObject) = receive(JSONObject()
        .put("type", "welcome").put("id", "me").put("peers", JSONArray(peers.toList()))
        .apply { if (latest != null) put("latest", latest) }.toString())

    private fun pc(version: String?) = JSONObject().put("id", "pc").put("device", "windows").put("name", "Test PC")
        .apply { if (version != null) put("version", version) }

    private val updateNotice: Notification? get() = notifications.getNotification("update", 0)

    @Test
    fun peersCarryTheirVersions() {
        welcome(null, pc("1.0.50"), JSONObject().put("id", "old").put("device", "android").put("name", "Old phone"))
        assertEquals(listOf("1.0.50", null), ConnectionManager.peers.map { it.version })
    }

    @Test
    fun aNewerReleaseIsAnnouncedOncePerVersion() {
        welcome("99.0.0")
        val notice = updateNotice!!
        assertEquals("Remote Desktop 99.0.0 is available", notice.extras.getString(Notification.EXTRA_TITLE))
        val download = shadowOf(notice.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, download.action)
        assertEquals("https://github.com/TheoGibbons/remote-desktop/releases/download/v99.0.0/RemoteDesktop-Android.apk",
            download.dataString)

        // Every reconnect brings the same welcome; it isn't news again.
        context.getSystemService(NotificationManager::class.java).cancelAll()
        welcome("99.0.0")
        assertNull(updateNotice)

        // A release while connected is.
        receive("""{"type":"latest","version":"99.0.1"}""")
        assertEquals("Remote Desktop 99.0.1 is available", updateNotice!!.extras.getString(Notification.EXTRA_TITLE))

        // Once this is the newest, the notice has done its job.
        welcome(BuildConfig.VERSION_NAME)
        assertNull(updateNotice)
    }

    @Test
    fun onlyTheRelayNamesReleases() {
        receive("""{"type":"latest","version":"99.0.0","from":"pc"}""")
        assertNull(AppUpdate.latest(context))
        assertNull(updateNotice)
        receive("""{"type":"latest","version":"../../evil"}""")
        assertNull(AppUpdate.latest(context))
    }

    @Test
    fun buildsThatDontCheckStayQuiet() {
        AppUpdate.enabledOverride = false
        welcome("99.0.0")
        assertNull(updateNotice)
        assertFalse(AppUpdate.updateAvailable(context))
    }

    @Test
    fun versionsCompareByNumber() {
        assertTrue(AppUpdate.compare("1.0.10", "1.0.9") > 0)
        assertEquals(0, AppUpdate.compare("1.0.52", "1.0.52"))
        assertTrue(AppUpdate.compare("1.1.0-beta", "1.1.0") < 0)
        assertTrue(AppUpdate.compare("2.0.0", "1.99.99") > 0)
        assertFalse(AppUpdate.isNewer("1.0.53", null))
    }

    @Test
    fun settingsOffersTheDownload() {
        Prefs(context).latestRelease = "99.0.0"
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals("${BuildConfig.VERSION_NAME} · 99.0.0 available",
                activity.findViewById<TextView>(R.id.versionValue).text.toString())
            activity.findViewById<View>(R.id.versionRow).performClick()
            val started = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, started.action)
            assertTrue(started.dataString!!.endsWith("/v99.0.0/RemoteDesktop-Android.apk"))
        } finally {
            controller.pause().stop().destroy()
        }

        Prefs(context).latestRelease = BuildConfig.VERSION_NAME
        val upToDate = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            assertEquals(BuildConfig.VERSION_NAME,
                upToDate.get().findViewById<TextView>(R.id.versionValue).text.toString())
        } finally {
            upToDate.pause().stop().destroy()
        }
    }

    @Test
    fun aPcThatIsBehindCanBeUpdatedFromTheHomeScreen() {
        welcome("1.0.53", pc("1.0.50"))
        trustedPeers.add("pc")
        PeerAuth.onResultFrom("pc", "trusted")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            fun row() = activity.findViewById<LinearLayout>(R.id.peerRows).getChildAt(0)
            fun button() = row().findViewById<MaterialButton>(R.id.peerUpdate)
            assertEquals("Version 1.0.53 is out. Test PC has 1.0.50.",
                row().findViewById<TextView>(R.id.peerDetail).text.toString())
            assertEquals(View.VISIBLE, button().visibility)
            assertEquals("Update PC to 1.0.53", button().text.toString())

            button().performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("pc", socket.messages.single { it.optString("type") == "update-now" }.optString("to"))
            assertEquals("Updating…", button().text.toString())
            assertFalse(button().isEnabled)

            receive("""{"type":"update-status","state":"updating","version":"1.0.53","from":"pc"}""")
            assertEquals("Test PC is updating to version 1.0.53. It reconnects when it restarts.", ShadowToast.getTextOfLatestToast())
            assertFalse(button().isEnabled)

            receive("""{"type":"update-status","state":"failed","error":"GitHub answered 404 NotFound.","from":"pc"}""")
            assertEquals("Test PC couldn't update: GitHub answered 404 NotFound.", ShadowToast.getTextOfLatestToast())
            assertTrue(button().isEnabled)

            // Back on the new version, it has nothing to offer.
            receive("""{"type":"peer-left","id":"pc"}""")
            receive("""{"type":"peer-joined","peer":${pc("1.0.53")}}""")
            trustedPeers.add("pc")
            PeerAuth.onResultFrom("pc", "trusted")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, button().visibility)
            assertEquals(View.GONE, row().findViewById<TextView>(R.id.peerDetail).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aPcFromBeforeVersionsIsNotOfferedAnUpdate() {
        welcome("1.0.53", pc(null))
        trustedPeers.add("pc")
        PeerAuth.onResultFrom("pc", "trusted")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val row = controller.get().findViewById<LinearLayout>(R.id.peerRows).getChildAt(0)
            // It wouldn't understand update-now.
            assertEquals(View.GONE, row.findViewById<View>(R.id.peerUpdate).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

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
