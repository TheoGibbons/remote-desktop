package co.remotedesktop

import org.junit.Assert.*
import org.junit.Test

/** When the screen is held on for a phone viewed in smooth sharing, and when it is let go. */
class KeepAwakeTest {
    private val idle = KeepAwake.IDLE_MS
    private val minute = 60_000L

    /** Smooth sharing on since time 0, and nobody viewing yet. */
    private fun sharing() = KeepAwake().also { it.sharing(true, now = 0) }

    @Test
    fun theScreenIsHeldOnlyWhileViewedInSmoothSharing() {
        val awake = KeepAwake()
        assertNull(awake.holdFor(now = 0))
        // Viewed, but not in smooth sharing: ordinary viewing holds nothing.
        awake.started("pc", now = 5)
        awake.input(now = 6)
        assertNull(awake.holdFor(now = 6))

        // Sharing starts under the viewer: someone said yes to Android's prompt.
        awake.sharing(true, now = 1_000)
        assertEquals(idle, awake.holdFor(now = 1_000))
        awake.stopped("pc", byItself = true)
        assertNull(awake.holdFor(now = 2_000))

        // Sharing with nobody viewing, and input from nobody, hold nothing.
        awake.input(now = 3_000)
        assertNull(awake.holdFor(now = 3_000))
        awake.started("pc", now = 4_000)
        assertEquals(idle, awake.holdFor(now = 4_000))
        awake.sharing(false, now = 5_000)
        assertNull(awake.holdFor(now = 5_000))
    }

    @Test
    fun itIsLetGoAfterTenIdleMinutesAndTakenAgainOnInput() {
        val awake = sharing()
        awake.started("pc", now = 0)
        assertEquals(1L, awake.holdFor(now = idle - 1))
        assertNull(awake.holdFor(now = idle))

        awake.input(now = idle + 5_000)
        assertEquals(idle, awake.holdFor(now = idle + 5_000))
        assertEquals(idle - minute, awake.holdFor(now = idle + 5_000 + minute))
    }

    /** A forgotten viewer on a link that drops every few minutes: its PC asks again by itself each time. */
    @Test
    fun aViewerThatOnlyComesBackDoesNotStartTheTenMinutesAgain() {
        val awake = sharing()
        awake.started("pc", now = 0)
        var now = 0L
        while (now < idle - 3 * minute) {
            now += 3 * minute
            awake.stopped("pc", byItself = false)
            assertNull(awake.holdFor(now))
            now += 2_000
            awake.started("pc", now)
            assertEquals("back at $now", idle - now, awake.holdFor(now))
        }
        // The ten minutes run out as if it had never been away, and coming back after that changes nothing.
        assertNull(awake.holdFor(now = idle))
        awake.stopped("pc", byItself = false)
        awake.started("pc", now = idle + minute)
        assertNull(awake.holdFor(now = idle + minute))
        // However long it was away.
        awake.stopped("pc", byItself = false)
        awake.started("pc", now = 50 * idle)
        assertNull(awake.holdFor(now = 50 * idle))
        // Someone at it does.
        awake.input(now = 50 * idle + 1)
        assertEquals(idle, awake.holdFor(now = 50 * idle + 1))
    }

    @Test
    fun aViewerThatWasClosedAndIsOpenedAgainDoes() {
        val awake = sharing()
        awake.started("pc", now = 0)
        awake.stopped("pc", byItself = true)
        awake.started("pc", now = 8 * minute)
        assertEquals(idle, awake.holdFor(now = 8 * minute))

        // A stop-view that arrives after the connection was lost still closes that viewing.
        awake.stopped("pc", byItself = false)
        awake.stopped("pc", byItself = true)
        awake.started("pc", now = 30 * minute)
        assertEquals(idle, awake.holdFor(now = 30 * minute))
    }

    @Test
    fun aDeviceThatHasNotViewedBeforeDoes() {
        val awake = sharing()
        awake.started("pc", now = 0)
        awake.started("other-pc", now = 9 * minute)
        assertEquals(idle, awake.holdFor(now = 9 * minute))
        // One of the two leaving lets nothing go while the other views.
        awake.stopped("pc", byItself = true)
        assertEquals(idle - minute, awake.holdFor(now = 10 * minute))
    }

    @Test
    fun aViewersLockLetsGoUntilSomeoneDoesSomething() {
        val awake = sharing()
        awake.started("pc", now = 0)
        awake.locked()
        assertNull(awake.holdFor(now = 1))
        // Its PC reconnecting by itself does not undo the lock.
        awake.stopped("pc", byItself = false)
        awake.started("pc", now = 2)
        assertNull(awake.holdFor(now = 2))
        awake.input(now = 3)
        assertEquals(idle, awake.holdFor(now = 3))
    }
}
