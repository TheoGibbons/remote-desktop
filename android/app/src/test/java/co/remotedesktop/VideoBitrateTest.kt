package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bitrate steps as the encoder is really driven: a screen that changes
 * thirty times a second, pictures that have to wait for room or do not,
 * keyframes for the reasons there are, and what the encoder is then told.
 * BitrateStepsTest has the rules themselves; this is that the video gives
 * them what they go by, and does what they say. Plain JVM, with a made-up
 * encoder whose pictures are the sizes the S26's were (video plan, check
 * C3): a scrolled list nine tenths of its share of the rate, a keyframe
 * 100 KB, a repeat on a still screen 2 KB.
 */
class VideoBitrateTest {
    private companion object {
        const val START = 2_925_000
        val DOWN_ONCE = (START * 0.7).toInt()
        const val KEYFRAME = 100_000
        const val REPEAT = 2_000
    }

    private val timer = FakeVideoTimer()
    private val pipes = ArrayList<FakeVideoPipe>()
    private val failures = ArrayList<String>()
    private var room = true
    private var sent = 0

    private fun video(fed: Boolean = false) = PhoneVideo(
        encoders = { listOf(VideoSetup.Encoder("hardware", hardware = true, constantBitrate = true) { _, _ -> true }) },
        open = { attempt, _, _, events ->
            FakeVideoPipe(attempt, events).also {
                it.keyPadTo = KEYFRAME
                pipes += it
            }
        },
        timer = timer,
        clock = { timer.now },
        hasRoom = { room },
        send = { sent++; VideoSent.SENT },
        onFailed = { reason, _ -> failures += reason },
        spare = { it() },
        fed = fed,
    )

    private val video = video()
    private val pipe get() = pipes.last()

    /** Video running, its encoder's first picture out and its first seconds behind it. */
    private fun running(of: PhoneVideo = video, width: Int = 1080, height: Int = 2340): FakeVideoPipe {
        assertTrue(of.start(width, height))
        of.setSending(true)
        pipe.config()
        pipe.padTo = REPEAT
        pipe.events.picture(timer.now)
        pipe.answerAll()
        timer.advance(BitrateSteps.GRACE_MS + 1_000)
        pipe.answerAll()
        return pipe
    }

    /**
     * The screen changes thirty times a second for [seconds], as when a
     * list is scrolled. In each second there is no room for the first
     * [heldMs]: the viewer's acknowledgements are late. Each picture that
     * comes out is [share] of what the encoder is given for one.
     */
    private fun scroll(seconds: Int, heldMs: Long = 0, share: Double = 0.9, of: PhoneVideo = video) {
        repeat(seconds) {
            val from = timer.now
            while (timer.now - from < 1_000) {
                room = timer.now - from >= heldMs
                pipe.events.picture(timer.now)
                timer.advance(33)
                pipe.padTo = ((of.bitrate ?: START) / 8 / VideoSetup.FPS * share).toInt()
                pipe.answerAll()
            }
        }
        room = true
    }

    /** Whether the encoder has only ever been told to aim higher, if anything. */
    private fun neverBroughtDown() = (listOf(START) + pipe.bitrates).zipWithNext().all { (was, now) -> now >= was }

    /** The screen stops changing: the two repeats that sharpen it, and then nothing. */
    private fun comesToRest() {
        pipe.padTo = REPEAT
        timer.advance(1_000)
        pipe.answerAll()
    }

