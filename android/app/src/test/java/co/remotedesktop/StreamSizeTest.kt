package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Test

/** The size a phone's screen is sent at, whichever way the phone is turned. Plain JVM. */
class StreamSizeTest {
    @Test
    fun aScreenWithinTheLimitIsSentAsItIs() {
        assertEquals(1080 to 2340, StreamSize.of(1080, 2340))
        assertEquals(720 to 1600, StreamSize.of(720, 1600))
    }

    @Test
    fun aLargerScreenIsMadeSmallerInItsOwnShape() {
        assertEquals(1080 to 2400, StreamSize.of(1440, 3200))
    }

    @Test
    fun aTurnedPhoneIsSentAsSharpAsAnUprightOne() {
        // The same pixels, turned: not 1080 x 498, which a limit on the width gave.
        assertEquals(2340 to 1080, StreamSize.of(2340, 1080))
        assertEquals(2400 to 1080, StreamSize.of(3200, 1440))
        for ((w, h) in listOf(1080 to 2340, 1440 to 3200, 1600 to 2560, 720 to 1600)) {
            val (uprightW, uprightH) = StreamSize.of(w, h)
            assertEquals("$w x $h turned", uprightH to uprightW, StreamSize.of(h, w))
        }
    }

    @Test
    fun aTabletOnItsSideKeepsItsShorterSideWithinTheLimit() {
        assertEquals(1728 to 1080, StreamSize.of(2560, 1600))
    }

    @Test
    fun aSizeIsNeverZero() {
        assertEquals(1 to 1, StreamSize.of(0, 0))
        assertEquals(2000 to 1, StreamSize.of(4000, 2, maxShortSide = 1))
    }
}
