package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What smooth sharing draws into, swapped when the screen turns and closed when sharing stops. Plain JVM. */
class CaptureTargetTest {
    private val made = mutableListOf<String>()
    private val closed = mutableListOf<String>()
    private val target = CaptureTarget({ w, h -> "${w}x$h#${made.size}".also { made += it } }, { closed += it })

    private val open get() = made - closed.toSet()

    @Test
    fun aTurnSwapsTheTargetAndClosesTheOldOne() {
        assertEquals("1080x2340#0", target.open(1080, 2340))
        val events = mutableListOf<String>()
        assertTrue(target.replace(2340, 1080, attach = { events += "attach $it" }, retire = { events += "retire, closed so far: $closed" }))
        // The picture is pointed at the new one before the old one goes, and what was
        // waiting for the old one is let go of while it is still open.
        assertEquals(listOf("attach 2340x1080#1", "retire, closed so far: []"), events)
        assertEquals(listOf("1080x2340#0"), closed)
        assertEquals(listOf("2340x1080#1"), open)
    }

    @Test
    fun theSameSizeAgainChangesNothing() {
        target.open(1080, 2340)
        assertFalse(target.replace(1080, 2340, attach = { fail("nothing to attach") }))
        assertEquals(1, made.size)
        assertEquals(emptyList<String>(), closed)
    }

    @Test
    fun aTargetThatCannotBeAttachedIsClosedAndTheOldOneStays() {
        target.open(1080, 2340)
        repeat(3) {
            try {
                target.replace(2340, 1080, attach = { throw IllegalStateException("the projection stopped") },
                    retire = { fail("nothing was swapped") })
                fail("the failure is the caller's to report")
            } catch (_: IllegalStateException) {
            }
        }
        // Three tries, three made, all three closed again: nothing piles up.
        assertEquals(4, made.size)
        assertEquals(listOf("1080x2340#0"), open)
        // And a later turn still works, from the size that is really in use.
        assertTrue(target.replace(2340, 1080, attach = {}))
        assertEquals(listOf("2340x1080#4"), open)
    }

    @Test
    fun closingClosesTheTargetAfterWhateverDrawsIntoItHasLetGo() {
        target.open(1080, 2340)
        target.replace(2340, 1080, attach = {})
        var openAtRelease: List<String>? = null
        target.close { openAtRelease = open }
        assertEquals(listOf("2340x1080#1"), openAtRelease)
        assertEquals(emptyList<String>(), open)
        target.close { fail("closed once") }
    }

    @Test
    fun nothingIsMadeOnceSharingHasStopped() {
        target.open(1080, 2340)
        target.close()
        assertFalse(target.replace(2340, 1080, attach = { fail("too late") }))
        assertNull(target.open(1080, 2340))
        assertEquals(1, made.size)
        assertEquals(emptyList<String>(), open)
    }

    @Test
    fun aTurnBeforeAnythingWasOpenedChangesNothing() {
        assertFalse(target.replace(2340, 1080, attach = { fail("nothing to swap") }))
        assertEquals(0, made.size)
    }

    @Test
    fun aTurnAndTheEndOfSharingTakeTurns() {
        target.open(1080, 2340)
        val attaching = CountDownLatch(1)
        val finish = CountDownLatch(1)
        // The capture thread is in the middle of a swap...
        val turn = thread {
            target.replace(2340, 1080, attach = {
                attaching.countDown()
                finish.await(5, TimeUnit.SECONDS)
            })
        }
        assertTrue(attaching.await(5, TimeUnit.SECONDS))
        // ...when the main thread stops sharing. It has to wait for the swap.
        var openAtRelease: List<String>? = null
        val stop = thread { target.close { openAtRelease = open } }
        stop.join(200)
        assertTrue("Closing waits for a swap that is under way", stop.isAlive)
        finish.countDown()
        turn.join(5000)
        stop.join(5000)
        assertEquals("The swap had finished before anything was let go of", listOf("2340x1080#1"), openAtRelease)
        assertEquals(emptyList<String>(), open)
    }
}
