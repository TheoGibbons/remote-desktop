package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the shared screen is mirrored to as viewers come and go, the phone
 * turns, the encoder fails and sharing stops: the reader for JPEGs, the
 * encoder for H.264, or nothing. And what the viewers are told and the
 * connection history gets when video fails, whichever way it fails: this
 * is the capture service's own wiring, which the service only hands its
 * display and its threads to. Above all, that whatever happens to video the
 * reader gets the screen back and the viewers hear of the JPEG stream, and
 * that no encoder is left open. Plain JVM, with a made-up encoder, on one
 * thread; VideoLaneTest has the encoder that really does not come back.
 */
class ScreenOutputsTest {
    private val timer = FakeVideoTimer()
    private val pipes = ArrayList<FakeVideoPipe>()
    private val readersMade = ArrayList<String>()
    private val readersClosed = ArrayList<String>()
    private var refuse = false
    private var dropped = 0
    private var screenOn = true

    /** What the screen is mirrored into now: a reader's name, an encoder, or null. */
    private var mirroredInto: Any? = null
    private var displayRefusesVideo = false

    // What the capture service is told to do.
    private var sending = true
    private var size = 1080 to 2340
    private val announced = ArrayList<String>()
    private val history = ArrayList<String>()
    private val later = ArrayList<Pair<Long, Runnable>>()

