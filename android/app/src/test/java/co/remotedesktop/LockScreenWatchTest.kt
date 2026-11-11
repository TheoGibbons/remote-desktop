package co.remotedesktop

import co.remotedesktop.LockScreenReader.Area
import co.remotedesktop.LockScreenReader.Entry
import co.remotedesktop.LockScreenReader.Found
import co.remotedesktop.LockScreenReader.Read
import org.junit.Assert.*
import org.junit.Test

/** When the lock screen is read again, and what viewers are told of what was
 *  read. Bookkeeping with the clock passed in: no phone and no Android here. */
class LockScreenWatchTest {
    private val grid = Entry.Pattern(Area(0.1454, 0.5141, 0.7083, 0.3269))
    private val gridMidSlide = Entry.Pattern(Area(0.1454, 0.6, 0.7083, 0.3269))

    private fun complete(entry: Entry? = null, message: String? = null) = Read.Complete(Found(entry, message))

    /** Reads when the watch says to, giving it [result]; returns whether viewers have news. */
    private fun LockScreenWatch.readAt(at: Long, result: Read): Boolean {
        assertEquals("due", at, nextDue)
        reading(at)
        return read(result, at)
    }

    /** A watch that has found the grid and told viewers of it. */
    private fun settled(message: String? = "Draw your pattern"): LockScreenWatch = LockScreenWatch().apply {
        start(0)
        assertFalse(readAt(0, complete(grid, message)))
        assertTrue(readAt(250, complete(grid, message)))
        assertEquals(Found(grid, message), published)
        assertNull(nextDue)
    }

    @Test
    fun itReadsAtOnceWhenItStartsAndWhenAWindowChanges() {
        val watch = LockScreenWatch()
        assertNull("Nothing to read until reading starts", watch.nextDue)
        watch.start(1_000)
        assertFalse(watch.readAt(1_000, complete()))
        assertNull("The lock screen, with nothing to enter: nothing more to do until something changes", watch.nextDue)
        assertEquals(Found.NOTHING, watch.published)

        watch.windowChanged(5_000)
        assertEquals(5_000L, watch.nextDue)
        // Several changes before the read is made are one read.
        watch.windowChanged(5_010)
        watch.contentChanged(5_020)
        assertEquals(5_000L, watch.nextDue)
    }

    @Test
    fun aGridIsToldOnceTwoReadsAgreeOnWhereItIs() {
        val watch = LockScreenWatch()
        watch.start(0)
        // The unlock screen slides in; the first read catches it on the way.
        assertFalse(watch.readAt(0, complete(gridMidSlide, "Draw your pattern")))
        assertEquals("Neither the grid nor its message yet", Found.NOTHING, watch.published)
        assertFalse(watch.readAt(250, complete(grid, "Draw your pattern")))
        assertTrue(watch.readAt(500, complete(grid, "Draw your pattern")))
        assertEquals(Found(grid, "Draw your pattern"), watch.published)
        assertNull(watch.nextDue)
    }

    @Test
    fun aNewMessageOnTheSameGridIsToldAtOnce() {
        val watch = settled()
        watch.contentChanged(5_000)
        assertTrue(watch.readAt(5_000, complete(grid, "Incorrect pattern drawn")))
        assertEquals("Incorrect pattern drawn", watch.published.message)
        // And a message with nothing recognisable under it is still told.
        val bare = LockScreenWatch().apply { start(0) }
        assertTrue(bare.readAt(0, complete(null, "Enter your backup PIN")))
        assertEquals(Found(null, "Enter your backup PIN"), bare.published)
    }

