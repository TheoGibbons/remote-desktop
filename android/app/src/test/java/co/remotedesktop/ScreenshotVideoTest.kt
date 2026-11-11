package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The video encoder fed with pictures that are handed to it one at a time,
 * as the Accessibility service's screenshots are, in place of a screen that
 * Android mirrors into it: when such a picture is encoded and with what
 * time, that a still screen costs nothing and can still be asked for a
 * keyframe, who closes a picture, and what counts as failing. Everything
 * else about the encoder is the same as for a mirrored screen, and is in
 * PhoneVideoTest. Plain JVM, with a made-up encoder.
 */
class ScreenshotVideoTest {
    /** A screenshot that knows whether it has been closed. */
    private class Shot(val name: String) : ScreenPicture {
        var closed = 0
        var failClose = false
        override fun close() {
            closed++
            if (failClose) throw IllegalStateException("already closed")
        }
    }

    private val timer = FakeVideoTimer()
    private val pipes = ArrayList<FakeVideoPipe>()
    private val sent = ArrayList<PhoneVideo.Frame>()
    private val failures = ArrayList<Pair<String, String?>>()
    private var room = true
    private var screenOn = true
    private var refuses: (VideoSetup.Attempt) -> Boolean = { false }

    private val video = PhoneVideo(
        encoders = { listOf(VideoSetup.Encoder("hardware", hardware = true, constantBitrate = true) { _, _ -> true }) },
        open = { attempt, _, _, events ->
            if (refuses(attempt)) throw IllegalStateException("cannot configure ${attempt.width}")
            FakeVideoPipe(attempt, events).also { pipes += it }
        },
        timer = timer,
        clock = { timer.now },
        hasRoom = { room },
        send = { frame -> sent += frame; VideoSent.SENT },
        onFailed = { reason, detail -> failures += reason to detail },
        screenOn = { screenOn },
        spare = { it() },
        fed = true,
        sizesForgottenAfterMs = DAY,
    )

    private val pipe get() = pipes.last()

    private fun started(): FakeVideoPipe {
        assertTrue(video.start(1080, 2340))
        video.setSending(true)
        pipe.config()
        return pipe
    }

    /** A screenshot is handed over and, a moment later, is in the texture. */
    private fun screenshot(name: String, capturedAt: Long = timer.now): Shot {
        val shot = Shot(name)
        video.show(shot, capturedAt)
        pipe.arrived()
        return shot
    }

    // ---------------- when a screenshot is encoded ----------------

