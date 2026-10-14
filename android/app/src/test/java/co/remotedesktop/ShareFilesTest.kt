package co.remotedesktop

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35, 36])
class ShareFilesTest {
    private val first = Uri.parse("content://gallery/first")
    private val second = Uri.parse("content://gallery/second")
    private lateinit var gateway: FakeShareGateway
    private lateinit var model: ShareFilesModel
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        gateway = FakeShareGateway()
        model = ShareFilesModel(RuntimeEnvironment.getApplication(), gateway)
        store = ViewModelStore().apply { put("share", model) }
        model.initialize(listOf(first, second))
    }

    @After
    fun tearDown() { store.clear(); shadowOf(Looper.getMainLooper()).idle() }

    @Test
    fun manifestResolvesSingleAndMultipleSharesAndOnlyAttachmentsAreRead() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val intent = Intent(action).setType("image/jpeg")
            val matches = RuntimeEnvironment.getApplication().packageManager.queryIntentActivities(intent, 0)
            assertTrue(matches.any { it.activityInfo.name == ShareFilesActivity::class.java.name && it.activityInfo.exported })
        }
        val single = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first).apply {
            clipData = ClipData.newRawUri("preview", second)
        }
        assertEquals(listOf(first), SharedFiles.fromIntent(single))
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
        assertEquals(listOf(first, second), SharedFiles.fromIntent(multiple))
        assertEquals(listOf(first, second), SharedFiles.fromIntent(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            clipData = ClipData.newRawUri("files", first).apply { addItem(ClipData.Item(second)) }
        }))
        assertTrue(SharedFiles.fromIntent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "text only")).isEmpty())
        assertTrue(SharedFiles.fromIntent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/private"))).isEmpty())
        assertTrue(SharedFiles.fromIntent(Intent(Intent.ACTION_VIEW).setData(first)).isEmpty())
    }

    @Test
    fun chosenDesktopRemainsFixedAndEachFileNeedsAccessAndSaveConfirmation() {
        assertFalse(model.canSend)
        model.select("b") // Same display name as "a", different device code.
        model.send()
        model.send() // Double tapping cannot enqueue another copy.
        assertEquals(listOf("b"), gateway.accessRequests)
        gateway.access("a", true)
        assertTrue(gateway.sends.isEmpty())
        gateway.access("b", true)
        assertEquals("b", gateway.sends.single().peerId)
        assertEquals(first, gateway.sends.single().uri)
        gateway.sends[0].done(true, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ShareFilesModel.Phase.CONFIRMING, model.phase)
        assertEquals(0, model.savedCount)
        gateway.saved("a", gateway.sends[0].id)
        assertEquals(0, model.savedCount)
        gateway.saved("b", gateway.sends[0].id)
        assertEquals(1, model.savedCount)
        assertEquals(listOf(second), model.remaining)
        assertEquals(listOf("b", "b"), gateway.accessRequests)
        gateway.access("b", true)
        // A save reply can race ahead of the worker's local completion callback.
        gateway.saved("b", gateway.sends[1].id)
        assertEquals(1, model.savedCount)
        gateway.sends[1].done(true, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ShareFilesModel.Phase.DONE, model.phase)
        assertEquals(2, model.savedCount)
        assertFalse(model.canSend)
    }

    @Test
    fun disconnectRevocationAndLateCallbacksCannotRetargetOrResumeTheTransfer() {
        model.select("b")
        model.send()
        gateway.access("b", true)
        val send = gateway.sends.single()
        assertTrue(send.canContinue())
        gateway.desktops = gateway.desktops.filter { it.id != "b" }
        gateway.changed()
        assertFalse(send.canContinue())
        assertFalse(model.busy)
        assertNull(model.selectedId)
        send.done(true, null)
        gateway.saved("b", send.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, model.savedCount)
        assertEquals(1, gateway.sends.size)
        assertFalse(model.canSend)

        model.select("a")
        model.send()
        gateway.access("a", false)
        assertFalse(model.busy)
        assertEquals(1, gateway.sends.size)
        model.send()
        gateway.access("a", true)
        gateway.access("a", false)
        assertFalse(gateway.sends.last().canContinue())
    }

    @Test
    fun missingApprovalsBlockSendingAndTimeoutsNeverClaimSuccess() {
        model.select("a")
        gateway.allTrusted = false
        gateway.changed()
        model.send()
        assertTrue(gateway.accessRequests.isEmpty())
        gateway.allTrusted = true
        gateway.changed()
        model.send()
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS)
        assertFalse(model.busy)
        assertTrue(model.status.contains("No file-access response"))
        model.send()
        gateway.access("a", true)
        gateway.sends.single().done(true, null)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertEquals(0, model.savedCount)
        assertTrue(model.status.contains("may already be in Downloads"))
    }

    @Test
    fun rotationInitializationDoesNotRestartAndCancellationStopsTheWorker() {
        model.select("a")
        model.send()
        gateway.access("a", true)
        model.initialize(listOf(second)) // A recreated activity uses the retained model.
        assertEquals(listOf(first, second), model.remaining)
        assertEquals(1, gateway.sends.size)
        model.cancel()
        assertFalse(gateway.sends.single().canContinue())
        gateway.sends.single().done(true, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, model.savedCount)
        assertFalse(model.busy)
    }

    @Test
    fun readFailuresSaveErrorsAndRetryKeepPreviouslySavedFilesOutOfTheQueue() {
        model.select("a")
        model.send()
        gateway.access("a", true)
        gateway.sends[0].done(true, null)
        shadowOf(Looper.getMainLooper()).idle()
        gateway.saved("a", gateway.sends[0].id)
        gateway.access("a", true)
        gateway.sends[1].done(false, "Source file permission expired")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, model.savedCount)
        assertEquals(listOf(second), model.remaining)
        assertTrue(model.status.contains("permission expired"))
        model.send()
        gateway.access("a", true)
        assertEquals(second, gateway.sends[2].uri)
        gateway.message(JSONObject().put("type", "fs-saved").put("from", "a")
            .put("xferId", gateway.sends[2].id).put("error", "Disk full"))
        assertFalse(model.busy)
        assertTrue(model.status.contains("Disk full"))
    }

    @Test
    fun processRestartRestoresOnlyUnconfirmedFilesAndRequiresAnExplicitDestination() {
        val restored = ShareFilesModel(RuntimeEnvironment.getApplication(), FakeShareGateway())
        store.put("restored", restored)
        restored.initialize(listOf(second), interrupted = true, confirmedCount = 1)
        assertEquals(listOf(second), restored.remaining)
        assertEquals(1, restored.savedCount)
        assertNull(restored.selectedId)
        assertFalse(restored.canSend)
        assertFalse(restored.busy)
        assertTrue(restored.status.contains("interrupted"))
    }
}

