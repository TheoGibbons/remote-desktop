package co.remotedesktop

import android.graphics.Rect
import android.graphics.Matrix
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.os.Looper
import android.view.Surface
import android.util.Base64
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaCodec
import java.nio.ByteBuffer
import java.time.Duration

/** Simulates hardware output that becomes ready AFTER the last network input. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [DelayedOutputCodec::class])
@LooperMode(LooperMode.Mode.PAUSED)
class H264IdleOutputTest {
    private var viewer: ViewerActivity? = null

    @Before
    fun setup() {
        DelayedOutputCodec.ready = false
        DelayedOutputCodec.inputAvailable = true
        DelayedOutputCodec.queuedPts = -1L
        DelayedOutputCodec.renderedPts.clear()
        // Keep this video test independent of AndroidKeyStore authentication.
        ConnectionManager::class.java.getDeclaredField("appContext").apply { isAccessible = true }
            .set(ConnectionManager, RuntimeEnvironment.getApplication())
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
    }

    @After
    fun teardown() {
        viewer?.let {
            pause(it)
            ViewerActivity::class.java.getDeclaredMethod("onDestroy")
                .apply { isAccessible = true }.invoke(it)
        }
        ConnectionManager.peers.clear()
        ConnectionManager::class.java.getDeclaredField("appContext").apply { isAccessible = true }
            .set(ConnectionManager, null)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun field(name: String) = ViewerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun pause(activity: ViewerActivity) {
        ViewerActivity::class.java.getDeclaredMethod("onPause")
            .apply { isAccessible = true }.invoke(activity)
    }

    private fun viewer(): ViewerActivity {
        val activity = Robolectric.buildActivity(ViewerActivity::class.java).setup().get()
        viewer = activity
        field("videoSurface").set(activity, Surface(SurfaceTexture(0)))
        return activity
    }

    private fun frame(seq: Int = 1, keyframe: Boolean = true): ByteArray =
        ByteBuffer.allocate(19).apply {
            put(4.toByte())
            putInt(seq)
            put(if (keyframe) 1.toByte() else 0.toByte())
            putShort(100); putShort(80)
            putShort(20); putShort(30); putShort(200); putShort(160)
            put(0x65.toByte())
        }.array()

    private fun deliver(activity: ViewerActivity, data: ByteArray = frame()) {
        ViewerActivity::class.java.getDeclaredMethod("handleH264", ByteArray::class.java)
            .apply { isAccessible = true }.invoke(activity, data)
    }

    @Test
    fun backClosesStatsBeforeExitingViewer() {
        val activity = viewer()
        ViewerActivity::class.java.getDeclaredMethod("showDiagnostics")
            .apply { isAccessible = true }.invoke(activity)
        val panel = field("diagnosticsPanel").get(activity) as android.view.View
        assertEquals(android.view.View.VISIBLE, panel.visibility)

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(android.view.View.GONE, panel.visibility)
        assertNull(field("diagnosticsUpdater").get(activity))
        assertFalse(activity.isFinishing)

        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun finalFrameRendersWithoutAnotherNetworkPacketAndKeepsItsRegion() {
        val activity = viewer()
        deliver(activity)
        assertTrue(DelayedOutputCodec.renderedPts.isEmpty())
        idle(48)
        assertTrue(DelayedOutputCodec.renderedPts.isEmpty())

        DelayedOutputCodec.ready = true
        idle(16)
        assertEquals(listOf(33_333L), DelayedOutputCodec.renderedPts)
        assertEquals(Rect(20, 30, 220, 190), field("latchedRegion").get(activity))
        assertTrue((field("pendingRegions").get(activity) as Map<*, *>).isEmpty())
    }

    @Test
    fun pauseCancelsPendingOutputAndLatePacketsCannotRestartTheDecoder() {
        val activity = viewer()
        deliver(activity)
        pause(activity)
        DelayedOutputCodec.ready = true
        idle(100)
        deliver(activity, frame(2))
        assertTrue(DelayedOutputCodec.renderedPts.isEmpty())
        assertNull(field("decoder").get(activity))
    }

    @Test
    fun newViewerOpensInLandscapeAndResumeResendsAnUnchangedViewport() {
        val activity = viewer()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        val screen = field("screen").get(activity) as RemoteScreenView
        val socketField = ConnectionManager::class.java.getDeclaredField("ws").apply { isAccessible = true }
        val keyField = ConnectionManager::class.java.getDeclaredField("encKey").apply { isAccessible = true }
        val previousKey = keyField.get(ConnectionManager)
        val socket = RecordingSocket(ByteArray(32) { 7 })
        socketField.set(ConnectionManager, socket)
        keyField.set(ConnectionManager, socket.key)
        try {
            screen.setDesktopSize(4000, 3200)
            idle(200)
            val before = socket.messages.last { it.getString("type") == "view-region" }.toString()
            assertTrue(JSONObject(before).getBoolean("h264"))
            socket.messages.clear()
            pause(activity)
            idle(200)
            assertEquals(listOf("stop-view"), socket.messages.map { it.getString("type") })
            socket.messages.clear()
            ViewerActivity::class.java.getDeclaredMethod("onResume")
                .apply { isAccessible = true }.invoke(activity)
            assertEquals(listOf("view-region", "start-view", "get-privacy-state"),
                socket.messages.map { it.getString("type") })
            assertEquals(before, socket.messages.first().toString())

            // Changing only decoder capability must also bypass deduplication.
            socket.messages.clear()
            field("h264Enabled").setBoolean(activity, false)
            screen.resendViewport(immediate = true)
            assertEquals(1, socket.messages.size)
            assertFalse(socket.messages.single().getBoolean("h264"))
        } finally {
            socketField.set(ConnectionManager, null)
            keyField.set(ConnectionManager, previousKey)
        }
    }

    @Test
    fun pauseKeepsLastVideoFrameAndDetachedTextureUntilViewerIsDestroyed() {
        val activity = viewer()
        val screen = field("screen").get(activity) as RemoteScreenView
        screen.setDesktopSize(4000, 3200)
        idle(200)
        screen.setVideoFrame(100, 80, Rect(20, 30, 220, 190))
        pause(activity)
        val videoWidth = RemoteScreenView::class.java.getDeclaredField("videoW").apply { isAccessible = true }
        assertEquals(100, videoWidth.getInt(screen))
        val texture = object : SurfaceTexture(0) {
            var releases = 0
            override fun release() {
                releases++
                super.release()
            }
        }
        val video = field("videoView").get(activity) as android.view.TextureView
        assertFalse(video.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture))
        assertSame(texture, field("retainedVideoTexture").get(activity))
        ViewerActivity::class.java.getDeclaredMethod("onDestroy")
            .apply { isAccessible = true }.invoke(activity)
        viewer = null
        assertNull(field("retainedVideoTexture").get(activity))
        assertEquals(1, texture.releases)
    }

    @Test
    fun reattachedVideoTextureKeepsItsFrameAndReappliesTheTransform() {
        val activity = viewer()
        val screen = field("screen").get(activity) as RemoteScreenView
        val video = field("videoView").get(activity) as android.view.TextureView
        screen.setDesktopSize(4000, 3200)
        idle(200)
        screen.setVideoFrame(100, 80, Rect(800, 600, 2800, 2200))
        val before = FloatArray(9)
        video.getTransform(Matrix()).getValues(before)
        pause(activity)
        val texture = SurfaceTexture(0)
        val listener = video.surfaceTextureListener!!
        assertFalse(listener.onSurfaceTextureDestroyed(texture))
        video.setTransform(Matrix())
        listener.onSurfaceTextureAvailable(SurfaceTexture(0), video.width, video.height)
        assertSame(texture, video.surfaceTexture)
        assertNull(field("retainedVideoTexture").get(activity))
        val after = FloatArray(9)
        video.getTransform(Matrix()).getValues(after)
        assertArrayEquals(before, after, .00001f)
        // Hand ownership back to the activity so teardown also covers release.
        assertFalse(listener.onSurfaceTextureDestroyed(texture))
    }

    @Test
    fun fullInputQueueResetsDecoderInsteadOfSilentlyLosingAnInterFrame() {
        val activity = viewer()
        DelayedOutputCodec.inputAvailable = false
        deliver(activity)
        assertNull(field("decoder").get(activity))
        assertFalse(field("haveKeyframe").getBoolean(activity))
    }

    @Test
    fun repeatedDecoderStallsFallBackToJpegAndPauseCancelsRecovery() {
        val activity = viewer()
        deliver(activity)
        idle(2_032)
        assertTrue(field("videoRecovering").getBoolean(activity))
        deliver(activity, frame(2))
        idle(2_032)
        assertFalse(field("h264Enabled").getBoolean(activity))
        pause(activity)
        assertFalse(field("videoRecovering").getBoolean(activity))
        idle(3_000)
        assertNull(field("decoder").get(activity))
    }

    @Test
    fun sequenceGapDoesNotFeedDependentFramesToTheDecoder() {
        val activity = viewer()
        deliver(activity)
        DelayedOutputCodec.ready = true
        idle(16)
        deliver(activity, frame(3, keyframe = false))
        assertNull(field("decoder").get(activity))
        assertFalse(field("haveKeyframe").getBoolean(activity))
        assertEquals(listOf(33_333L), DelayedOutputCodec.renderedPts)
    }

    @Test
    fun stuckOutputTimesOutAndCanRecoverOnAKeyframe() {
        val activity = viewer()
        deliver(activity)
        idle(2_032)
        assertNull(field("decoder").get(activity))
        deliver(activity, frame(2))
        DelayedOutputCodec.ready = true
        idle(16)
        assertEquals(listOf(66_666L), DelayedOutputCodec.renderedPts)
    }

    private class RecordingSocket(val key: ByteArray) : WebSocket {
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

@Implements(MediaCodec::class)
class DelayedOutputCodec : ShadowMediaCodec() {
    @Implementation
    override fun native_dequeueInputBuffer(timeoutUs: Long): Int = if (inputAvailable) 0 else -1

    @Implementation
    override fun getBuffer(input: Boolean, index: Int): ByteBuffer = ByteBuffer.allocate(1024)

    @Implementation
    override fun native_queueInputBuffer(index: Int, offset: Int, size: Int, pts: Long, flags: Int) {
        queuedPts = pts
    }

    @Implementation
    override fun native_dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
        if (!ready || queuedPts < 0) return MediaCodec.INFO_TRY_AGAIN_LATER
        info.set(0, 1, queuedPts, 0)
        return 0
    }

    @Implementation
    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        if (render) renderedPts.add(queuedPts)
        queuedPts = -1L
    }

    companion object {
        var ready = false
        var inputAvailable = true
        var queuedPts = -1L
        val renderedPts = mutableListOf<Long>()
    }
}
