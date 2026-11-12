package co.remotedesktop

import org.junit.Assert.*
import org.junit.Test

/** Which devices that begin to view are only back, and so neither wake the phone nor are announced. */
class ViewerReturnsTest {
    private val limit = ViewerReturns.WITHIN_MS
    private val second = 1_000L

    @Test
    fun aDeviceThatHasNotViewedBeforeIsNew() {
        val returns = ViewerReturns()
        assertFalse(returns.isBack("pc", now = 0))
        // And so is another one, while the first is away.
        returns.ended("pc", byItself = false, now = second)
        assertFalse(returns.isBack("other-pc", now = 2 * second))
    }

    /** The relay is restarted, or a connection drops: the viewer asks again by itself. */
    @Test
    fun aDeviceWhoseViewWasCutOffIsBack() {
        val returns = ViewerReturns()
        assertFalse(returns.isBack("pc", now = 0))
        returns.ended("pc", byItself = false, now = 10 * second)
        assertTrue(returns.isBack("pc", now = 12 * second))
        // As often as it happens.
        returns.ended("pc", byItself = false, now = 60 * second)
        assertTrue(returns.isBack("pc", now = 60 * second))
        returns.ended("pc", byItself = false, now = 90 * second)
        assertTrue(returns.isBack("pc", now = 90 * second + limit))
    }

    @Test
    fun aViewerThatWasClosedAndIsOpenedAgainIsNew() {
        val returns = ViewerReturns()
        returns.isBack("pc", now = 0)
        returns.ended("pc", byItself = true, now = second)
        assertFalse(returns.isBack("pc", now = 2 * second))

        // A stop-view that arrives after the connection was lost still closes that viewing.
        returns.ended("pc", byItself = false, now = 3 * second)
        returns.ended("pc", byItself = true, now = 4 * second)
        assertFalse(returns.isBack("pc", now = 5 * second))
    }

    /** Its app was closed with the viewer open, and someone opens a viewer the next morning. */
    @Test
    fun aDeviceThatViewsAgainLongAfterwardsIsNew() {
        val returns = ViewerReturns()
        returns.ended("pc", byItself = false, now = second)
        assertFalse(returns.isBack("pc", now = second + limit + 1))
        // Nor is it back the time after that: it was not cut off again.
        assertFalse(returns.isBack("pc", now = second + limit + 2))

        // The same for one that was let run out while others came and went.
        returns.ended("pc", byItself = false, now = 0)
        returns.ended("other-pc", byItself = false, now = limit + second)
        assertFalse(returns.isBack("pc", now = limit + second))
        assertTrue(returns.isBack("other-pc", now = limit + 2 * second))
    }

    @Test
    fun aReturnIsUsedUpByTheViewItStarts() {
        val returns = ViewerReturns()
        returns.ended("pc", byItself = false, now = second)
        assertTrue(returns.isBack("pc", now = 2 * second))
        // That view is closed, and the next is opened by a person.
        returns.ended("pc", byItself = true, now = 3 * second)
        assertFalse(returns.isBack("pc", now = 4 * second))
    }

    /** A clock that has gone backwards says nothing about how long ago it was. */
    @Test
    fun aCutOffInTheFutureIsNotBelieved() {
        val returns = ViewerReturns()
        returns.ended("pc", byItself = false, now = 10 * second)
        assertFalse(returns.isBack("pc", now = 5 * second))
    }
}
