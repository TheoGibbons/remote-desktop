package co.remotedesktop

import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * The capture service taking and letting go of the screen as [KeepAwake]
 * says. The rules are in KeepAwakeTest; this is the wake lock itself. The
 * service is made without Android's screen sharing behind it, and stood in
 * as the one that is running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmoothScreenHoldTest {
    private val instanceField = ScreenCaptureService::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private lateinit var controller: ServiceController<ScreenCaptureService>

    @Before
    fun setup() {
        ShadowPowerManager.clearWakeLocks()
        controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        instanceField.set(null, controller.get())
        ScreenCaptureService.awake = KeepAwake().also { it.sharing(true, SystemClock.uptimeMillis()) }
    }

    @After
    fun cleanup() {
        instanceField.set(null, null)
        ScreenCaptureService.awake = KeepAwake()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun later(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    private val held: Boolean get() = ShadowPowerManager.getLatestWakeLock()?.isHeld == true

    private fun acted(type: String, action: String? = null) {
        val msg = JSONObject().put("type", type)
        if (action != null) msg.put("action", action)
        ScreenCaptureService.viewerActed(msg)
    }

    @Test
    fun theScreenIsHeldWhileSomeoneViewsAndLetGoWhenTheyStop() {
        assertFalse(held)
        ScreenCaptureService.viewerStarted("pc")
        idle()
        assertTrue(held)
        ScreenCaptureService.viewerStopped("pc", byItself = true)
        idle()
        assertFalse(held)
    }

    /** The PC's connection drops and it asks again by itself, with nobody at it. */
    @Test
    fun aViewerThatOnlyComesBackTakesNoNewHold() {
        ScreenCaptureService.viewerStarted("pc")
        later(KeepAwake.IDLE_MS - 60_000)
        ScreenCaptureService.viewerStopped("pc", byItself = false)
        idle()
        assertFalse(held)
        ScreenCaptureService.viewerStarted("pc")
        idle()
        // Held for what was left of the ten minutes, not for ten more.
        assertTrue(held)
        assertEquals(60_000L, ScreenCaptureService.awake.holdFor(SystemClock.uptimeMillis()))

        later(60_000)
        ScreenCaptureService.viewerStopped("pc", byItself = false)
        ScreenCaptureService.viewerStarted("pc")
        idle()
        assertFalse("coming back by itself held the screen on past the ten idle minutes", held)
    }

    @Test
    fun aViewersLockLetsGoBeforeTheScreenIsTurnedOffAndItsNextInputTakesHold() {
        ScreenCaptureService.viewerStarted("pc")
        idle()
        assertTrue(held)
        acted("lock")
        // At once, on the thread the message came in on: the lock is carried out right after.
        assertFalse(held)
        idle()
        assertFalse(held)

        // Moving a finger that is already down, and the menu's questions, are not input.
        acted("touch", "move")
        acted("get-phone-state")
        idle()
        assertFalse(held)
        // Nor is its PC reconnecting by itself.
        ScreenCaptureService.viewerStopped("pc", byItself = false)
        ScreenCaptureService.viewerStarted("pc")
        idle()
        assertFalse(held)

        acted("tap")
        idle()
        assertTrue(held)
    }

    @Test
    fun nothingIsTakenOnceSharingHasEnded() {
        ScreenCaptureService.viewerStarted("pc")
        idle()
        assertTrue(held)
        // A viewer's tap is on its way to the main thread as sharing stops.
        acted("tap")
        controller.destroy()
        idle()
        assertFalse("the screen was taken again by a service that is gone, with nothing left to let it go", held)
    }
}
