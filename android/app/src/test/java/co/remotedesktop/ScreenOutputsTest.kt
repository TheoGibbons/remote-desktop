package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the shared screen is mirrored to as viewers come and go, the phone
 * turns, the encoder fails and sharing stops: the reader for JPEGs, the
 * encoder for H.264, or nothing. Above all, that whatever happens to video
 * the reader gets the screen back, and that no encoder is left open. Plain
 * JVM, with a made-up encoder.
 */
class ScreenOutputsTest {
    private val timer = FakeVideoTimer()
    private val pipes = ArrayList<FakeVideoPipe>()
    private val readersMade = ArrayList<String>()
    private val readersClosed = ArrayList<String>()
    private val failures = ArrayList<String>()
    private var refuse = false
    private var dropped = 0

    /** What the screen is mirrored into now: a reader's name, an encoder, or null. */
    private var mirroredInto: Any? = null
    private var displayGone = false
    private var displayRefusesVideo = false

    private val target = CaptureTarget({ w, h -> "reader ${w}x$h".also { readersMade += it } }) { readersClosed += it }
    private val video = PhoneVideo(
        encoders = { listOf(VideoSetup.Encoder("hardware", hardware = true, constantBitrate = true) { _, _ -> true }) },
        open = { attempt, _, _, events ->
            if (refuse) throw IllegalStateException("refused")
            FakeVideoPipe(attempt, events).also { pipes += it }
        },
        timer = timer,
        clock = { timer.now },
        hasRoom = { true },
        send = { VideoSent.SENT },
        onFailed = { reason, _ -> failures += reason },
    )
    private val outputs = ScreenOutputs(target, video, object : ScreenOutputs.Display<String> {
        override fun toReader(reader: String?) {
            if (displayGone) throw IllegalStateException("the display has gone")
            mirroredInto = reader
        }

        override fun toVideo(pipe: VideoPipe) {
            if (displayGone || displayRefusesVideo) throw IllegalStateException("the display will not take it")
            mirroredInto = pipe
        }
    }) { dropped++ }

    private val pipe get() = pipes.last()
    private val openPipes get() = pipes.count { it.closed == 0 }

    /** Sharing has just started: the reader is made and the screen drawn into it, as the service does. */
    private fun sharing() {
        mirroredInto = target.open(1080, 2340)
    }

    private fun point(mirror: Boolean = true, video: Boolean = false, sending: Boolean = true,
                      width: Int = 1080, height: Int = 2340) = outputs.point(mirror, video, sending, width, height)

    /** The encoder's first picture, after which its repeats and watchdogs have nothing to say. */
    private fun firstPicture() {
        pipe.config()
        pipe.events.picture(timer.now)
        timer.advance(1_000)
        pipe.answerAll()
    }

    @Test
    fun viewersThatAllTakeVideoGetTheEncoderAndOthersTheReader() {
        sharing()
        // A PC that offers no H.264, a phone, an older app: JPEGs, from the reader.
        assertNull(point(video = false))
        assertEquals("reader 1080x2340", mirroredInto)
        assertTrue(pipes.isEmpty())

        // They leave, and a PC that offers H.264 is the only viewer.
        assertNull(point(video = true))
        assertTrue(mirroredInto === pipe)
        assertTrue(video.running)
        assertFalse(target.drawing)
        // Pictures that were waiting to become JPEGs are dropped.
        assertEquals(1, dropped)
        firstPicture()

        // A viewer that cannot draw H.264 joins: back to the reader, and the encoder is let go of.
        assertNull(point(video = false))
        assertEquals("reader 1080x2340", mirroredInto)
        assertEquals(1, pipe.closed)
        assertFalse(video.running)
        assertTrue(target.drawing)

        // It leaves again: a new encoder.
        assertNull(point(video = true))
        assertEquals(2, pipes.size)
        assertEquals(1, openPipes)
    }

