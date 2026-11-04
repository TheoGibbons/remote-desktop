package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which screen pictures a phone sends while sharing its screen. Plain JVM. */
class FramePacerTest {
    private val released = mutableListOf<String>()
    // At most 10 pictures a second; while the queue is full, look again every 100 ms.
    private val pacer = FramePacer<String>(minIntervalMs = 100, retryMs = 100) { released += it }

    @Test
    fun aPictureTooSoonAfterTheLastIsKeptAndTheNewestGoesOutLater() {
        assertEquals("a", pacer.offer("a", now = 1_000, hasRoom = true))
        assertNull(pacer.offer("b", now = 1_050, hasRoom = true))
        assertEquals(50L, pacer.retryDelay(now = 1_050, hasRoom = true))
        // A newer picture replaces the kept one.
        assertNull(pacer.offer("c", now = 1_080, hasRoom = true))
        assertEquals(listOf("b"), released)
        assertNull(pacer.retry(now = 1_099, hasRoom = true))
        // The screen then stops changing, and the last picture still goes out.
        assertEquals("c", pacer.retry(now = 1_100, hasRoom = true))
        assertNull(pacer.retryDelay(now = 1_100, hasRoom = true))
    }

    @Test
    fun aFullSendQueueHoldsTheNewestPictureUntilItDrains() {
        assertNull(pacer.offer("a", now = 1_000, hasRoom = false))
        assertEquals(100L, pacer.retryDelay(now = 1_000, hasRoom = false))
        assertNull(pacer.retry(now = 1_100, hasRoom = false))
        assertNull(pacer.offer("b", now = 1_150, hasRoom = false))
        assertEquals("b", pacer.retry(now = 1_250, hasRoom = true))
        assertEquals(listOf("a"), released)
    }

    @Test
    fun clearingReleasesTheKeptPicture() {
        pacer.offer("a", now = 1_000, hasRoom = true)
        assertNull(pacer.offer("b", now = 1_010, hasRoom = true))
        pacer.clear()
        assertEquals(listOf("b"), released)
        assertNull(pacer.retry(now = 2_000, hasRoom = true))
    }
}
