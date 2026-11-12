package co.remotedesktop

import org.junit.Assert.*
import org.junit.Test

/** Which devices that begin to view are only back, and so neither wake the phone nor are announced. */
class ViewerReturnsTest {
    private val limit = ViewerReturns.WITHIN_MS
    private val second = 1_000L

    /** A viewer that asks again by itself and says so, as `resume: true` in its `start-view`. */
    private fun ViewerReturns.resumes(device: String, now: Long) = isBack(device, now, resume = true)

    /** A viewer that someone opened: its `start-view` has no mark. */
    private fun ViewerReturns.opens(device: String, now: Long) = isBack(device, now, resume = false)

    @Test
    fun aDeviceThatHasNotViewedBeforeIsNewWhateverItSays() {
        val returns = ViewerReturns()
        assertFalse(returns.resumes("pc", now = 0))
        // And so is another one, while the first is away.
        returns.ended("pc", byItself = false, now = second)
        assertFalse(returns.resumes("other-pc", now = 2 * second))
    }

    /** The relay is restarted, or a connection drops: the viewer asks again by itself. */
    @Test
    fun aDeviceWhoseViewWasCutOffAndThatSaysItIsResumingIsBack() {
        val returns = ViewerReturns()
        assertFalse(returns.opens("pc", now = 0))
        returns.ended("pc", byItself = false, now = 10 * second)
        assertTrue(returns.resumes("pc", now = 12 * second))
        // As often as it happens.
        returns.ended("pc", byItself = false, now = 60 * second)
        assertTrue(returns.resumes("pc", now = 60 * second))
        returns.ended("pc", byItself = false, now = 90 * second)
        assertTrue(returns.resumes("pc", now = 90 * second + limit))
    }

    /**
     * The viewer was closed while it could reach nobody, or its app was
     * closed, or it gave up waiting, or it is an app from before the mark:
     * no `stop-view` came, and someone opens a viewer a minute later.
     */
    @Test
    fun aViewThatIsNotMarkedIsAlwaysNew() {
        val returns = ViewerReturns()
        returns.ended("pc", byItself = false, now = second)
        assertFalse(returns.opens("pc", now = 2 * second))
        // And what was remembered is used up by it: a mark right after means nothing.
        assertFalse(returns.resumes("pc", now = 3 * second))
    }

    @Test
    fun aViewerThatWasClosedAndIsOpenedAgainIsNew() {
        val returns = ViewerReturns()
        returns.opens("pc", now = 0)
        returns.ended("pc", byItself = true, now = second)
        assertFalse(returns.resumes("pc", now = 2 * second))

        // A stop-view that arrives after the connection was lost still closes that viewing.
        returns.ended("pc", byItself = false, now = 3 * second)
        returns.ended("pc", byItself = true, now = 4 * second)
        assertFalse(returns.resumes("pc", now = 5 * second))
    }

    @Test
    fun aDeviceThatComesBackLongAfterwardsIsNew() {
        val returns = ViewerReturns()
        returns.ended("pc", byItself = false, now = second)
        assertFalse(returns.resumes("pc", now = second + limit + 1))
        // Nor is it back the time after that: it was not cut off again.
        assertFalse(returns.resumes("pc", now = second + limit + 2))

        // The same for one that was let run out while others came and went.
        returns.ended("pc", byItself = false, now = 0)
        returns.ended("other-pc", byItself = false, now = limit + second)
        assertFalse(returns.resumes("pc", now = limit + second))
        assertTrue(returns.resumes("other-pc", now = limit + 2 * second))
    }

    @Test
    fun aReturnIsUsedUpByTheViewItStarts() {
        val returns = ViewerReturns()
        returns.ended("pc", byItself = false, now = second)
        assertTrue(returns.resumes("pc", now = 2 * second))
        // Without being cut off again, it cannot come back again.
        assertFalse(returns.resumes("pc", now = 3 * second))
    }

    /** The phone's owner took the device's approval away, switched control off, or stopped the connection. */
    @Test
    fun whatThePhonesOwnerDoesAboutItsViewersIsForgottenByNobodyComingBackUnannounced() {
        val returns = ViewerReturns()
        returns.ended("pc", byItself = false, now = second)
        returns.forget("pc")
        assertFalse(returns.resumes("pc", now = 2 * second))

        returns.ended("pc", byItself = false, now = 3 * second)
        returns.ended("other-pc", byItself = false, now = 3 * second)
        returns.forgetAll()
        assertFalse(returns.resumes("pc", now = 4 * second))
        assertFalse(returns.resumes("other-pc", now = 4 * second))

        // Only devices that are still approved are remembered.
        returns.ended("pc", byItself = false, now = 5 * second)
        returns.ended("other-pc", byItself = false, now = 5 * second)
        returns.keep(listOf("other-pc", "a device that never viewed"))
        assertFalse(returns.resumes("pc", now = 6 * second))
        assertTrue(returns.resumes("other-pc", now = 6 * second))
    }

    /** A clock that has gone backwards says nothing about how long ago it was. */
    @Test
    fun aCutOffInTheFutureIsNotBelieved() {
        val returns = ViewerReturns()
        returns.ended("pc", byItself = false, now = 10 * second)
        assertFalse(returns.resumes("pc", now = 5 * second))
    }
}