    @Test
    fun aShortPauseKeepsTheEncoderAndNobodyViewingLetsGoOfIt() {
        sharing()
        point(video = true)
        firstPicture()
        val drawn = pipe.drawn.size

        // A device joins the session: pictures pause for a moment. Nobody counts as
        // viewing meanwhile, so nothing says video is wanted; the encoder is kept all the same.
        assertNull(point(video = false, sending = false))
        assertEquals(0, pipe.closed)
        assertTrue(mirroredInto === pipe)
        pipe.events.picture(timer.now)
        timer.advance(500)
        assertEquals(drawn, pipe.drawn.size)
        // They go on: the same encoder, and a keyframe for the new stream.
        assertNull(point(video = true, sending = true))
        timer.advance()
        assertEquals(1, pipes.size)
        assertTrue(pipe.drawn.last().keyframe)

        // The last viewer leaves, which looks the same at first. Five seconds later
        // the screen is mirrored nowhere, and the encoder is let go of.
        point(video = false, sending = false)
        assertEquals(0, pipe.closed)
        assertNull(point(mirror = false, video = false, sending = false))
        assertNull(mirroredInto)
        assertEquals(1, pipe.closed)
        assertEquals(0, openPipes)
        assertFalse(target.drawing)

        // Even if the viewers that left all took video, nothing is made for nobody.
        assertNull(point(mirror = false, video = true, sending = false))
        assertEquals(0, openPipes)
        assertNull(mirroredInto)
    }

    @Test
    fun aPauseWhileSendingJpegsStartsNoEncoder() {
        sharing()
        point(video = false)
        // Paused, with viewers that would all take video: that is for when pictures go again.
        assertNull(point(video = true, sending = false))
        assertTrue(pipes.isEmpty())
        assertEquals("reader 1080x2340", mirroredInto)
        assertNull(point(video = true, sending = true))
        assertEquals(1, openPipes)
    }

    @Test
    fun picturesThatGoAgainAfterAPauseToViewersWithoutVideoLeaveTheEncoder() {
        sharing()
        point(video = true)
        firstPicture()
        // A phone joined during the pause: JPEGs for everyone when it is over.
        point(video = false, sending = false)
        assertEquals(0, pipe.closed)
        assertNull(point(video = false, sending = true))
        assertEquals(1, pipe.closed)
        assertEquals("reader 1080x2340", mirroredInto)
    }

    @Test
    fun theLastViewerLeavingStraightFromVideoLeavesNothingOpen() {
        sharing()
        point(video = true)
        firstPicture()
        // Nobody views, and the wish for video goes with them, in one step.
        assertNull(point(mirror = false, video = false, sending = false))
        assertNull(mirroredInto)
        assertEquals(0, openPipes)
        // Nothing is drawn or kept awake afterwards.
        timer.advance(600_000)
        assertEquals(3, pipe.drawn.size)

        // Someone views again: video again, from a new encoder.
        assertNull(point(video = true))
        assertEquals(2, pipes.size)
        assertTrue(mirroredInto === pipe)
    }

    @Test
    fun videoThatWillNotStartLeavesTheScreenInTheReader() {
        sharing()
        refuse = true
        val failure = point(video = true)
        assertEquals("The video encoder could not be started.", failure!!.first)
        assertTrue(failure.second!!.contains("refused"))
        // JPEGs go on: the reader has the screen.
        assertEquals("reader 1080x2340", mirroredInto)
        assertTrue(target.drawing)
        assertEquals(0, openPipes)

        // Nothing would start at any size, so video is given up: it is not even tried.
        assertTrue(video.gaveUp)
        refuse = false
        assertNull(point(video = true))
        assertEquals("reader 1080x2340", mirroredInto)
        assertTrue(pipes.isEmpty())

        // Ten minutes later it is.
        timer.advance(PhoneVideo.FORGET_MS)
        assertNull(point(video = true))
        assertEquals(1, openPipes)
        assertTrue(mirroredInto === pipe)
    }

    @Test
    fun anEncoderThatFailsWhileRunningHandsTheScreenBackOnTheNextPoint() {
        sharing()
        point(video = true)
        firstPicture()
        pipe.events.failed(IllegalStateException("codec error"))
        timer.advance()
        assertEquals(listOf("The video encoder stopped working."), failures)
        assertEquals(0, openPipes)
        // The service hears of it and points again, video no longer being what it tries.
        assertNull(point(video = false))
        assertEquals("reader 1080x2340", mirroredInto)
        assertTrue(target.drawing)
    }

