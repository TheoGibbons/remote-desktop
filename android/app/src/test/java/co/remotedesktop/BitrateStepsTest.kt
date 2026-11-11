package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many bits a second the phone's video encoder is given as the link
 * turns out slow or fast: when it steps down and up, how far, how long it
 * waits, and what counts as no evidence. Plain JVM, with a clock moved by hand.
 */
class BitrateStepsTest {
    private companion object {
        /** What a 1080 x 2340 screen starts at. */
        const val START = 2_925_000
    }

    private var now = 100_000L
    private val steps = BitrateSteps()

    /** The size of a picture that uses all the encoder is given for it, at thirty a second. */
    private fun full() = steps.bitrate / 8 / VideoSetup.FPS

    /**
     * One second goes by with the encoder looked at every 20 ms, as it is
     * while a screen changes.
     * @param heldMs for how much of it a picture was kept waiting for room.
     * @param pictures how many pictures came out of the encoder in it.
     * @param size how large each was.
     * @return whether the rate changed.
     */
    private fun second(heldMs: Long = 0, pictures: Int = 30, size: Int = full()): Boolean {
        var changed = false
        val from = now
        var out = 0
        while (now - from < 1_000) {
            steps.look(now, waiting = now - from < heldMs)
            changed = steps.step(now) || changed
            // The pictures come out spread over the second.
            while (out < pictures && (now - from) * pictures >= out * 1_000L) {
                steps.encoded(size)
                out++
            }
            now += 20
        }
        return changed
    }

    /** A second in which the screen does not change: nothing looks at the encoder at all. */
    private fun still(seconds: Int = 1) {
        now += seconds * 1_000L
    }

    /** An encoder has been running for long enough that its start is behind it. */
    private fun running(start: Int = START): Int {
        val rate = steps.begin(start, now)
        now += BitrateSteps.SETTLE_MS
        return rate
    }

    // ---------------- down ----------------

    @Test
    fun anEncoderStartsAtItsUsualRateAndStaysThereWhileNothingSaysOtherwise() {
        assertEquals(START, steps.begin(START, now))
        assertEquals(START, steps.bitrate)
        // A picture waits now and then, for less than a third of the second: nothing changes.
        now += BitrateSteps.SETTLE_MS
        repeat(20) { assertFalse(second(heldMs = 250)) }
        assertEquals(START, steps.bitrate)
    }

    @Test
    fun picturesKeptWaitingForAThirdOfASecondBringTheRateDownBySevenTenths() {
        running()
        assertFalse(second())
        // The step is made as the second that showed it closes.
        second(heldMs = 400)
        second()
        assertEquals((START * 0.7).toInt(), steps.bitrate)
    }

    @Test
    fun afterAStepDownTheLinkIsNotJudgedAgainForTwoSeconds() {
        running()
        second(heldMs = 600)
        second(heldMs = 600)
        val once = (START * 0.7).toInt()
        assertEquals(once, steps.bitrate)
        // What was on its way at the old rate is still arriving.
        second(heldMs = 600)
        second(heldMs = 600)
        assertEquals(once, steps.bitrate)
        // Still no room in the second after those two: down again.
        second(heldMs = 600)
        assertEquals((START * (0.7 * 0.7)).toInt(), steps.bitrate)
    }

    @Test
    fun theRateNeverGoesBelowTheFloor() {
        running()
        repeat(60) { second(heldMs = 900) }
        assertEquals(VideoSetup.MIN_BITRATE, steps.bitrate)
        // And comes up from the floor itself, not from somewhere below it.
        var seconds = 0
        while (steps.bitrate == VideoSetup.MIN_BITRATE && seconds++ < 20) second()
        assertEquals(VideoSetup.MIN_BITRATE * 1.25, steps.bitrate.toDouble(), 1.0)
    }

    @Test
    fun theFirstTwoSecondsOfAnEncoderAreNotJudged() {
        steps.begin(START, now)
        // Its first picture is its largest, and nothing has been acknowledged yet.
        second(heldMs = 900)
        second(heldMs = 900)
        assertEquals(START, steps.bitrate)
        second(heldMs = 900)
        second()
        assertEquals((START * 0.7).toInt(), steps.bitrate)
    }

    // ---------------- up ----------------

    @Test
    fun fiveSecondsWithRoomThroughoutAndTheBudgetUsedBringTheRateUpByAQuarter() {
        running()
        repeat(5) { assertFalse(second()) }
        // The fifth such second has closed.
        assertTrue(second())
        assertEquals((START * 1.25).toInt(), steps.bitrate)
    }

    @Test
    fun onceItIsClimbingItClimbsEveryTwoSecondsUpToTheCeiling() {
        running()
        repeat(6) { second() }
        val first = steps.bitrate
        assertEquals((START * 1.25).toInt(), first)
        second()
        assertEquals(first, steps.bitrate)
        second()
        assertEquals((START * (1.25 * 1.25)).toInt(), steps.bitrate)
        repeat(30) { second() }
        assertEquals(VideoSetup.MAX_BITRATE, steps.bitrate)
        repeat(10) { assertFalse(second()) }
    }

    @Test
    fun theWayBackToWhereItStartedIsTakenASecondAtATime() {
        running()
        repeat(5) { second(heldMs = 600) }
        assertEquals((START * (0.7 * 0.7)).toInt(), steps.bitrate)
        // The link is good again. Five seconds show it, and then each second is a step:
        // four steps bring it past where it started.
        var seconds = 0
        while (steps.bitrate < START) {
            second()
            seconds++
            assertTrue("Never came back", seconds < 60)
        }
        assertTrue("$seconds", seconds in 7..10)
        // From there on it is only trying, and takes two seconds over each step.
        val above = steps.bitrate
        second()
        assertEquals(above, steps.bitrate)
        second()
        assertTrue(steps.bitrate > above)
    }

