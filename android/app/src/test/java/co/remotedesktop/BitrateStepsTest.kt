package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many bits a second the phone's video encoder is given in smooth
 * sharing as the link turns out slow or fast. Most of all, what a real link
 * does to it: acknowledgements that come unevenly, a large keyframe on an
 * ordinary upload, a viewer that is busy for a second. None of those may
 * bring the rate down, and a rate that was brought down has to find its way
 * back. Plain JVM, with a clock moved by hand.
 *
 * The sizes are the S26's (video plan, check C3): at its starting rate a
 * scrolled list came out at about 11 KB a picture, nine tenths of what the
 * encoder is given for one.
 */
class BitrateStepsTest {
    private companion object {
        /** What a 1080 x 2340 screen starts at. */
        const val START = 2_925_000
        val DOWN_ONCE = (START * 0.7).toInt()

        /** How long pictures waited in each second of a minute on a good link that is uneven, in ms. */
        val UNEVEN = longArrayOf(0, 120, 40, 250, 0, 180, 90, 0, 280, 60, 0, 30, 140, 0, 220, 10, 0, 90, 270, 0)
    }

    private var now = 100_000L
    private val steps = BitrateSteps()

    /** The size of a scrolled picture at the rate the encoder has now: nine tenths of its share. */
    private fun scrolled(of: BitrateSteps = steps) = of.bitrate / 8 / VideoSetup.FPS * 9 / 10

    /**
     * One second goes by with the encoder looked at every 20 ms, as it is
     * while a screen changes.
     * @param heldMs for how much of it a picture was kept waiting for a viewer's acknowledgement.
     * @param pictures how many pictures came out of the encoder in it.
     * @param size how large each was.
     * @return whether the rate changed.
     */
    private fun second(heldMs: Long = 0, pictures: Int = 30, size: Int = scrolled(), of: BitrateSteps = steps): Boolean {
        var changed = false
        val from = now
        var out = 0
        while (now - from < 1_000) {
            of.look(now, waiting = now - from < heldMs)
            changed = of.step(now) || changed
            // The pictures come out spread over the second.
            while (out < pictures && (now - from) * pictures >= out * 1_000L) {
                of.encoded(size)
                out++
            }
            now += 20
        }
        return changed
    }

    /** Seconds in which the screen does not change: nothing looks at the encoder at all. */
    private fun still(seconds: Int = 1) {
        now += seconds * 1_000L
    }

    /** An encoder has been running for long enough that its start is behind it. */
    private fun running(start: Int = START, of: BitrateSteps = steps): Int {
        val rate = of.begin(start, now)
        now += BitrateSteps.GRACE_MS
        return rate
    }

    /** The link is too slow for [seconds]: pictures wait for more than half of each. */
    private fun tooSlow(seconds: Int) = repeat(seconds) { second(heldMs = 600) }

    /** The rate has been brought down [times], and the last second that did it has closed. */
    private fun broughtDown(times: Int) {
        running()
        var seconds = 0
        while (steps.bitrate > (START * Math.pow(0.7, times.toDouble())).toInt() + 1) {
            second(heldMs = 600)
            assertTrue("Never came down", seconds++ < 60)
        }
    }

    // ---------------- what a real link does, and must not bring the rate down ----------------

    @Test
    fun acknowledgementsThatComeUnevenlyNeverBringTheRateDown() {
        running()
        // Two minutes of a good link: in most seconds a picture waits a little, in some a quarter of the second.
        var lowest = steps.bitrate
        repeat(6) {
            for (held in UNEVEN) {
                second(heldMs = held)
                lowest = minOf(lowest, steps.bitrate)
            }
        }
        assertEquals(START, lowest)
        // And it has gone up to all the encoder may be given, since the pictures used what they were given.
        assertEquals(VideoSetup.MAX_BITRATE, steps.bitrate)
    }

    @Test
    fun aViewerThatIsBusyForOneSecondChangesNothing() {
        running()
        repeat(2) { second() }
        // One second in which nearly nothing was acknowledged in time.
        second(heldMs = 900)
        repeat(2) { second() }
        assertEquals(START, steps.bitrate)
        // Nor does it set the count of good seconds back: the fifth brings the step up.
        second()
        second()
        assertEquals((START * 1.25).toInt(), steps.bitrate)
        // The same again every so often, for a minute: never a step down.
        repeat(10) {
            second(heldMs = 900)
            repeat(5) { second() }
            assertTrue(steps.bitrate >= START)
        }
    }