    @Test
    fun aLinkThatStaysTooSlowBringsTheEncodersRateDownAndAGoodOneBringsItBack() {
        running()
        assertEquals(START, video.bitrate)
        scroll(2)
        // With room throughout nothing has been asked of the encoder but pictures.
        assertTrue(pipe.bitrates.isEmpty())

        // The link slows: for half of every second pictures wait for room.
        scroll(3, heldMs = 500)
        assertEquals(listOf(DOWN_ONCE), pipe.bitrates)
        // What the viewer's readout is told is what the encoder was told.
        assertEquals(DOWN_ONCE, video.bitrate)
        // It stays slow: down again, three seconds apart, and no keyframe is made for any of it.
        val keyframes = pipe.drawn.count { it.keyframe }
        scroll(8, heldMs = 500)
        assertTrue("${pipe.bitrates}", pipe.bitrates.size in 3..4)
        assertEquals(pipe.bitrates.sortedDescending(), pipe.bitrates)
        assertEquals(keyframes, pipe.drawn.count { it.keyframe })
        assertTrue(video.bitrate!! < 1_100_000)

        // The link recovers: ten seconds later the rate is back where it started.
        scroll(10)
        assertTrue("${pipe.bitrates}", video.bitrate!! >= START)
        // And goes on from there for as long as the pictures use what they are given.
        scroll(30)
        assertEquals(VideoSetup.MAX_BITRATE, video.bitrate)
        assertEquals(VideoSetup.MAX_BITRATE, pipe.bitrates.last())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aKeyframeThatHoldsEverythingUpIsNotTakenForASlowLink() {
        running()
        scroll(2)
        // A second viewer opens its window in the middle of a scroll: a keyframe is made for it,
        // 100 KB on an ordinary upload, and for the best part of two seconds pictures wait behind it.
        val keyframes = pipe.drawn.count { it.keyframe }
        video.keyframe(byViewer = false)
        assertEquals(keyframes + 1, pipe.drawn.count { it.keyframe })
        scroll(1, heldMs = 700)
        scroll(1, heldMs = 500)
        scroll(3)
        assertTrue("${pipe.bitrates}", neverBroughtDown())

        // The same two seconds with no keyframe before them are a slow link.
        val before = video.bitrate!!
        scroll(1, heldMs = 700)
        scroll(1, heldMs = 500)
        scroll(1)
        assertTrue("${pipe.bitrates}", video.bitrate!! < before)
    }

    @Test
    fun theKeyframeAfterThirtyStillSecondsIsNotTakenForASlowLinkEither() {
        running()
        scroll(2)
        comesToRest()
        timer.advance(31_000)
        // The first change after that is a keyframe by the thirty-second rule, and things wait behind it.
        val keyframes = pipe.drawn.count { it.keyframe }
        scroll(1, heldMs = 700)
        assertEquals(keyframes + 1, pipe.drawn.count { it.keyframe })
        scroll(1, heldMs = 500)
        scroll(3)
        assertTrue("${pipe.bitrates}", neverBroughtDown())
    }

    @Test
    fun aStillScreenChangesNothingHoweverLongItLasts() {
        running()
        scroll(4, heldMs = 500)
        assertEquals(DOWN_ONCE, video.bitrate)
        comesToRest()
        val told = pipe.bitrates.size
        // Nothing to encode: nothing is learned about the link, and nothing runs.
        room = false
        timer.advance(3_600_000)
        room = true
        timer.advance(3_600_000)
        assertEquals(told, pipe.bitrates.size)
        assertEquals(DOWN_ONCE, video.bitrate)
    }

    @Test
    fun smallPicturesDoNotRaiseTheRateAboveWhereItStarts() {
        running()
        // Someone types: the screen changes all the time and each picture needs little.
        scroll(30, share = 0.2)
        assertTrue(pipe.bitrates.isEmpty())
        assertEquals(START, video.bitrate)
    }

    @Test
    fun aViewerAskingTwiceForAKeyframeBringsTheRateDown() {
        running()
        fun goesBy(ms: Long) {
            pipe.answerAll()
            timer.advance(ms)
            pipe.answerAll()
        }
        // The phone's own keyframes (a viewer joined, a picture went unsent) say nothing of the link.
        video.keyframe(byViewer = false)
        goesBy(500)
        video.keyframe(byViewer = false)
        goesBy(1_500)
        assertTrue(pipe.bitrates.isEmpty())

        // A viewer that lost a frame asks. One request is one lost frame.
        video.keyframe(byViewer = true)
        goesBy(3_000)
        assertTrue(pipe.bitrates.isEmpty())
        // A second within five seconds: frames are being dropped on the way.
        val keyframes = pipe.drawn.count { it.keyframe }
        video.keyframe(byViewer = true)
        assertEquals(listOf(DOWN_ONCE), pipe.bitrates)
        assertEquals(DOWN_ONCE, video.bitrate)
        // The keyframe it asked for is made all the same.
        goesBy(1_000)
        assertEquals(keyframes + 1, pipe.drawn.count { it.keyframe })
        assertTrue(failures.isEmpty())
    }

    @Test
    fun anEncoderThatFollowsAtOnceStartsAtTheRateTheLastOneHad() {
        running()
        scroll(4, heldMs = 500)
        assertEquals(DOWN_ONCE, video.bitrate)

        // The phone is turned: an encoder of the same number of pixels, started at the lower rate.
        video.stop()
        assertTrue(video.start(2340, 1080))
        assertEquals(2, pipes.size)
        assertEquals(DOWN_ONCE, pipe.attempt.bitrate)
        assertEquals(DOWN_ONCE, video.bitrate)
        assertTrue(pipe.bitrates.isEmpty())

        // Nobody views for a while. The next encoder starts where any does.
        video.stop()
        timer.advance(BitrateSteps.KEEP_MS + 1)
        assertTrue(video.start(1080, 2340))
        assertEquals(START, pipe.attempt.bitrate)
        assertEquals(START, video.bitrate)
    }

    @Test
    fun anEncoderThatWillNotTakeANewRateHasFailed() {
        running()
        pipe.failBitrate = IllegalStateException("codec is in the error state")
        scroll(4, heldMs = 500)
        assertEquals(listOf("The video encoder stopped working."), failures)
        assertEquals(1, pipe.closed)
        assertFalse(video.running)
        val sentBefore = sent
        timer.advance(5_000)
        assertEquals(sentBefore, sent)
    }

    @Test
    fun aPauseIsNotTakenForPicturesWaiting() {
        running()
        scroll(1)
        // One bad second, and then a device joins the session as a picture waits for room:
        // pictures stop for ten seconds.
        scroll(1, heldMs = 600)
        room = false
        pipe.events.picture(timer.now)
        timer.advance(250)
        video.setSending(false)
        timer.advance(10_000)
        room = true
        video.setSending(true)
        timer.advance(40)
        pipe.answerAll()
        scroll(3)
        assertTrue("${pipe.bitrates}", neverBroughtDown())
    }

    @Test
    fun screenshotsKeepTheRateTheirEncoderStartsWithWhateverTheLinkDoes() {
        val fed = video(fed = true)
        assertTrue(fed.start(1080, 2340))
        fed.setSending(true)
        pipe.config()

        /** A screenshot every 400 ms for [seconds], each kept waiting [heldMs] for room. */
        fun screenshots(seconds: Int, heldMs: Long = 0) = repeat(seconds * 5 / 2) {
            room = heldMs == 0L
            fed.show(object : ScreenPicture { override fun close() = Unit }, timer.now)
            pipe.arrived()
            timer.advance(heldMs)
            room = true
            timer.advance(400 - heldMs)
            pipe.padTo = 40_000
            pipe.answerAll()
        }
        // A good link, then one on which every screenshot waits most of its 400 ms, for a minute.
        screenshots(30)
        screenshots(60, heldMs = 300)
        // And a viewer that asks for keyframes again and again.
        repeat(5) {
            fed.keyframe(byViewer = true)
            timer.advance(1_200)
            pipe.answerAll()
        }
        screenshots(30)
        assertTrue(pipe.bitrates.isEmpty())
        assertEquals(START, fed.bitrate)
        assertTrue(failures.isEmpty())
    }
}