    @Test
    fun aSecondWithALittleWaitingIsNotRoomThroughout() {
        running()
        repeat(4) { second() }
        second(heldMs = 100)
        repeat(5) { second() }
        // The count began again after it.
        assertEquals(START, steps.bitrate)
        second()
        assertEquals((START * 1.25).toInt(), steps.bitrate)
    }

    @Test
    fun aStillScreenIsNoEvidenceEitherWay() {
        running()
        // An hour of nothing: no step, up or down.
        still(3_600)
        assertFalse(steps.step(now))
        assertEquals(START, steps.bitrate)

        // Nor does it break a run of good seconds, or count towards one.
        repeat(3) { second() }
        still(30)
        repeat(2) { second() }
        assertEquals(START, steps.bitrate)
        second()
        assertEquals((START * 1.25).toInt(), steps.bitrate)
    }

    @Test
    fun picturesThatNeededFewBitsDoNotProveTheLinkCanTakeMore() {
        running()
        // Someone is typing: thirty small pictures a second, and all the room in the world.
        repeat(60) { second(size = full() / 4) }
        assertEquals(START, steps.bitrate)
        // A sharpening repeat or two a second proves as little.
        repeat(60) { second(pictures = 2, size = 2_000) }
        assertEquals(START, steps.bitrate)
        // A few pictures a second that each use what they are given do count: Accessibility pictures.
        repeat(6) { second(pictures = 3) }
        assertEquals((START * 1.25).toInt(), steps.bitrate)
    }

    @Test
    fun accessibilityPicturesAreNeverGivenMoreThanTheyStartWith() {
        val capped = BitrateSteps(startIsMost = true)
        capped.begin(START, now)
        now += BitrateSteps.SETTLE_MS
        fun second(heldMs: Long = 0) {
            val from = now
            while (now - from < 1_000) {
                capped.look(now, waiting = now - from < heldMs)
                capped.step(now)
                if ((now - from) % 400 == 0L) capped.encoded(capped.bitrate / 8 / VideoSetup.FPS * 4)
                now += 20
            }
        }
        repeat(30) { second() }
        assertEquals(START, capped.bitrate)
        // Down on a slow link like any other, and back up to where it started and no further.
        second(heldMs = 500)
        second()
        assertEquals((START * 0.7).toInt(), capped.bitrate)
        repeat(30) { second() }
        assertEquals(START, capped.bitrate)
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
        // And another four seconds after that: the relay is dropping frames.
        now += 4_000
        steps.keyframeAsked(now)
        assertEquals((START * 0.7).toInt(), steps.bitrate)
        // A viewer that goes on asking every second does not bring it down every second.
        now += 1_000
        steps.keyframeAsked(now)
        assertEquals((START * 0.7).toInt(), steps.bitrate)
        now += 1_000
        steps.keyframeAsked(now)
        assertEquals((START * (0.7 * 0.7)).toInt(), steps.bitrate)
    }

    // ---------------- a step up that does not hold ----------------

    @Test
    fun aStepUpThatHasToBeTakenBackIsTriedAgainLaterAndLaterEachTime() {
        running()
        repeat(6) { second() }
        val up = steps.bitrate
        assertEquals((START * 1.25).toInt(), up)

        /** The link cannot take the higher rate; seconds until the next step up is made. */
        fun failsAndIsTriedAgainAfter(): Int {
            val high = steps.bitrate
            second(heldMs = 600)
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

        // A step up that holds for ten seconds was no mistake. When the link gets worse
        // after that, the way back up is the ordinary one.
        repeat(12) { second(size = full() / 4) }
        second(heldMs = 600)
        second()
        val low = steps.bitrate
        var seconds = 0
        while (steps.bitrate == low) {
            second()
            seconds++
        }
        assertTrue("$seconds", seconds in 4..6)
    }

    // ---------------- from one encoder to the next ----------------

    @Test
    fun anEncoderThatFollowsAtOnceCarriesOnAtTheSameShareOfItsOwnRate() {
        running()
        second(heldMs = 600)
        second()
        assertEquals((START * 0.7).toInt(), steps.bitrate)

        // The phone is turned: the old encoder ends and one of the same size starts.
        steps.ended(now)
        now += 50
        assertEquals((START * 0.7).toInt(), steps.begin(START, now))
        // After a failure the next one is three quarters the size, and starts at 0.7 of what that size starts at.
        steps.ended(now)
        now += 5_000
        val smaller = VideoSetup.bitrate(810, 1754)
        assertEquals((smaller * 0.7).toInt(), steps.begin(smaller, now))
    }

    @Test
    fun anEncoderStartedLaterStartsOver() {
        running()
        repeat(8) { second(heldMs = 600) }
        assertTrue(steps.bitrate < START)
        steps.ended(now)
        // Another viewer, on another link perhaps.
        now += BitrateSteps.KEEP_MS + 1
        assertEquals(START, steps.begin(START, now))
    }

    @Test
    fun aPauseDropsTheSecondThatWasBeingCounted() {
        running()
        second()
        // A picture is waiting as pictures stop; they start again half a minute later.
        steps.look(now, waiting = true)
        steps.paused()
        now += 30_000
        assertFalse(steps.step(now))
        second()
        second()
        assertEquals(START, steps.bitrate)
    }

    @Test
    fun aSmallPictureIsNeverGivenLessThanTheFloor() {
        val small = VideoSetup.bitrate(240, 520)
        assertEquals(VideoSetup.MIN_BITRATE, steps.begin(small, now))
        now += BitrateSteps.SETTLE_MS
        repeat(10) { second(heldMs = 900) }
        assertEquals(VideoSetup.MIN_BITRATE, steps.bitrate)
    }
}