    @Test
    fun onlyAReadFromEndToEndTakesTheGridAway() {
        val watch = settled()
        watch.windowChanged(5_000)
        // No window for a moment, then one that broke off: nothing is known, so nothing changes.
        assertFalse(watch.readAt(5_000, Read.Unknown))
        assertEquals(Found(grid, "Draw your pattern"), watch.published)
        assertFalse(watch.readAt(5_300, Read.Unknown))
        assertFalse(watch.readAt(5_600, Read.Unknown))
        assertFalse(watch.readAt(5_900, Read.Unknown))
        assertNull("Tried three more times, then left until something changes", watch.nextDue)
        assertEquals(Found(grid, "Draw your pattern"), watch.published)

        // Back on the lock screen, read whole: the grid goes at once, with no second look.
        watch.windowChanged(8_000)
        assertTrue(watch.readAt(8_000, complete()))
        assertEquals(Found.NOTHING, watch.published)
        assertNull(watch.nextDue)

        // A failed read that then succeeds is used, and the count starts again.
        watch.windowChanged(9_000)
        assertFalse(watch.readAt(9_000, Read.Unknown))
        assertFalse(watch.readAt(9_300, complete(grid)))
        assertTrue(watch.readAt(9_550, complete(grid)))
        watch.windowChanged(10_000)
        assertFalse(watch.readAt(10_000, Read.Unknown))
        assertEquals(10_300L, watch.nextDue)
    }

    @Test
    fun changesInsideAWindowAreReadAtMostOnceASecondAndLessWhenNothingIsNew() {
        val watch = settled()   // last read at 250
        // A countdown: the message changes every second, and each change is read.
        watch.contentChanged(400)
        assertEquals("Not within a second of the last read", 1_250L, watch.nextDue)
        watch.contentChanged(900)
        assertEquals(1_250L, watch.nextDue)
        assertTrue(watch.readAt(1_250, complete(grid, "Try again in 29 seconds")))
        watch.contentChanged(1_300)
        assertTrue(watch.readAt(2_250, complete(grid, "Try again in 28 seconds")))

        // Then something keeps changing that is none of our business (a clock, an animation).
        watch.contentChanged(2_300)
        assertFalse(watch.readAt(3_250, complete(grid, "Try again in 28 seconds")))
        watch.contentChanged(3_300)
        assertEquals("Nothing new last time: twice as long", 5_250L, watch.nextDue)
        assertFalse(watch.readAt(5_250, complete(grid, "Try again in 28 seconds")))
        watch.contentChanged(5_300)
        assertFalse(watch.readAt(9_250, complete(grid, "Try again in 28 seconds")))
        watch.contentChanged(9_300)
        assertFalse(watch.readAt(17_250, complete(grid, "Try again in 28 seconds")))
        watch.contentChanged(17_300)
        assertEquals("And no longer than eight seconds", 25_250L, watch.nextDue)

        // A read that does find something new is back to once a second.
        assertTrue(watch.readAt(25_250, complete(grid, "Draw your pattern")))
        watch.contentChanged(25_300)
        assertEquals(26_250L, watch.nextDue)
        // A change long after the last read is read at once.
        assertTrue(watch.readAt(26_250, complete(grid, "x")))
        watch.contentChanged(60_000)
        assertEquals(60_000L, watch.nextDue)
    }

    @Test
    fun aWindowChangeIsReadAtOnceHoweverQuietThingsHadGot() {
        val watch = settled()
        watch.contentChanged(300)
        assertFalse(watch.readAt(1_250, complete(grid, "Draw your pattern")))
        watch.contentChanged(1_300)
        assertEquals(3_250L, watch.nextDue)
        watch.windowChanged(1_400)
        assertEquals(1_400L, watch.nextDue)
        assertTrue(watch.readAt(1_400, complete()))
        // And changes inside a window are back to once a second.
        watch.contentChanged(1_500)
        assertEquals(2_400L, watch.nextDue)
    }

    @Test
    fun startingOverForgetsEverything() {
        val watch = settled()
        watch.start(50_000)
        assertEquals(Found.NOTHING, watch.published)
        assertEquals(50_000L, watch.nextDue)
        // The grid has to be seen twice again.
        assertFalse(watch.readAt(50_000, complete(grid)))
        assertTrue(watch.readAt(50_250, complete(grid)))
    }
}