    private val wish = VideoWish()
    private val wedge = AtomicBoolean()
    private val target = CaptureTarget({ w, h -> "reader ${w}x$h".also { readersMade += it } }) { readersClosed += it }
    private lateinit var outputs: ScreenOutputs<String>
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
        // As the service passes it on, from the video's thread to the capture thread.
        onFailed = { reason, detail -> outputs.failed(reason, detail) },
        screenOn = { screenOn },
        spare = { it() },
    )
    private val lane = VideoLane(video, post = { it.run() }, clock = { timer.now }, wedge = wedge)

    init {
        outputs = ScreenOutputs(target, lane, wish, object : ScreenOutputs.Display<String> {
            override fun toReader(reader: String?) {
                mirroredInto = reader
            }

            override fun toVideo(pipe: VideoPipe) {
                if (displayRefusesVideo) throw IllegalStateException("the display will not take it")
                mirroredInto = pipe
            }
        }, object : ScreenOutputs.Host {
            override val sending: Boolean get() = this@ScreenOutputsTest.sending
            override fun size() = this@ScreenOutputsTest.size
            override fun dropWaiting() { dropped++ }
            override fun announce(width: Int, height: Int, video: Boolean, always: Boolean) {
                announced += "${width}x$height ${if (video) "video" else "jpeg"}${if (always) "" else ", news only"}"
            }
            override fun videoFailed(reason: String, detail: String?) { history += reason }
            override fun later(task: Runnable, delayMs: Long) { later += (timer.now + delayMs) to task }
        })
    }

    private val pipe get() = pipes.last()
    private val openPipes get() = pipes.count { it.closed == 0 }

    /** Sharing has just started: the reader is made and the screen drawn into it, as the service does. */
    private fun sharing() {
        mirroredInto = target.open(1080, 2340)
    }

    /** What the capture thread has been asked to do later, as far as it has fallen due. */
    private fun captureThread() {
        while (true) {
            val due = later.filter { it.first <= timer.now }.minByOrNull { it.first } ?: return
            later.remove(due)
            due.second.run()
        }
    }

    private fun advance(ms: Long) {
        timer.advance(ms)
        captureThread()
    }

    /** As the service's main thread does when viewers change: pictures go or stop, and video is wanted or not. */
    private fun viewers(on: Boolean, allTakeVideo: Boolean) {
        val change = wish.update(on, on && allTakeVideo)
        sending = on
        if (!change.any) return
        if (on) {
            outputs.announce(always = change.started)
            outputs.mirror(true)
        } else outputs.point()
    }

    /** The encoder's first picture, after which its repeats and watchdogs have nothing to say. */
    private fun firstPicture() {
        pipe.config()
        pipe.events.picture(timer.now)
        advance(1_000)
        pipe.answerAll()
    }

    // ---------------- where the screen goes ----------------

    @Test
    fun viewersThatAllTakeVideoGetTheEncoderAndOthersTheReader() {
        sharing()
        // A PC that offers no H.264, a phone, an older app: JPEGs, from the reader.
        viewers(on = true, allTakeVideo = false)
        assertEquals("reader 1080x2340", mirroredInto)
        assertTrue(pipes.isEmpty())
        assertFalse(outputs.asVideo)
        assertEquals(listOf("1080x2340 jpeg"), announced)

        // They leave, and a PC that offers H.264 is the only viewer.
        viewers(on = true, allTakeVideo = true)
        assertTrue(mirroredInto === pipe)
        assertTrue(lane.running)
        assertTrue(outputs.asVideo)
        assertFalse(target.drawing)
        // Pictures that were waiting to become JPEGs are dropped.
        assertEquals(1, dropped)
        // Only viewers it is news to are told: one on an older app sizes its window on every word.
        assertEquals("1080x2340 video, news only", announced.last())
        firstPicture()

        // A viewer that cannot draw H.264 joins: back to the reader, and the encoder is let go of.
        viewers(on = true, allTakeVideo = false)
        assertEquals("reader 1080x2340", mirroredInto)
        assertEquals(1, pipe.closed)
        assertFalse(lane.running)
        assertTrue(target.drawing)
        assertEquals("1080x2340 jpeg, news only", announced.last())

        // It leaves again: a new encoder.
        viewers(on = true, allTakeVideo = true)
        assertEquals(2, pipes.size)
        assertEquals(1, openPipes)
        assertTrue(history.isEmpty())
    }

    @Test
    fun aShortPauseKeepsTheEncoderAndNobodyViewingLetsGoOfIt() {
        sharing()
        viewers(on = true, allTakeVideo = true)
        firstPicture()
        val drawn = pipe.drawn.size

        // A device joins the session: pictures pause for a moment. Nobody counts as
        // viewing meanwhile, so nothing says video is wanted; the encoder is kept all the same.
        viewers(on = false, allTakeVideo = false)
        assertEquals(0, pipe.closed)
        assertTrue(mirroredInto === pipe)
        pipe.events.picture(timer.now)
        advance(500)
        assertEquals(drawn, pipe.drawn.size)
        // They go on: the same encoder, and a keyframe for the new stream.
        viewers(on = true, allTakeVideo = true)
        advance(0)
        assertEquals(1, pipes.size)
        assertTrue(pipe.drawn.last().keyframe)
        assertEquals("1080x2340 video", announced.last())

        // The last viewer leaves, which looks the same at first. Five seconds later
        // the screen is mirrored nowhere, and the encoder is let go of.
        viewers(on = false, allTakeVideo = false)
        assertEquals(0, pipe.closed)
        outputs.mirror(false)
        assertNull(mirroredInto)
        assertEquals(1, pipe.closed)
        assertEquals(0, openPipes)
        assertFalse(target.drawing)

        // Nothing is drawn, watched or kept awake afterwards.
        val drawnAtTheEnd = pipe.drawn.size
        advance(600_000)
        assertEquals(drawnAtTheEnd, pipe.drawn.size)
        assertTrue(later.isEmpty())
        assertTrue(history.isEmpty())

        // Someone views again: video again, from a new encoder.
        viewers(on = true, allTakeVideo = true)
        assertEquals(2, pipes.size)
        assertTrue(mirroredInto === pipe)
    }

    @Test
    fun aPauseWhileSendingJpegsStartsNoEncoder() {
        sharing()
        viewers(on = true, allTakeVideo = false)
        viewers(on = false, allTakeVideo = false)
        assertTrue(pipes.isEmpty())
        assertEquals("reader 1080x2340", mirroredInto)
        // Pictures go again, and now every viewer takes video.
        viewers(on = true, allTakeVideo = true)
        assertEquals(1, openPipes)
    }

    @Test
    fun picturesThatGoAgainAfterAPauseToViewersWithoutVideoLeaveTheEncoder() {
        sharing()
        viewers(on = true, allTakeVideo = true)
        firstPicture()
        // A phone joined during the pause: JPEGs for everyone when it is over.
        viewers(on = false, allTakeVideo = false)
        assertEquals(0, pipe.closed)
        viewers(on = true, allTakeVideo = false)
        assertEquals(1, pipe.closed)
        assertEquals("reader 1080x2340", mirroredInto)
        assertEquals("1080x2340 jpeg", announced.last())
    }

    // ---------------- every way video fails ends the same way ----------------

    /** JPEGs go on: the history has its line, the reader has the screen and the viewers know. */
    private fun assertWentBackToJpeg(reason: String, lines: Int = 1) {
        assertEquals(reason, history.last())
        assertEquals(lines, history.size)
        assertFalse(outputs.asVideo)
        assertEquals("reader 1080x2340", mirroredInto)
        assertTrue(target.drawing)
        assertEquals(0, openPipes)
        assertFalse(lane.running)
        assertEquals("1080x2340 jpeg", announced.last())
    }

    @Test
    fun videoThatWillNotStartGoesBackToJpeg() {
        sharing()
        refuse = true
        viewers(on = true, allTakeVideo = true)
        // The viewers were told video was coming, when pictures started. They are told better.
        assertEquals(listOf("1080x2340 video", "1080x2340 jpeg"), announced)
        assertWentBackToJpeg("The video encoder could not be started.")
        // This try at video is spent.
        assertFalse(wish.wanted)

        // Nothing would start at any size, so video is given up: looking again does not try it.
        refuse = false
        outputs.point()
        viewers(on = false, allTakeVideo = false)
        viewers(on = true, allTakeVideo = true)
        assertTrue(pipes.isEmpty())
        assertEquals(1, history.size)
        assertEquals("1080x2340 jpeg", announced.last())

        // Ten minutes later it is tried again.
        advance(PhoneVideo.FORGET_MS)
        viewers(on = false, allTakeVideo = false)
        viewers(on = true, allTakeVideo = true)
        assertEquals(1, openPipes)
        assertTrue(mirroredInto === pipe)
        assertEquals("1080x2340 video", announced.last())
    }

    @Test
    fun anEncoderThatFailsWhileRunningGoesBackToJpegAndIsTriedSmallerNextTime() {
        sharing()
        viewers(on = true, allTakeVideo = true)
        firstPicture()
        announced.clear()

        // It reports an error on its own thread, and the video passes that on.
        pipe.events.failed(IllegalStateException("codec error"))
        advance(0)
        assertEquals(1, pipe.closed)
        assertEquals(listOf("1080x2340 jpeg"), announced)
        assertWentBackToJpeg("The video encoder stopped working.")
        // This try at video is spent, though video itself is not given up.
        assertFalse(wish.wanted)
        assertTrue(lane.usable)

        // Looking again while nothing has changed is no new try.
        outputs.point()
        viewers(on = true, allTakeVideo = true)
        assertEquals(1, pipes.size)

        // Pictures pause and go again: a new try, at the next size down, and the viewers hear its size.
        viewers(on = false, allTakeVideo = false)
        viewers(on = true, allTakeVideo = true)
        assertEquals(2, pipes.size)
        assertEquals(810 to 1754, pipe.width to pipe.height)
        assertTrue(mirroredInto === pipe)
        assertEquals("810x1754 video", announced.last())
        assertEquals(1, history.size)
    }

    @Test
    fun anEncoderThatStopsGivingPicturesGoesBackToJpeg() {
        sharing()
        viewers(on = true, allTakeVideo = true)
        firstPicture()
        // The screen changes, pictures go in, none comes out.
        repeat(40) {
            if (history.isEmpty()) {
                pipe.events.picture(timer.now)
                advance(100)
            }
        }
        assertWentBackToJpeg("The video encoder stopped giving pictures.")
    }

    @Test
    fun aScreenThatGivesTheEncoderNoPictureGoesBackToJpeg() {
        sharing()
        viewers(on = true, allTakeVideo = true)
        advance(PhoneVideo.FIRST_PICTURE_MS)
        assertWentBackToJpeg("The screen gave the video encoder no picture.")
    }

    @Test
    fun aDisplayThatWillNotTakeTheEncoderGoesBackToJpegAndCountsAsAFailure() {
        sharing()
        displayRefusesVideo = true
        viewers(on = true, allTakeVideo = true)
        // The encoder that had started is let go of.
        assertEquals(1, pipes.size)
        assertWentBackToJpeg("The screen could not be shown to the video encoder.")

        // It counts like any failure, so it is not tried at every pause for ever:
        // twice more, a size down each time, and then video is given up.
        repeat(6) {
            viewers(on = false, allTakeVideo = false)
            viewers(on = true, allTakeVideo = true)
        }
        assertEquals(listOf(1080, 810, 540), pipes.map { it.width })
        assertTrue(video.gaveUp)
        assertWentBackToJpeg("The screen could not be shown to the video encoder.", lines = 3)
    }

    @Test
    fun anEncoderThatNeverComesBackFromADrawIsGivenUpAndJpegsGoOn() {
        sharing()
        viewers(on = true, allTakeVideo = true)
        firstPicture()
        announced.clear()
        val stuck = pipe
        // The video's thread goes into a draw and stays there. Three seconds on, the capture
        // thread, which is not held up by it, takes its regular look.
        stuck.whileDrawing = {
            stuck.whileDrawing = null
            timer.now += 3_000
            captureThread()
            // All of it has happened while the draw is still out: none of it waited for the video's thread.
            assertEquals(listOf(ScreenOutputs.WEDGED), history)
            assertEquals("reader 1080x2340", mirroredInto)
            assertEquals(listOf("1080x2340 jpeg"), announced)
        }
        stuck.events.picture(timer.now)
        assertWentBackToJpeg("The video encoder stopped answering.")
        assertTrue(lane.wedged)
        // The encoder was let go of from outside, to free the thread if anything will.
        assertEquals(1, stuck.aborted)

        // The thread came back, as it happens. It closes what it had; nothing is said twice.
        advance(60_000)
        assertEquals(1, stuck.closed)
        assertEquals(1, history.size)

        // Video is not tried again, now or after any wait: whatever stuck is still in there.
        advance(PhoneVideo.FORGET_MS)
        viewers(on = false, allTakeVideo = false)
        viewers(on = true, allTakeVideo = true)
        assertEquals(1, pipes.size)
        assertEquals("1080x2340 jpeg", announced.last())
        // Nor by the next run of smooth sharing in the same process, which shares the flag.
        assertFalse(VideoLane(video, { it.run() }, { timer.now }, wedge).usable)
        assertFalse(VideoLane(video, { it.run() }, { timer.now }, wedge).wedgedJustNow())
    }

    @Test
    fun aHealthyEncoderIsLookedAtAndLeftAlone() {
        sharing()
        viewers(on = true, allTakeVideo = true)
        firstPicture()
        repeat(100) {
            pipe.events.picture(timer.now)
            advance(100)
            pipe.answerAll()
        }
        assertTrue(history.isEmpty())
        assertTrue(lane.running)
        // One look is waiting, not a hundred.
        assertEquals(1, later.size)
    }

    // ---------------- a turn, and the end ----------------

    @Test
    fun aTurnedPhoneGetsANewEncoderAtTheNewSize() {
        sharing()
        viewers(on = true, allTakeVideo = true)
        firstPicture()
        val upright = pipe

        // The display listener fires for many things that are not a turn.
        assertFalse(outputs.turning(1080, 2340))
        assertEquals(0, upright.closed)

        // A turn, as the service follows it: video at the old shape ends before the mirror changes shape.
        size = 2340 to 1080
        assertTrue(outputs.turning(2340, 1080))
        assertEquals(1, upright.closed)
        assertNull(mirroredInto)
        assertTrue(target.replace(2340, 1080, attach = { if (target.drawing) mirroredInto = it }))
        // The reader is swapped for the new shape but not drawn into: video is what is wanted.
        assertNull(mirroredInto)
        outputs.point()
        outputs.announce()
        assertTrue(mirroredInto === pipe)
        assertEquals(2340 to 1080, lane.size)
        assertEquals(1, openPipes)
        assertEquals("2340x1080 video", announced.last())
        // Its first picture is a keyframe, once the screen has given the new texture one.
        pipe.config()
        pipe.events.picture(timer.now)
        assertTrue(pipe.drawn.single().keyframe)
        assertEquals(listOf("reader 1080x2340"), readersClosed)
        assertTrue(history.isEmpty())
    }

    @Test
    fun aTurnWhileSendingJpegsLeavesVideoAlone() {
        sharing()
        viewers(on = true, allTakeVideo = false)
        assertFalse(outputs.turning(2340, 1080))
        assertTrue(pipes.isEmpty())
    }

    @Test
    fun whenSharingStopsEverythingIsLetGoOfAndNothingStartsAgain() {
        sharing()
        viewers(on = true, allTakeVideo = true)
        firstPicture()
        pipe.events.picture(timer.now)
        announced.clear()

        // As the service's onDestroy does: the display and the reader first, then the video.
        target.close { mirroredInto = null }
        outputs.close()
        assertEquals(1, pipe.closed)
        assertEquals(listOf("reader 1080x2340"), readersClosed)
        assertFalse(lane.running)

        // What was already on its way to the capture thread does nothing, and says nothing.
        outputs.point()
        outputs.mirror(true)
        outputs.failed("late", null)
        outputs.announce()
        advance(600_000)
        assertEquals(1, pipes.size)
        assertEquals(1, readersMade.size)
        assertTrue(history.isEmpty())
        assertTrue(announced.isEmpty())
        assertFalse(video.gaveUp)
    }

    @Test
    fun sharingThatStopsWhileTheScreenIsOffOrLockedLeavesNothingOpen() {
        // A sleeping phone gives the encoder no picture, and that is not a failure.
        screenOn = false
        sharing()
        viewers(on = true, allTakeVideo = true)
        advance(120_000)
        assertTrue(history.isEmpty())
        assertTrue(pipe.drawn.isEmpty())
        // Android ends the sharing because the phone locked.
        target.close()
        outputs.close()
        assertEquals(0, openPipes)
        advance(120_000)
        assertTrue(history.isEmpty())
    }
}
