package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Test

/** The size smooth sharing streams a screen at, whichever way the phone is turned. Plain JVM. */
class StreamSizeTest {
    private fun size(width: Int, height: Int) = ScreenCaptureService.streamSize(width, height, maxWidth = 1080)

    @Test
    fun aScreenNoWiderThanTheLimitIsSentAsItIs() {
        assertEquals(1080 to 2340, size(1080, 2340))
        assertEquals(720 to 1600, size(720, 1600))
    }

    @Test
    fun aWiderScreenIsMadeSmallerInItsOwnShape() {
        assertEquals(1080 to 2400, size(1440, 3200))
    }

    @Test
    fun aTurnedPhoneGetsATurnedPicture() {
        // The same phone on its side: wider than the limit now, so smaller, and still its own shape.
        // It is not the upright size turned round, which is why the stream is rebuilt.
        assertEquals(1080 to 498, size(2340, 1080))
    }

    @Test
    fun aSizeIsNeverZero() {
        assertEquals(1 to 1, size(0, 0))
        assertEquals(1080 to 1, size(100_000, 50))
    }
}
