package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bitrate steps as the encoder is really driven: a screen that changes
 * thirty times a second, pictures that have to wait for room or do not, and
 * what the encoder is then told. BitrateStepsTest has the rules themselves;
 * this is that the video gives them what they go by, and does what they
 * say. Plain JVM, with a made-up encoder.
 */
class VideoBitrateTest {
    private companion object {
        const val START = 2_925_000
        val DOWN_ONCE = (START * 0.7).toInt()
    }

    private val timer = FakeVideoTimer()
    private val pipes = ArrayList<FakeVideoPipe>()
    private val failures = ArrayList<String>()
    private var room = true
    private var sent = 0

    private fun video(fed: Boolean = false) = PhoneVideo(
        encoders = { listOf(VideoSetup.Encoder("hardware", hardware = true, constantBitrate = true) { _, _ -> true }) },
        open = { attempt, _, _, events -> FakeVideoPipe(attempt, events).also { pipes += it } },
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

    /** Video running, its encoder's first picture out and its first two seconds behind it. */
    private fun running(of: PhoneVideo = video, width: Int = 1080, height: Int = 2340): FakeVideoPipe {
        assertTrue(of.start(width, height))
        of.setSending(true)
        pipe.config()
        pipe.events.picture(timer.now)
        pipe.answerAll()
        timer.advance(BitrateSteps.SETTLE_MS + 1_000)
        pipe.answerAll()
        return pipe
    }

    /**
     * The screen changes thirty times a second for [seconds], as when a
     * list is scrolled. In each second there is no room for the first
     * [heldMs]: the viewer's acknowledgements are late. Each picture that
     * comes out is [share] of what the encoder is given for one.
     */
    private fun scroll(seconds: Int, heldMs: Long = 0, share: Double = 1.0, of: PhoneVideo = video) {
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

    @Test
    fun picturesKeptWaitingBringTheEncodersRateDownAndRoomBringsItBack() {
        running()
        assertEquals(START, video.bitrate)
        scroll(3)
        // With room throughout nothing has been asked of the encoder but pictures.
        assertTrue(pipe.bitrates.isEmpty())

        // The link slows: for half of every second pictures wait for room.
        scroll(2, heldMs = 500)
        assertEquals(listOf(DOWN_ONCE), pipe.bitrates)
        // What the viewer's readout is told is what the encoder was told.
        assertEquals(DOWN_ONCE, video.bitrate)
        // It stays slow: down again, a few seconds apart, and no keyframe is made for any of it.
        val keyframes = pipe.drawn.count { it.keyframe }
        scroll(8, heldMs = 500)
        assertTrue("${pipe.bitrates}", pipe.bitrates.size in 3..4)
        assertEquals(pipe.bitrates.sortedDescending(), pipe.bitrates)
        assertEquals(keyframes, pipe.drawn.count { it.keyframe })
        val low = video.bitrate!!
        assertTrue(low < DOWN_ONCE)

        // The link recovers: within about ten seconds the rate is well on its way back.
        scroll(10)
        assertTrue("${pipe.bitrates} from $low", video.bitrate!! > low * 1.5)
        scroll(30)
        assertEquals(VideoSetup.MAX_BITRATE, video.bitrate)
        assertEquals(VideoSetup.MAX_BITRATE, pipe.bitrates.last())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aStillScreenChangesNothingHoweverLongItLasts() {
        running()
        scroll(2, heldMs = 500)
        assertEquals(DOWN_ONCE, video.bitrate)
        timer.advance(1_000)
        pipe.answerAll()
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
    fun smallPicturesDoNotRaiseTheRate() {
        running()
        // Someone types: the screen changes all the time and each picture needs little.
        scroll(30, share = 0.2)
        assertTrue(pipe.bitrates.isEmpty())
        assertEquals(START, video.bitrate)
    }

    @Test
    fun aViewerAskingTwiceForAKeyframeBringsTheRateDown() {
        running()
        // The phone's own keyframes (a viewer joined, a picture went unsent) say nothing of the link.
        fun goesBy(ms: Long) {
            pipe.answerAll()
            timer.advance(ms)
            pipe.answerAll()
        }
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
        scroll(2, heldMs = 500)
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
        scroll(2, heldMs = 500)
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
        // A device joins the session as a picture waits for room; pictures stop for ten seconds.
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
        assertTrue(pipe.bitrates.isEmpty())
    }

    @Test
    fun screenshotsAreNeverGivenMoreThanTheyStartWithAndComeDownLikeAnyPicture() {
        val fed = video(fed = true)
        assertTrue(fed.start(1080, 2340))
        fed.setSending(true)
        pipe.config()

        /** A screenshot every 400 ms for [seconds], each using four times a smooth picture's share of the bits. */
        fun screenshots(seconds: Int, heldMs: Long = 0) = repeat(seconds * 5 / 2) {
            room = heldMs == 0L
            fed.show(object : ScreenPicture { override fun close() = Unit }, timer.now)
            pipe.arrived()
            timer.advance(heldMs)
            room = true
            timer.advance(400 - heldMs)
            pipe.padTo = fed.bitrate!! / 8 / VideoSetup.FPS * 4
            pipe.answerAll()
        }
        screenshots(30)
        assertTrue(pipe.bitrates.isEmpty())
        assertEquals(START, fed.bitrate)

        // On a slow link each screenshot waits 200 ms of its 400 for room.
        screenshots(4, heldMs = 200)
        assertEquals(DOWN_ONCE, pipe.bitrates.first())
        // And back, to where it started and no further.
        screenshots(40)
        assertEquals(START, fed.bitrate)
        assertEquals(START, pipe.bitrates.last())
        assertTrue(failures.isEmpty())
    }
}
