package co.remotedesktop

import org.junit.Assert.*
import org.junit.Test

/** When the screen is held on for a phone viewed in smooth sharing, and when it is let go. */
class KeepAwakeTest {
    private val idle = KeepAwake.IDLE_MS

    @Test
    fun theScreenIsHeldOnlyWhileViewed() {
        val awake = KeepAwake()
        assertNull(awake.holdFor(now = 0))
        // Input with nobody viewing holds nothing.
        awake.input(now = 5)
        assertNull(awake.holdFor(now = 5))

        awake.viewed(true, now = 1_000)
        assertEquals(idle, awake.holdFor(now = 1_000))
        awake.viewed(false, now = 2_000)
        assertNull(awake.holdFor(now = 2_000))
    }

    @Test
    fun itIsLetGoAfterTenIdleMinutesAndTakenAgainOnInput() {
        val awake = KeepAwake()
        awake.viewed(true, now = 0)
        assertEquals(1L, awake.holdFor(now = idle - 1))
        assertNull(awake.holdFor(now = idle))
        // Being viewed still, again and again, is not input.
        awake.viewed(true, now = idle + 1)
        assertNull(awake.holdFor(now = idle + 1))

        awake.input(now = idle + 5_000)
        assertEquals(idle, awake.holdFor(now = idle + 5_000))
        assertEquals(idle - 60_000, awake.holdFor(now = idle + 65_000))
    }

    @Test
    fun aViewersLockLetsGoUntilItsNextInput() {
        val awake = KeepAwake()
        awake.viewed(true, now = 0)
        awake.locked()
        assertNull(awake.holdFor(now = 1))
        awake.viewed(true, now = 2)
        assertNull(awake.holdFor(now = 2))
        awake.input(now = 3)
        assertEquals(idle, awake.holdFor(now = 3))
    }
}