    @Test
    fun aLargeKeyframeOnAnOrdinaryUploadIsNotTakenForASlowLink() {
        running()
        repeat(2) { second() }
        // A viewer joins, or thirty seconds have passed, or the last picture went unacknowledged:
        // a keyframe of 150 KB on a 2 Mbit/s upload, and everything behind it waits for most of a second, twice.
        steps.keyframe(now)
        second(heldMs = 700)
        second(heldMs = 500)
        repeat(2) { second() }
        assertEquals(START, steps.bitrate)

        // Without a keyframe to explain them, the same two seconds are a slow link.
        second(heldMs = 700)
        second(heldMs = 500)
        second()
        assertEquals(DOWN_ONCE, steps.bitrate)
    }

    @Test
    fun aKeyframeEveryFewSecondsOnAGoodLinkLeavesTheRateWhereItIs() {
        running()
        // A viewer that comes and goes, each time a keyframe and half a second behind it.
        repeat(20) {
            steps.keyframe(now)
            second(heldMs = 500)
            second(heldMs = 350)
            second()
            assertTrue(steps.bitrate >= START)
        }
    }

    @Test
    fun theFirstTwoSecondsOfAnEncoderAreNotJudged() {
        steps.begin(START, now)
        // Its first picture is its largest, and nothing has been acknowledged yet.
        second(heldMs = 900)
        second(heldMs = 900)
        // The third second is the first that counts, and one is not enough.
        second(heldMs = 900)
        second()
        assertEquals(START, steps.bitrate)
    }

    // ---------------- down ----------------

    @Test
    fun twoBadSecondsInARowBringTheRateDownBySevenTenths() {
        running()
        assertFalse(second())
        second(heldMs = 400)
        // One bad second has closed: nothing yet.
        second(heldMs = 400)
        assertEquals(START, steps.bitrate)
        // The step is made as the second bad second closes.
        second()
        assertEquals(DOWN_ONCE, steps.bitrate)
    }

    @Test
    fun aLinkThatStaysTooSlowBringsTheRateDownEveryThreeSeconds() {
        running()
        val downs = ArrayList<Int>()
        var last = steps.bitrate
        repeat(12) { second ->
            second(heldMs = 600)
            if (steps.bitrate != last) {
                downs += second
                last = steps.bitrate
            }
        }
        // What was sent at the old rate is still arriving for a second after each step.
        assertEquals(listOf(2, 5, 8, 11), downs)
        assertEquals((START * (0.7 * 0.7 * 0.7 * 0.7)).toInt(), steps.bitrate)
    }

    @Test
    fun theRateNeverGoesBelowTheFloor() {
        running()
        tooSlow(120)
        assertEquals(VideoSetup.MIN_BITRATE, steps.bitrate)
        // And comes up from the floor itself, not from somewhere below it.
        var seconds = 0
        while (steps.bitrate == VideoSetup.MIN_BITRATE && seconds++ < 20) second()
        assertEquals(VideoSetup.MIN_BITRATE * 1.25, steps.bitrate.toDouble(), 1.0)
    }

    @Test
    fun aSmallPictureIsNeverGivenLessThanTheFloor() {
        val small = VideoSetup.bitrate(240, 520)
        assertEquals(VideoSetup.MIN_BITRATE, steps.begin(small, now))
        now += BitrateSteps.GRACE_MS
        tooSlow(10)
        assertEquals(VideoSetup.MIN_BITRATE, steps.bitrate)
    }

    @Test
    fun badSecondsWithAStillScreenBetweenThemAreNotInARow() {
        running()
        repeat(2) { second() }
        second(heldMs = 900)
        // The screen stops changing for an hour, and nothing looks at the encoder.
        still(3_600)
        assertFalse(steps.step(now))
        // It changes again, into another bad second, which is the first of its kind.
        second(heldMs = 900)
        repeat(2) { second() }
        assertEquals(START, steps.bitrate)
        // The hour counted for nothing the other way either: the good seconds are the four there were.
        second()
        assertEquals(START, steps.bitrate)
        second()
        assertEquals((START * 1.25).toInt(), steps.bitrate)
    }

    // ---------------- back up to where it started ----------------

