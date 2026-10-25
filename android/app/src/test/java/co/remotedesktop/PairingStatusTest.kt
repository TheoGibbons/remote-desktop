package co.remotedesktop

import android.content.Context
import android.content.Intent
import android.content.res.XmlResourceParser
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
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

/** After a reinstall the phone has a new key, so the PC must approve it again.
 *  The phone has to say so instead of looking paired and showing a black screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSessionPeerAuth::class, ShadowSessionEnvironment::class],
    instrumentedPackages = ["co.remotedesktop"])
class PairingStatusTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    // ShadowSessionPeerAuth's fingerprint, as the phone shows it.
    private val code = "A3F2-9C41"

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun authField(name: String) = PeerAuth::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(PeerAuth)

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = authField("trustedPeers") as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private val resultFrom get() = authField("resultFrom") as MutableMap<String, String>

    @Suppress("UNCHECKED_CAST")
    private val peerFingerprint get() = authField("peerFingerprint") as MutableMap<String, String>

    @Suppress("UNCHECKED_CAST")
    private val pending get() = authField("pending") as MutableList<PeerAuth.PendingRequest>

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
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        // This phone approved the PC long ago, and its trust store came back with the backup.
        trustedPeers.add("pc")
        peerFingerprint["pc"] = "0123456789abcdef"
    }

    @After
    fun cleanup() {
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        resultFrom.clear()
        peerFingerprint.clear()
        pending.clear()
        for (name in listOf("settings", "install")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** As ConnectionManager records an auth-result, then lets listeners refresh. */
    private fun pcSays(status: String, pc: String = "pc") {
        PeerAuth.onResultFrom(pc, status)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** @param offered whether its View and Browse work, or null when it has none. */
    private data class Row(val name: String, val status: String, val detail: String? = null, val offered: Boolean? = null)

    private fun rows(activity: MainActivity): List<Row> {
        val container = activity.findViewById<LinearLayout>(R.id.peerRows)
        return (0 until container.childCount).map { container.getChildAt(it) }.map { row ->
            val detail = row.findViewById<TextView>(R.id.peerDetail)
            val view = row.findViewById<MaterialButton>(R.id.peerView)
            val offered = if (row.findViewById<View>(R.id.peerActions).visibility != View.VISIBLE) null else {
                assertEquals(view.isEnabled, row.findViewById<View>(R.id.peerBrowse).isEnabled)
                view.isEnabled
            }
            Row(row.findViewById<TextView>(R.id.peerName).text.toString(),
                row.findViewById<TextView>(R.id.peerStatus).text.toString(),
                detail.text.toString().takeIf { detail.visibility == View.VISIBLE }, offered)
        }
    }

    @Test
    fun homeScreenSaysWhatThePcDecidedAndOnlyOffersItOnceApproved() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            // Each device online has a row, so the card needs no summary.
            assertEquals(View.GONE, activity.findViewById<View>(R.id.statusSubtitle).visibility)
            // The PC hasn't answered the device check yet.
            assertEquals(listOf(Row("Test PC · PC", "Checking…", offered = false)), rows(activity))

            pcSays("pending")
            assertEquals(listOf(Row("Test PC · PC", "Waiting for approval",
                "Approve this phone on Test PC. Code: $code", offered = false)), rows(activity))

            pcSays("trusted")
            assertEquals(listOf(Row("Test PC · PC", "Paired", offered = true)), rows(activity))

            // A hang-up from the PC keeps its approval, so the phone can view it again.
            pcSays("disconnected")
            assertEquals(listOf(Row("Test PC · PC", "Paired", offered = true)), rows(activity))

            pcSays("denied")
            assertEquals(listOf(Row("Test PC · PC", "Denied",
                "Test PC denied this phone. Restart the app there to ask again.", offered = false)), rows(activity))

            pcSays("revoked")
            assertEquals(listOf(Row("Test PC · PC", "Revoked",
                "Test PC removed this phone. It asks again when this phone next connects.", offered = false)), rows(activity))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun eachDeviceGetsItsOwnRowAndEachPcItsOwnButtons() {
        ConnectionManager.peers.add(ConnectionManager.Peer("tablet", "android", "Tablet"))
        ConnectionManager.peers.add(ConnectionManager.Peer("pc2", "windows", "Office PC"))
        trustedPeers.add("pc2")
        resultFrom["pc"] = "trusted"
        resultFrom["pc2"] = "pending"
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(listOf(
                Row("Test PC · PC", "Paired", offered = true),
                // This app can't open phones, so they get no buttons.
                Row("Tablet · phone", "Checking…"),
                Row("Office PC · PC", "Waiting for approval", "Approve this phone on Office PC. Code: $code", offered = false),
            ), rows(activity))

            // Each PC's buttons open that PC.
            val first = activity.findViewById<LinearLayout>(R.id.peerRows).getChildAt(0)
            val view = first.findViewById<MaterialButton>(R.id.peerView)
            assertEquals("View Test PC", view.contentDescription)
            view.performClick()
            shadowOf(activity).nextStartedActivity.let {
                assertEquals(ViewerActivity::class.java.name, it.component!!.className)
                assertEquals("pc", it.getStringExtra(ConnectionManager.EXTRA_PEER_ID))
            }
            first.findViewById<MaterialButton>(R.id.peerBrowse).performClick()
            shadowOf(activity).nextStartedActivity.let {
                assertEquals(FileExplorerActivity::class.java.name, it.component!!.className)
                assertEquals("pc", it.getStringExtra(ConnectionManager.EXTRA_PEER_ID))
            }

            // The PC approved this phone, but this phone hasn't answered its own prompt for the PC.
            trustedPeers.remove("pc")
            pending.add(PeerAuth.PendingRequest("pc", "0123456789abcdef", "Test PC", "windows"))
            pcSays("trusted")
            assertEquals(Row("Test PC · PC", "Needs your approval", "Tap here to approve or deny it", offered = false),
                rows(activity)[0])

            pending.clear()
            pcSays("trusted")
            assertEquals(Row("Test PC · PC", "Denied on this phone", offered = false), rows(activity)[0])
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun viewerOpensTheChosenPcAndExplainsTheEmptyScreenUntilItApprovesThisPhone() {
        ConnectionManager.peers.add(ConnectionManager.Peer("pc2", "windows", "Office PC"))
        trustedPeers.add("pc2")
        resultFrom["pc"] = "trusted"
        resultFrom["pc2"] = "pending"
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ConnectionManager.EXTRA_PEER_ID, "pc2")
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val notice = ViewerActivity::class.java.getDeclaredField("approvalNotice")
                .apply { isAccessible = true }.get(activity) as TextView
            assertEquals(View.VISIBLE, notice.visibility)
            assertEquals("Approve this phone on Office PC.\nCode: $code", notice.text.toString())
            assertFalse("Touches must still reach the screen", notice.isClickable)

            socket.messages.clear()
            PeerAuth.onResultFrom("pc2", "trusted")
            @Suppress("UNCHECKED_CAST")
            (ViewerActivity::class.java.getDeclaredField("jsonListener").apply { isAccessible = true }
                .get(activity) as (JSONObject) -> Unit)(
                JSONObject().put("type", "auth-result").put("from", "pc2").put("ok", true).put("status", "trusted"))
            assertEquals(View.GONE, notice.visibility)
            assertTrue("Approval restarts the stream", socket.messages.any {
                it.getString("type") == "start-view" && it.getString("to") == "pc2" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun theRelayIdStaysOutOfBackupsAndRestoredSettings() {
        // A backup made by an older version brings back the old phone's id.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("deviceUid", "restored").commit()
        val uid = Prefs(context).deviceUid
        assertNotEquals("restored", uid)
        assertEquals(uid, Prefs(context).deviceUid)
        assertEquals(uid, context.getSharedPreferences("install", Context.MODE_PRIVATE).getString("deviceUid", null))
        assertFalse(context.getSharedPreferences("settings", Context.MODE_PRIVATE).contains("deviceUid"))

        assertEquals(listOf("full-backup-content"), excludedInstallPrefs(R.xml.backup_rules))
        assertEquals(listOf("cloud-backup", "device-transfer"), excludedInstallPrefs(R.xml.data_extraction_rules))
    }

    /** The sections of a backup rules file that leave out install.xml. */
    private fun excludedInstallPrefs(xml: Int): List<String> {
        val parser = context.resources.getXml(xml)
        val sections = mutableListOf<String>()
        var section = ""
        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.eventType != XmlResourceParser.START_TAG) continue
            if (parser.name != "exclude") section = parser.name
            else if (parser.getAttributeValue(null, "domain") == "sharedpref" &&
                parser.getAttributeValue(null, "path") == "install.xml") sections += section
        }
        return sections
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
