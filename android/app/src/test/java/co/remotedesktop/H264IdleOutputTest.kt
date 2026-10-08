package co.remotedesktop

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.os.Looper
import android.view.Surface
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
        viewer?.let { pause(it) }
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
    fun fullInputQueueResetsDecoderInsteadOfSilentlyLosingAnInterFrame() {
        val activity = viewer()
        DelayedOutputCodec.inputAvailable = false
        deliver(activity)
        assertNull(field("decoder").get(activity))
        assertFalse(field("haveKeyframe").getBoolean(activity))
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