    @Test
    fun onAnUnevenLinkTheRateIsBackWhereItStartedWithinTenSeconds() {
        // Held to 1 Mbit/s for a while, as the plan's test does: three steps down.
        broughtDown(3)
        assertTrue(steps.bitrate < 1_100_000)
        // The limit lifts. The link is good again, and as uneven as links are.
        var seconds = 0
        while (steps.bitrate < START) {
            second(heldMs = UNEVEN[seconds % UNEVEN.size])
            seconds++
            assertTrue("Never came back", seconds < 60)
        }
        assertTrue("$seconds", seconds <= 10)
    }

    @Test
    fun itComesBackWhateverThePicturesNeed() {
        broughtDown(3)
        // Someone types: small pictures, a few a second. The link is not full, and that is enough
        // to go back to the rate any encoder starts at.
        var seconds = 0
        while (steps.bitrate < START) {
            second(pictures = 4, size = 1_500)
            seconds++
            assertTrue("Never came back", seconds < 60)
        }
        assertTrue("$seconds", seconds <= 10)
    }

    @Test
    fun fromTheFloorItTakesAQuarterOfAMinute() {
        running()
        tooSlow(120)
        var seconds = 0
        while (steps.bitrate < START) {
            second(heldMs = UNEVEN[seconds % UNEVEN.size])
            seconds++
            assertTrue("Never came back", seconds < 60)
        }
        assertTrue("$seconds", seconds in 12..16)
    }

    @Test
    fun aBadSecondOnTheWayBackHoldsItUpForThatSecondOnly() {
        broughtDown(2)
        val low = steps.bitrate
        repeat(4) { second() }
        assertTrue(steps.bitrate > low)
        // One bad second. (As it begins, the good one before it closes, with its step.)
        second(heldMs = 900)
        val climbing = steps.bitrate
        // As it closes: no step, up or down.
        second()
        assertEquals(climbing, steps.bitrate)
        // And on it goes.
        second()
        assertTrue(steps.bitrate > climbing)
    }

    // ---------------- above where it started ----------------

    @Test
    fun aboveItsStartingRateFiveGoodSecondsThatUsedTheBudgetBringAStepAndThenEveryTwo() {
        running()
        repeat(5) { assertFalse(second()) }
        // The fifth such second has closed.
        assertTrue(second())
        assertEquals((START * 1.25).toInt(), steps.bitrate)
        second()
        assertEquals((START * 1.25).toInt(), steps.bitrate)
        second()
        assertEquals((START * (1.25 * 1.25)).toInt(), steps.bitrate)
        repeat(30) { second() }
        assertEquals(VideoSetup.MAX_BITRATE, steps.bitrate)
        repeat(10) { assertFalse(second()) }
    }

    @Test
    fun picturesThatNeededFewBitsDoNotTakeTheRateAboveWhereItStarted() {
        running()
        // Someone is typing: thirty small pictures a second, and all the room in the world.
        repeat(60) { second(size = scrolled() / 4) }
        assertEquals(START, steps.bitrate)
        // A sharpening repeat or two a second proves as little.
        repeat(60) { second(pictures = 2, size = 2_000) }
        assertEquals(START, steps.bitrate)
    }

    @Test
    fun aboveItsStartingRateASecondWithMuchWaitingDoesNotCountAndDoesNotStartTheCountAgain() {
        running()
        repeat(3) { second() }
        // A fifth of a second's waiting: not bad, and no sign that more would get through.
        second(heldMs = 200)
        repeat(2) { second() }
        assertEquals(START, steps.bitrate)
        second()
        assertEquals((START * 1.25).toInt(), steps.bitrate)
    }

    // ---------------- requests for a keyframe ----------------

    @Test
    fun twoRequestsForAKeyframeWithinFiveSecondsAreAStepDown() {
        running()
        // One is a viewer that lost a frame, or has just come.
        steps.keyframeAsked(now)
        assertEquals(START, steps.bitrate)
        // Six seconds later another: still nothing.
        now += 6_000
        steps.keyframeAsked(now)
        assertEquals(START, steps.bitrate)
        // And another four seconds after that: the relay is dropping frames. The keyframes
        // that were made for those requests take nothing away from that.
        steps.keyframe(now)
        now += 4_000
        steps.keyframeAsked(now)
        assertEquals(DOWN_ONCE, steps.bitrate)
        // A viewer that goes on asking every second does not bring it down every second.
        steps.keyframe(now)
        now += 1_000
        steps.keyframeAsked(now)
        assertEquals(DOWN_ONCE, steps.bitrate)
        steps.keyframe(now)
        now += 1_000
        steps.keyframeAsked(now)
        assertEquals((START * (0.7 * 0.7)).toInt(), steps.bitrate)
    }