internal class FakeShareGateway : ShareGateway {
    override var configured = true
    override var connected = true
    override var allTrusted = true
    override var desktops = listOf(
        ShareDesktop("a", "Office PC", "AAAA-1111", true),
        ShareDesktop("b", "Office PC", "BBBB-2222", true),
    )
    lateinit var changed: () -> Unit
    lateinit var message: (JSONObject) -> Unit
    val accessRequests = mutableListOf<String>()
    data class Send(val peerId: String, val uri: Uri, val id: Int, val canContinue: () -> Boolean,
                    val done: (Boolean, String?) -> Unit)
    val sends = mutableListOf<Send>()
    override fun observe(changed: () -> Unit, message: (JSONObject) -> Unit) { this.changed = changed; this.message = message }
    override fun close() = Unit
    override fun connect() = Unit
    override fun checkAccess(peerId: String) { accessRequests.add(peerId) }
    override fun nextTransferId() = sends.size + 1
    override fun send(peerId: String, uri: Uri, id: Int, canContinue: () -> Boolean,
                      progress: (String) -> Unit, done: (Boolean, String?) -> Unit) {
        sends.add(Send(peerId, uri, id, canContinue, done))
    }
    fun access(peerId: String, allowed: Boolean) = message(JSONObject()
        .put("type", "file-access").put("from", peerId).put("allowed", allowed))
    fun saved(peerId: String, id: Int) = message(JSONObject().put("type", "fs-saved").put("from", peerId)
        .put("xferId", id).put("path", "C:\\Downloads\\file"))
}