    @Test
    fun aDisplayThatWillNotTakeTheEncoderIsAFailureToo() {
        sharing()
        displayRefusesVideo = true
        val failure = point(video = true)
        assertEquals("The screen could not be shown to the video encoder.", failure!!.first)
        // The encoder that had started is let go of, and the reader has the screen again.
        assertEquals(1, pipes.size)
        assertEquals(0, openPipes)
        assertEquals("reader 1080x2340", mirroredInto)
        assertTrue(target.drawing)

        // It counts like any failure, so it is not tried at every pause for ever:
        // twice more, a size down each time, and then video is given up.
        assertNotNull(point(video = true))
        assertNotNull(point(video = true))
        assertEquals(listOf(1080, 810, 540), pipes.map { it.width })
        assertTrue(video.gaveUp)
        repeat(5) { assertNull(point(video = true)) }
        assertEquals(3, pipes.size)
        assertEquals(0, openPipes)
        assertEquals("reader 1080x2340", mirroredInto)
    }

    @Test
    fun aTurnedPhoneGetsANewEncoderAtTheNewSize() {
        sharing()
        point(video = true)
        firstPicture()
        val upright = pipe

        // The display listener fires for many things that are not a turn.
        assertFalse(outputs.turning(1080, 2340))
        assertEquals(0, upright.closed)

        // A turn: video at the old shape ends before the mirror changes shape.
        assertTrue(outputs.turning(2340, 1080))
        assertEquals(1, upright.closed)
        assertNull(mirroredInto)
        assertTrue(target.replace(2340, 1080, attach = { if (target.drawing) mirroredInto = it }))
        // The reader is swapped for the new shape but not drawn into: video is what is wanted.
        assertNull(mirroredInto)
        assertNull(point(video = true, width = 2340, height = 1080))
        assertTrue(mirroredInto === pipe)
        assertEquals(2340 to 1080, video.size)
        assertEquals(1, openPipes)
        // Its first picture is a keyframe, once the screen has given the new texture one.
        pipe.config()
        pipe.events.picture(timer.now)
        assertTrue(pipe.drawn.single().keyframe)
        assertEquals(listOf("reader 1080x2340"), readersClosed)
    }

    @Test
    fun aTurnWhileSendingJpegsLeavesVideoAlone() {
        sharing()
        point(video = false)
        assertFalse(outputs.turning(2340, 1080))
        assertTrue(pipes.isEmpty())
    }

    @Test
    fun whenSharingStopsEverythingIsLetGoOfAndNothingStartsAgain() {
        sharing()
        point(video = true)
        firstPicture()
        pipe.events.picture(timer.now)

        // As the service's onDestroy does: the display and the reader first, then the video.
        target.close { displayGone = true }
        outputs.close()
        assertEquals(1, pipe.closed)
        assertEquals(listOf("reader 1080x2340"), readersClosed)
        assertFalse(video.running)

        // A point that was already on its way to the capture thread does nothing, and reports nothing.
        assertNull(point(video = true))
        assertNull(point(video = false))
        timer.advance(600_000)
        assertEquals(1, pipes.size)
        assertEquals(1, readersMade.size)
        assertTrue(failures.isEmpty())
        assertFalse(video.gaveUp)
    }

    @Test
    fun sharingThatStopsWhileTheScreenIsOffOrLockedLeavesNothingOpen() {
        // A sleeping phone gives the encoder no picture, and that is not a failure.
        val asleep = PhoneVideo({ listOf(VideoSetup.Encoder("hardware", true, true) { _, _ -> true }) },
            { attempt, _, _, events -> FakeVideoPipe(attempt, events).also { pipes += it } },
            timer, { timer.now }, { true }, { VideoSent.SENT }, { reason, _ -> failures += reason }, screenOn = { false })
        val outputs = ScreenOutputs(target, asleep, object : ScreenOutputs.Display<String> {
            override fun toReader(reader: String?) { mirroredInto = reader }
            override fun toVideo(pipe: VideoPipe) { mirroredInto = pipe }
        }) { dropped++ }
        sharing()
        assertNull(outputs.point(true, true, true, 1080, 2340))
        timer.advance(120_000)
        assertTrue(failures.isEmpty())
        assertTrue(pipe.drawn.isEmpty())
        // Android ends the sharing because the phone locked.
        target.close()
        outputs.close()
        assertEquals(0, openPipes)
        timer.advance(120_000)
        assertTrue(failures.isEmpty())
    }
}