    // ---------------- a step up that does not hold ----------------

    @Test
    fun aStepUpThatHasToBeTakenBackIsTriedAgainLaterAndLaterEachTime() {
        running()
        repeat(6) { second() }
        assertEquals((START * 1.25).toInt(), steps.bitrate)

        /** The link cannot take the higher rate; seconds until the next step up is made. */
        fun failsAndIsTriedAgainAfter(): Int {
            val high = steps.bitrate
            tooSlow(2)
            second()
            val low = steps.bitrate
            assertTrue(low < high)
            var seconds = 0
            while (steps.bitrate == low) {
                second()
                seconds++
                assertTrue("Never tried again", seconds < 120)
            }
            return seconds
        }
        val first = failsAndIsTriedAgainAfter()
        val second = failsAndIsTriedAgainAfter()
        val third = failsAndIsTriedAgainAfter()
        val fourth = failsAndIsTriedAgainAfter()
        assertTrue("$first", first in 9..11)
        assertTrue("$second", second in 19..21)
        assertTrue("$third", third in 29..31)
        // No longer than that, however often.
        assertTrue("$fourth", fourth in 29..31)
    }

    @Test
    fun aStepUpThatHeldForTenSecondsWasNoMistake() {
        running()
        repeat(6) { second() }
        assertEquals((START * 1.25).toInt(), steps.bitrate)
        // Twelve seconds at the higher rate, with nothing more to learn from them.
        repeat(12) { second(size = scrolled() / 4) }
        assertEquals((START * 1.25).toInt(), steps.bitrate)
        // The link gets worse. The way back up afterwards is the ordinary one, with no extra wait.
        tooSlow(2)
        second()
        val low = steps.bitrate
        assertTrue(low < START)
        var seconds = 0
        while (steps.bitrate == low) {
            second()
            seconds++
            assertTrue("Never tried again", seconds < 60)
        }
        assertTrue("$seconds", seconds in 2..4)
    }

    // ---------------- from one encoder to the next ----------------

    @Test
    fun anEncoderThatFollowsAtOnceCarriesOnAtTheSameShareOfItsOwnRate() {
        broughtDown(1)
        assertEquals(DOWN_ONCE, steps.bitrate)

        // The phone is turned: the old encoder ends and one of the same size starts.
        steps.ended(now)
        now += 50
        assertEquals(DOWN_ONCE, steps.begin(START, now))
        // After a failure the next one is three quarters the size, and starts at 0.7 of what that size starts at.
        steps.ended(now)
        now += 5_000
        val smaller = VideoSetup.bitrate(810, 1754)
        assertEquals((smaller * 0.7).toInt(), steps.begin(smaller, now))
    }

    @Test
    fun anEncoderStartedLaterStartsOver() {
        broughtDown(2)
        steps.ended(now)
        // Another viewer, on another link perhaps.
        now += BitrateSteps.KEEP_MS + 1
        assertEquals(START, steps.begin(START, now))
    }

    /**
     * Pictures stop (a device joins the session) while one is waiting, just
     * after a bad second. What was being counted is dropped. Run both ways,
     * to show that without the dropping this very case is a step down.
     */
    @Test
    fun aPauseDropsTheSecondThatWasBeingCountedAndTheBadOneBeforeIt() {
        fun rateAfterAPause(dropped: Boolean): Int {
            val steps = BitrateSteps()
            running(of = steps)
            second(of = steps)
            second(heldMs = 900, of = steps)
            // The bad second closes, and in the next a picture waits a quarter of a second. Then pictures stop.
            val from = now
            while (now - from <= 260) {
                steps.look(now, waiting = true)
                steps.step(now)
                now += 20
            }
            if (dropped) steps.paused()
            now += 30_000
            repeat(2) { second(of = steps) }
            return steps.bitrate
        }
        assertEquals(START, rateAfterAPause(dropped = true))
        assertEquals(DOWN_ONCE, rateAfterAPause(dropped = false))
    }
}