    @Test
    fun aScreenshotIsEncodedOnceItIsInTheTextureWithTheTimeItWasTakenAt() {
        started()
        // Nothing is drawn before the first picture: the texture is empty.
        timer.advance(1_000)
        assertTrue(pipe.drawn.isEmpty())

        // The screenshot was taken 35 ms before it got here.
        val first = Shot("first")
        video.show(first, timer.now - 35)
        assertEquals(listOf<ScreenPicture>(first), pipe.shown)
        // Handed over, and not in the texture yet: drawing now would encode what was there before.
        timer.advance(5)
        assertTrue(pipe.drawn.isEmpty())
        pipe.arrived()
        assertEquals(1, pipe.drawn.size)
        assertTrue(pipe.drawn[0].keyframe)
        pipe.answerAll()
        sent.single().let {
            assertTrue(it.keyframe)
            assertFalse(it.repeat)
            // When the screen looked like that, and not when it was drawn into the texture.
            assertEquals(timer.now - 40, it.capturedAtMs)
            assertEquals(1080 to 2340, it.width to it.height)
        }

        // The next one, 400 ms later, is an ordinary picture.
        timer.advance(400)
        pipe.answerAll()
        sent.clear()
        screenshot("second", capturedAt = timer.now - 20)
        pipe.answerAll()
        sent.single().let {
            assertFalse(it.keyframe)
            assertFalse(it.repeat)
            assertEquals(timer.now - 20, it.capturedAtMs)
        }
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aStillScreenIsSharpenedTwiceAndThenCostsNothing() {
        started()
        screenshot("only")
        timer.advance(1_000)
        pipe.answerAll()
        // The picture, and the two repeats that sharpen it.
        assertEquals(listOf(false, true, true), sent.map { it.repeat })
        // No more screenshots are handed over (the screen has not changed), and nothing more is
        // drawn, watched or reported, however long that lasts and whatever the phone's screen does.
        screenOn = false
        timer.advance(3_600_000)
        assertEquals(3, pipe.drawn.size)
        assertTrue(failures.isEmpty())
        assertTrue(video.running)
    }

    @Test
    fun aKeyframeAskedForOnAStillScreenIsTheLastScreenshotDrawnAgain() {
        started()
        screenshot("only", capturedAt = timer.now - 30)
        timer.advance(1_000)
        pipe.answerAll()
        timer.advance(60_000)
        sent.clear()
        val shownBefore = pipe.shown.size

        video.keyframe(byViewer = true)
        pipe.answerAll()
        sent.single().let {
            assertTrue(it.keyframe)
            // The same screen as before: the viewer leaves it out of its delay figures.
            assertTrue(it.repeat)
            assertEquals(timer.now, it.capturedAtMs)
        }
        // No new picture was needed for it: the texture still holds the last one.
        assertEquals(shownBefore, pipe.shown.size)
    }

    @Test
    fun aScreenshotWaitsForRoomAndTheNewestIsTheOneThatGoes() {
        started()
        screenshot("first")
        timer.advance(1_000)
        pipe.answerAll()
        val drawn = pipe.drawn.size
        sent.clear()

        // The viewer is behind. Two screenshots come meanwhile; neither is encoded.
        room = false
        screenshot("second", capturedAt = timer.now - 10)
        timer.advance(400)
        screenshot("third", capturedAt = timer.now - 10)
        timer.advance(200)
        assertEquals(drawn, pipe.drawn.size)

        // Room again: one picture is encoded, and it is the third, with the third's time.
        val thirdAt = timer.now - 210
        room = true
        timer.advance(20)
        assertEquals(drawn + 1, pipe.drawn.size)
        pipe.answerAll()
        assertEquals(thirdAt, sent.single().capturedAtMs)
        assertFalse(sent.single().repeat)
    }

    @Test
    fun afterAPauseNothingGoesUntilTheNextScreenshotWhichIsTheKeyframe() {
        started()
        screenshot("before")
        timer.advance(1_000)
        pipe.answerAll()
        sent.clear()
        val drawn = pipe.drawn.size

        // A device joins the session: pictures stop for a moment, and no screenshots are taken.
        video.setSending(false)
        timer.advance(3_000)
        // They go on. The texture still holds the screen from before the pause, which
        // is not sent as if it were the screen now, even to a viewer that asks.
        video.setSending(true)
        video.keyframe(byViewer = false)
        timer.advance(300)
        assertEquals(drawn, pipe.drawn.size)

        // The next screenshot starts the new stream: a keyframe, with its own time, and no repeat.
        screenshot("after", capturedAt = timer.now - 25)
        pipe.answerAll()
        sent.single().let {
            assertTrue(it.keyframe)
            assertFalse(it.repeat)
            assertEquals(timer.now - 25, it.capturedAtMs)
        }
        // The same encoder throughout.
        assertEquals(1, pipes.size)
        assertEquals(0, pipe.closed)
        // And it is sharpened like any other.
        timer.advance(1_000)
        pipe.answerAll()
        assertEquals(listOf(false, true, true), sent.map { it.repeat })
        assertTrue(failures.isEmpty())
    }

    // ---------------- who closes a screenshot ----------------

    @Test
    fun aScreenshotIsKeptUntilTheNextHasTakenItsPlaceAndTheLastGoesWithTheEncoder() {
        started()
        val first = screenshot("first")
        assertEquals(0, first.closed)
        timer.advance(400)
        val second = screenshot("second")
        // The first has been drawn over. The second may still be drawn from.
        assertEquals(1, first.closed)
        assertEquals(0, second.closed)

        video.stop()
        assertEquals(1, pipe.closed)
        assertEquals(1, second.closed)
        assertEquals(1, first.closed)

        // With no encoder running, a screenshot is closed at once and goes nowhere.
        val late = Shot("late")
        video.show(late, timer.now)
        assertEquals(1, late.closed)
        assertEquals(2, pipe.shown.size)
    }

    @Test
    fun aScreenshotThatWillNotCloseDoesNotStopTheNext() {
        started()
        val first = screenshot("first")
        first.failClose = true
        timer.advance(400)
        screenshot("second")
        pipe.answerAll()
        assertEquals(1, first.closed)
        assertTrue(failures.isEmpty())
        assertTrue(video.running)
    }

    // ---------------- failing ----------------

    @Test
    fun aScreenshotThatCannotBeDrawnEndsVideoOnceAndIsClosed() {
        started()
        val first = screenshot("first")
        pipe.answerAll()
        pipe.failShows = IllegalStateException("Surface was abandoned")
        val second = Shot("second")
        video.show(second, timer.now)
        assertEquals(listOf("The video encoder stopped working."), failures.map { it.first })
        assertTrue(failures.single().second!!.contains("abandoned"))
        assertFalse(video.running)
        assertEquals(1, pipe.closed)
        assertEquals(1, first.closed)
        assertEquals(1, second.closed)
    }

    @Test
    fun aScreenshotThatNeverReachesTheTextureIsAFailureAndNoneHandedOverIsNot() {
        started()
        // Three seconds and more with nothing handed over: nothing was expected.
        timer.advance(10_000)
        assertTrue(failures.isEmpty())

        val lost = Shot("lost")
        video.show(lost, timer.now)
        timer.advance(PhoneVideo.FIRST_PICTURE_MS - 1)
        assertTrue(failures.isEmpty())
        timer.advance(1)
        assertEquals(listOf("A picture of the screen did not reach the video encoder."), failures.map { it.first })
        assertFalse(video.running)
        assertEquals(1, lost.closed)
        assertEquals(1, pipe.closed)
    }

    @Test
    fun aLaterScreenshotThatNeverReachesTheTextureIsAFailureToo() {
        started()
        screenshot("first")
        timer.advance(1_000)
        pipe.answerAll()
        timer.advance(5_000)
        assertTrue(failures.isEmpty())

        video.show(Shot("lost"), timer.now)
        timer.advance(PhoneVideo.FIRST_PICTURE_MS)
        assertEquals(listOf("A picture of the screen did not reach the video encoder."), failures.map { it.first })
    }

    @Test
    fun anEncoderThatSwallowsScreenshotsIsNoticed() {
        started()
        screenshot("first")
        // The picture and its two repeats went in; nothing comes out.
        timer.advance(1_000 + PhoneVideo.STALL_MS)
        assertEquals(listOf("The video encoder stopped giving pictures."), failures.map { it.first })
        assertEquals(1, pipe.closed)
    }

    // ---------------- sizes that failed ----------------

    @Test
    fun aSizeThatFailedIsTriedAgainADayLater() {
        started()
        screenshot("first")
        pipe.events.failed(IllegalStateException("codec error"))
        timer.advance()
        assertEquals(1, failures.size)

        // The next try is at three quarters size, and so is one an hour later.
        assertTrue(video.start(1080, 2340))
        assertEquals(810 to 1754, video.size)
        video.stop()
        timer.advance(3_600_000)
        assertTrue(video.start(1080, 2340))
        assertEquals(810 to 1754, video.size)
        video.stop()

        // A day after the failure it may have been that moment's trouble: full size again.
        timer.advance(DAY)
        assertTrue(video.start(1080, 2340))
        assertEquals(1080 to 2340, video.size)
    }

    // ---------------- handing over from another thread ----------------

    @Test
    fun onlyTheNewestScreenshotWaitsForTheVideosThread() {
        val posted = ArrayList<Runnable>()
        val lane = VideoLane(video, post = { posted += it }, clock = { timer.now })
        started()

        val first = Shot("first")
        val second = Shot("second")
        lane.show(first, 1)
        lane.show(second, 2)
        // The video's thread has come for neither yet: the first gives way.
        assertEquals(1, first.closed)
        assertEquals(0, second.closed)

        posted.forEach { it.run() }
        assertEquals(listOf<ScreenPicture>(second), pipe.shown)
        pipe.arrived()
        pipe.answerAll()
        assertEquals(2L, sent.single().capturedAtMs)
        assertEquals(0, second.closed)
    }

    @Test
    fun aVideoThatWasGivenUpClosesWhatItIsHanded() {
        val wedged = VideoLane(video, post = { it.run() }, clock = { timer.now }, wedge = AtomicBoolean(true))
        val shot = Shot("late")
        wedged.show(shot, 1)
        assertEquals(1, shot.closed)
        assertTrue(pipes.isEmpty())
    }

    @Test
    fun aScreenshotStillWaitingWhenTheVideoIsGivenUpIsClosed() {
        val posted = ArrayList<Runnable>()
        val wedge = AtomicBoolean()
        val lane = VideoLane(video, post = { posted += it }, clock = { timer.now }, wedge = wedge, patienceMs = 2_000)
        started()
        val shot = Shot("waiting")
        lane.show(shot, 1)
        // The video's thread is inside the encoder, and stays there.
        pipe.whileDrawing = { timer.now += 2_000; assertTrue(lane.wedgedJustNow()) }
        screenshot("stuck")
        assertTrue(wedge.get())
        assertEquals(1, shot.closed)
        // Whatever the lost thread still does, the screenshot is not drawn.
        posted.forEach { it.run() }
        assertEquals(listOf("stuck"), pipe.shown.map { (it as Shot).name })
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
