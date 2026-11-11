package co.remotedesktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clock and a queue of tasks that a test moves forward by hand, standing in for the capture thread. */
internal class FakeVideoTimer(var now: Long = 10_000) : PhoneVideo.Timer {
    private val tasks = ArrayList<Pair<Long, Runnable>>()
    override fun post(task: Runnable) { tasks += now to task }
    override fun postDelayed(task: Runnable, delayMs: Long) { tasks += (now + delayMs) to task }
    override fun remove(task: Runnable) { tasks.removeAll { it.second === task } }

    /** Runs everything that falls due in the next [ms], each at its own time. */
    fun advance(ms: Long = 0) {
        val until = now + ms
        while (true) {
            val next = tasks.filter { it.first <= until }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            now = maxOf(now, next.first)
            next.second.run()
        }
        now = until
    }
}

/** An encoder that does what a test tells it to. */
internal class FakeVideoPipe(val attempt: VideoSetup.Attempt, val events: VideoPipe.Events) : VideoPipe {
    class Drawn(val ptsUs: Long, val keyframe: Boolean)

    override val width = attempt.width
    override val height = attempt.height
    override val bitrate = attempt.bitrate
    val drawn = ArrayList<Drawn>()
    var closed = 0
    var failDraws: RuntimeException? = null
    var failClose = false

    override fun draw(ptsUs: Long, keyframe: Boolean) {
        check(closed == 0) { "Drawn into after it was closed" }
        failDraws?.let { throw it }
        drawn += Drawn(ptsUs, keyframe)
    }

    override fun close() {
        closed++
        if (failClose) throw IllegalStateException("close failed")
    }

    companion object {
        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        val SETS = bytes(0, 0, 0, 1, 0x67, 0x64, 0, 0x32, 0, 0, 0, 1, 0x68, 0xEE)
        val KEY = bytes(0, 0, 0, 1, 0x65, 0xB8, 0x40)
        val PICTURE = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x02)
    }

    /** The encoder gives its parameter sets, as it does once before its first picture. */
    fun config() = events.encoded(SETS, 0, config = true)

    /** The encoder gives the picture for one draw: a keyframe if it was asked for one, unless told otherwise. */
    fun answer(draw: Drawn, keyframe: Boolean = draw.keyframe) =
        events.encoded(if (keyframe) KEY else PICTURE, draw.ptsUs, config = false)

    private var answered = 0

    /** Answers every draw that has not been answered yet. */
    fun answerAll() {
        while (answered < drawn.size) answer(drawn[answered++])
    }
}

/**
 * Everything decided about the phone's video encoder, with a made-up
 * encoder in place of the real one: which is tried, when a picture is
 * encoded, what is sent, and every way of failing, each of which has to end
 * with the encoder let go of and one report, so that pictures go on as JPEGs.
 * Plain JVM.
 */
class PhoneVideoTest {
    private val timer = FakeVideoTimer()
    private val pipes = ArrayList<FakeVideoPipe>()
    private val sent = ArrayList<PhoneVideo.Frame>()
    private val failures = ArrayList<Pair<String, String?>>()
    private var room = true
    private var screenOn = true
    private var sendResult = VideoSent.SENT
    private var encoders = listOf(
        VideoSetup.Encoder("hardware", hardware = true, constantBitrate = true) { _, _ -> true },
        VideoSetup.Encoder("software", hardware = false, constantBitrate = false) { _, _ -> true })

    /** Which attempts cannot be opened. */
    private var refuses: (VideoSetup.Attempt) -> Boolean = { false }

    private val video = PhoneVideo(
        encoders = { encoders },
        open = { attempt, _, _, events ->
            if (refuses(attempt)) throw IllegalStateException("cannot configure ${attempt.encoder} ${attempt.width}")
            FakeVideoPipe(attempt, events).also { pipes += it }
        },
        timer = timer,
        clock = { timer.now },
        hasRoom = { room },
        send = { frame -> sent += frame; sendResult },
        onFailed = { reason, detail -> failures += reason to detail },
        screenOn = { screenOn },
    )

    private val pipe get() = pipes.last()

    /** Video running and sending, its encoder having made the first picture of a screen that then stays still. */
    private fun running(): FakeVideoPipe {
        assertTrue(video.start(1080, 2340))
        video.setSending(true)
        pipe.config()
        pipe.events.picture(timer.now)
        pipe.answerAll()
        // The two repeats that sharpen a still screen.
        timer.advance(700)
        pipe.answerAll()
        assertEquals(3, pipe.drawn.size)
        sent.clear()
        return pipe
    }

    private fun screenChanges(at: Long = timer.now) = pipe.events.picture(at)

    // ---------------- starting ----------------

    @Test
    fun theFirstPictureIsAKeyframeWithItsParameterSetsAndTheStillScreenIsSharpened() {
        assertTrue(video.start(1080, 2340))
        video.setSending(true)
        assertEquals("hardware", pipe.attempt.encoder)
        assertEquals(1080 to 2340, video.size)
        assertEquals(2_925_000, video.bitrate)
        // Nothing is drawn until the screen has given a picture: the texture is empty.
        timer.advance(500)
        assertTrue(pipe.drawn.isEmpty())

        pipe.config()
        screenChanges(at = timer.now - 4)
        assertEquals(1, pipe.drawn.size)
        assertTrue(pipe.drawn[0].keyframe)
        // The capture time goes in as the stamp and comes back with the picture.
        assertEquals((timer.now - 4) * 1000, pipe.drawn[0].ptsUs)
        pipe.answerAll()
        sent.single().let {
            assertTrue(it.keyframe)
            assertFalse(it.repeat)
            assertArrayEquals(FakeVideoPipe.SETS + FakeVideoPipe.KEY, it.bytes)
            assertEquals(1080 to 2340, it.width to it.height)
            assertEquals(timer.now - 4, it.capturedAtMs)
        }

        timer.advance(1_000)
        pipe.answerAll()
        assertEquals(listOf(false, true, true), sent.map { it.repeat })
        assertEquals(listOf(true, false, false), sent.map { it.keyframe })
        // Stamps only go up.
        assertEquals(pipe.drawn.map { it.ptsUs }.sorted(), pipe.drawn.map { it.ptsUs })
        // Then a still screen costs nothing.
        timer.advance(60_000)
        assertEquals(3, pipe.drawn.size)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun anEncoderThatRefusesIsFollowedByTheNextAndThenBySmallerSizes() {
        // Only half size will open, and only on the second encoder.
        refuses = { it.width > 540 || it.encoder == "hardware" }
        assertTrue(video.start(1080, 2340))
        assertEquals("software", pipe.attempt.encoder)
        assertEquals(540 to 1170, video.size)
        assertEquals(1, pipes.size)
        assertTrue(failures.isEmpty())
        assertFalse(video.gaveUp)

        // Its pictures say the size they really are.
        video.setSending(true)
        pipe.config()
        screenChanges()
        pipe.answerAll()
        assertEquals(540 to 1170, sent.single().width to sent.single().height)
    }

    @Test
    fun whenNoEncoderStartsVideoIsOffAndSaysWhy() {
        refuses = { true }
        assertFalse(video.start(1080, 2340))
        assertFalse(video.running)
        assertNull(video.size)
        assertEquals("The video encoder could not be started.", video.lastFailure.first)
        assertTrue(video.lastFailure.second!!.contains("cannot configure"))
        assertTrue(pipes.isEmpty())
        // The caller is told by the answer; nothing is reported a second time.
        assertTrue(failures.isEmpty())

        // A phone with no encoder for the size at all.
        encoders = emptyList()
        assertFalse(video.start(1080, 2340))
        assertEquals("This phone has no video encoder for its screen's size." to null, video.lastFailure)
        // That was the second failure.
        assertTrue(video.gaveUp)
    }

    @Test
    fun aPhoneWhoseListOfEncodersCannotBeReadHasNoVideo() {
        val broken = PhoneVideo({ throw IllegalStateException("codec list") }, { _, _, _, _ -> error("unreachable") },
            timer, { timer.now }, { true }, { VideoSent.SENT }, { _, _ -> })
        assertFalse(broken.start(1080, 2340))
        assertEquals("The video encoder could not be started.", broken.lastFailure.first)
    }

    // ---------------- every way of failing ends in one report and nothing left open ----------------

    private fun assertFailedOnce(reason: String, of: FakeVideoPipe) {
        assertEquals(listOf(reason), failures.map { it.first })
        assertEquals(1, of.closed)
        assertFalse(video.running)
        assertNull(video.size)
        // Nothing more is drawn or sent, whatever happens next.
        val drawn = of.drawn.size
        sent.clear()
        of.events.picture(timer.now)
        of.events.encoded(FakeVideoPipe.PICTURE, 1, config = false)
        of.events.failed(IllegalStateException("again"))
        video.keyframe(byViewer = false)
        video.resend()
        timer.advance(10_000)
        assertEquals(drawn, of.drawn.size)
        assertTrue(sent.isEmpty())
        assertEquals(1, failures.size)
        assertEquals(1, of.closed)
    }

    @Test
    fun aDrawThatThrowsEndsVideo() {
        val pipe = running()
        pipe.failDraws = IllegalStateException("eglSwapBuffers 0x300d")
        screenChanges()
        assertEquals("java.lang.IllegalStateException: eglSwapBuffers 0x300d", failures.single().second)
        assertFailedOnce("The video encoder stopped working.", pipe)
    }

    @Test
    fun anErrorFromTheEncoderEndsVideo() {
        val pipe = running()
        // It comes in on the encoder's thread, and is dealt with on the capture thread.
        pipe.events.failed(IllegalStateException("codec error"))
        assertTrue(failures.isEmpty())
        timer.advance()
        assertFailedOnce("The video encoder stopped working.", pipe)
    }

    @Test
    fun anEncoderThatWillNotCloseIsStillLetGoOf() {
        val pipe = running()
        pipe.failClose = true
        pipe.events.failed(IllegalStateException("codec error"))
        timer.advance()
        assertEquals(1, failures.size)
        assertFalse(video.running)
    }

    @Test
    fun aScreenThatGivesNoPictureEndsVideo() {
        assertTrue(video.start(1080, 2340))
        video.setSending(true)
        timer.advance(PhoneVideo.FIRST_PICTURE_MS - 1)
        assertTrue(failures.isEmpty())
        timer.advance(1)
        assertFailedOnce("The screen gave the video encoder no picture.", pipe)
    }

    @Test
    fun aPhoneWhoseScreenIsOffGivesNoPictureAndThatIsNoFailure() {
        screenOn = false
        assertTrue(video.start(1080, 2340))
        video.setSending(true)
        timer.advance(60_000)
        assertTrue(failures.isEmpty())
        assertTrue(video.running)
        assertTrue(pipe.drawn.isEmpty())
        // It wakes, and the picture comes.
        screenOn = true
        pipe.config()
        screenChanges()
        pipe.answerAll()
        timer.advance(1_000)
        pipe.answerAll()
        timer.advance(60_000)
        assertTrue(sent.first().keyframe)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun anEncoderThatStopsAnsweringEndsVideo() {
        val pipe = running()
        // Pictures go in, as the screen changes, and none comes out.
        val before = pipe.drawn.size
        var rounds = 0
        while (failures.isEmpty() && rounds++ < 100) {
            screenChanges()
            timer.advance(100)
        }
        // Noticed within a few seconds, not after a hundred pictures.
        assertTrue(pipe.drawn.size - before in 3..45)
        assertFailedOnce("The video encoder stopped giving pictures.", pipe)
    }

    @Test
    fun anEncoderThatDiesOnAStillScreenIsNoticedToo() {
        val pipe = running()
        // One change, then nothing: the picture and its two repeats go in, and nothing comes out.
        screenChanges()
        timer.advance(10_000)
        assertEquals(3 + 3, pipe.drawn.size)
        assertFailedOnce("The video encoder stopped giving pictures.", pipe)
    }

    @Test
    fun anEncoderThatPassesOverAPictureIsNotStalled() {
        val pipe = running()
        // It skips the last repeat of a still screen, as one may to keep to its bitrate.
        screenChanges()
        timer.advance(200)
        pipe.answerAll()
        timer.advance(60_000)
        assertEquals(3 + 3, pipe.drawn.size)
        assertTrue(failures.isEmpty())
        assertTrue(video.running)
    }

    @Test
    fun anEncoderThatKeepsItsNewestPictureUntilTheNextComesIsNotStalled() {
        assertTrue(video.start(1080, 2340))
        video.setSending(true)
        pipe.config()
        // Each picture comes out only when the one after it goes in.
        var answered = 0
        fun pushOut() { while (answered < pipe.drawn.size - 1) pipe.answer(pipe.drawn[answered++]) }
        repeat(10) {
            screenChanges()
            pushOut()
            timer.advance(50)
            pushOut()
        }
        // The screen stops. The repeats push the last real picture out; the last repeat stays in.
        repeat(100) {
            timer.advance(100)
            pushOut()
        }
        assertTrue(failures.isEmpty())
        assertTrue(video.running)
        assertEquals(pipe.drawn.size - 1, sent.size)
        // Every changed picture got out; only a repeat is left inside.
        assertEquals(10, sent.count { !it.repeat })
    }

    @Test
    fun aKeyframeWithNoParameterSetsEndsVideo() {
        assertTrue(video.start(1080, 2340))
        video.setSending(true)
        // No config ever came.
        screenChanges()
        pipe.answerAll()
        timer.advance()
        assertTrue(sent.isEmpty())
        assertFailedOnce("The video encoder stopped working.", pipe)
    }

    @Test
    fun anEncoderThatNeverMakesTheKeyframeItIsAskedForEndsVideo() {
        val pipe = running()
        var answered = pipe.drawn.size
        var asked = 0
        video.keyframe(byViewer = false)
        // Each time it is asked again, and each time an ordinary picture comes out.
        for (round in 1..50) {
            if (failures.isNotEmpty()) break
            timer.advance(40)
            while (answered < pipe.drawn.size) {
                if (pipe.drawn[answered].keyframe) asked++
                pipe.answer(pipe.drawn[answered++], keyframe = false)
            }
        }
        assertEquals(PhoneVideo.KEY_TRIES, asked)
        // The ordinary pictures it made instead were sent all the same: they are part of the stream.
        assertEquals(PhoneVideo.KEY_TRIES - 1, sent.size)
        assertFailedOnce("The video encoder stopped working.", pipe)
    }

    @Test
    fun aKeyframeThatComesOneTryLateIsEnough() {
        val pipe = running()
        video.keyframe(byViewer = false)
        timer.advance()
        val first = pipe.drawn.last()
        assertTrue(first.keyframe)
        pipe.answer(first, keyframe = false)
        // Asked again by itself.
        timer.advance(40)
        val second = pipe.drawn.last()
        assertTrue(second.keyframe && second !== first)
        pipe.answer(second, keyframe = true)
        timer.advance(5_000)
        assertTrue(failures.isEmpty())
        assertEquals(listOf(false, true), sent.take(2).map { it.keyframe })
    }

    @Test
    fun afterTwoFailuresVideoIsGivenUp() {
        running().events.failed(IllegalStateException("one"))
        timer.advance()
        assertFalse(video.gaveUp)
        assertTrue(video.usable)

        // The next try starts a new encoder.
        running().events.failed(IllegalStateException("two"))
        timer.advance()
        assertEquals(2, failures.size)
        assertTrue(video.gaveUp)
        assertFalse(video.usable)
        assertEquals(2, pipes.size)
        assertTrue(pipes.all { it.closed == 1 })

        // And then none is started, however often it is asked for.
        assertFalse(video.start(1080, 2340))
        assertEquals(2, pipes.size)
    }

    // ---------------- what is encoded, and what is sent ----------------

    @Test
    fun aPictureOnceEncodedIsAlwaysHandedOverToBeSent() {
        val pipe = running()
        screenChanges()
        assertEquals(4, pipe.drawn.size)
        // No room any more by the time it comes out: it goes all the same.
        room = false
        pipe.answerAll()
        assertEquals(1, sent.size)

        // What there is no room for is the next encode.
        screenChanges()
        timer.advance(500)
        assertEquals(4, pipe.drawn.size)
        video.keyframe(byViewer = false)
        timer.advance(500)
        assertEquals(4, pipe.drawn.size)
        room = true
        timer.advance(20)
        // One picture for all that waited: the newest screen, as the keyframe that was wanted.
        assertEquals(5, pipe.drawn.size)
        assertTrue(pipe.drawn.last().keyframe)
        pipe.answerAll()
        assertTrue(sent.last().keyframe)
        assertFalse(sent.last().repeat)
    }

    @Test
    fun aPauseKeepsTheEncoderAndTheNextPictureIsAKeyframe() {
        val pipe = running()
        video.setSending(false)
        // The screen goes on changing; nothing is encoded for nobody.
        repeat(5) {
            screenChanges()
            timer.advance(100)
        }
        video.keyframe(byViewer = true)
        timer.advance(1_000)
        assertEquals(3, pipe.drawn.size)
        assertTrue(failures.isEmpty())

        video.setSending(true)
        timer.advance()
        assertEquals(4, pipe.drawn.size)
        assertTrue(pipe.drawn.last().keyframe)
        // The same encoder: none was made, none was closed.
        assertEquals(1, pipes.size)
        assertEquals(0, pipe.closed)
        pipe.answerAll()
        assertTrue(sent.single().keyframe)
        assertFalse(sent.single().repeat)
        // Stamped as the pause ended: nobody waited a second and a half for this picture.
        assertEquals(timer.now, sent.single().capturedAtMs)
    }

    @Test
    fun aPauseOnAStillScreenResumesWithAKeyframeOfTheSamePicture() {
        val pipe = running()
        video.setSending(false)
        timer.advance(2_000)
        video.setSending(true)
        timer.advance()
        pipe.answerAll()
        sent.single().let {
            assertTrue(it.keyframe)
            assertTrue(it.repeat)
            assertFalse(it.resend)
        }
    }

    @Test
    fun aPictureThatCouldNotBeSentIsFollowedByAKeyframe() {
        val pipe = running()
        screenChanges()
        sendResult = VideoSent.NEEDS_KEYFRAME
        pipe.answerAll()
        sendResult = VideoSent.SENT
        timer.advance(40)
        assertTrue(pipe.drawn.last().keyframe)
        assertEquals(5, pipe.drawn.size)
        pipe.answerAll()
        assertTrue(sent.last().keyframe)
        // Once: the sharpening repeats that follow are ordinary.
        timer.advance(2_000)
        assertEquals(1, pipe.drawn.drop(4).count { it.keyframe })
    }

    @Test
    fun aPictureLostBeforeAKeyframeThatIsAlreadyDrawnAsksForNoSecondOne() {
        val pipe = running()
        screenChanges()
        val lost = pipe.drawn.last()
        timer.advance(40)
        video.keyframe(byViewer = false)
        timer.advance()
        assertTrue(pipe.drawn.last().keyframe)
        val drawn = pipe.drawn.size
        // The older picture comes out now and cannot be sent. The keyframe behind it is what was needed.
        sendResult = VideoSent.NEEDS_KEYFRAME
        pipe.answer(lost)
        sendResult = VideoSent.SENT
        timer.advance(20)
        pipe.answer(pipe.drawn[drawn - 1])
        timer.advance(2_000)
        assertEquals(1, pipe.drawn.drop(drawn - 1).count { it.keyframe })
    }

    @Test
    fun aPictureThatWentUnsentIsFollowedByAKeyframeWithoutOneBeingDrawnForIt() {
        val pipe = running()
        screenChanges()
        // Nobody is being sent video at this moment: a device has just joined the session.
        sendResult = VideoSent.NOT_SENT
        pipe.answerAll()
        timer.advance(40)
        // Nothing is encoded to make up for it: there may be nobody to send that to either.
        assertEquals(4, pipe.drawn.size)

        // But whatever is drawn next was built on the picture that never went: it is a keyframe.
        sendResult = VideoSent.SENT
        screenChanges()
        timer.advance(40)
        assertEquals(5, pipe.drawn.size)
        assertTrue(pipe.drawn.last().keyframe)
        pipe.answerAll()
        assertTrue(sent.last().keyframe)
        // Once.
        screenChanges()
        timer.advance(40)
        assertFalse(pipe.drawn.last().keyframe)
    }

    @Test
    fun onAStillScreenTheRepeatAfterAnUnsentPictureIsTheKeyframe() {
        val pipe = running()
        screenChanges()
        sendResult = VideoSent.NOT_SENT
        pipe.answerAll()
        sendResult = VideoSent.SENT
        // The screen does not change again. The repeat that sharpens it comes 150 ms later.
        timer.advance(200)
        assertEquals(5, pipe.drawn.size)
        assertTrue(pipe.drawn.last().keyframe)
        pipe.answerAll()
        sent.last().let {
            assertTrue(it.keyframe)
            assertTrue(it.repeat)
        }
    }

    @Test
    fun anUnsentPictureFromBeforeAKeyframeAsksForNoSecondOne() {
        val pipe = running()
        screenChanges()
        val unsent = pipe.drawn.last()
        timer.advance(40)
        video.keyframe(byViewer = false)
        timer.advance()
        val drawn = pipe.drawn.size
        assertTrue(pipe.drawn.last().keyframe)
        sendResult = VideoSent.NOT_SENT
        pipe.answer(unsent)
        sendResult = VideoSent.SENT
        pipe.answer(pipe.drawn.last())
        timer.advance(2_000)
        assertEquals(1, pipe.drawn.drop(drawn - 1).count { it.keyframe })
    }

    @Test
    fun viewersRequestsMakeOneKeyframeASecondAndAnUnacknowledgedPictureIsMarked() {
        val pipe = running()
        video.keyframe(byViewer = true)
        video.keyframe(byViewer = true)
        timer.advance()
        assertEquals(1, pipe.drawn.count { it.keyframe } - 1)
        video.keyframe(byViewer = true)
        timer.advance(900)
        assertEquals(2, pipe.drawn.count { it.keyframe })
        timer.advance(200)
        assertEquals(3, pipe.drawn.count { it.keyframe })
        pipe.answerAll()
        assertTrue(sent.none { it.resend })

        sent.clear()
        timer.advance(2_000)
        pipe.answerAll()
        sent.clear()
        video.resend()
        timer.advance()
        pipe.answerAll()
        sent.single().let {
            assertTrue(it.keyframe)
            assertTrue(it.repeat)
            assertTrue(it.resend)
        }
    }

    // ---------------- stopping ----------------

    @Test
    fun nothingOfAStoppedEncoderIsSentAndANewOneStartsClean() {
        val old = running()
        screenChanges()
        val late = old.drawn.last()
        video.stop()
        assertEquals(1, old.closed)
        assertFalse(video.running)
        // What the old encoder still says is nobody's business any more.
        old.answer(late)
        old.events.failed(IllegalStateException("late"))
        timer.advance(10_000)
        assertTrue(sent.isEmpty())
        assertTrue(failures.isEmpty())

        // A turn: the same video, a new encoder at the new size, and its first picture is a keyframe.
        assertTrue(video.start(2340, 1080))
        assertEquals(2340 to 1080, video.size)
        val fresh = pipe
        assertTrue(fresh !== old)
        fresh.config()
        timer.advance(500)
        // Not before the screen has given the new texture a picture.
        assertTrue(fresh.drawn.isEmpty())
        screenChanges()
        fresh.answerAll()
        sent.single().let {
            assertTrue(it.keyframe)
            assertEquals(2340 to 1080, it.width to it.height)
        }
        assertEquals(1, old.closed)
        assertFalse(video.gaveUp)
    }

    @Test
    fun whenTheServiceEndsTheEncoderIsLetGoOfAndNoneIsStartedAgain() {
        val pipe = running()
        screenChanges()
        video.shutDown()
        assertEquals(1, pipe.closed)
        assertFalse(video.running)
        assertFalse(video.usable)
        timer.advance(60_000)
        assertEquals(4, pipe.drawn.size)

        assertFalse(video.start(1080, 2340))
        assertEquals(1, pipes.size)
        // Not a failure: nothing went wrong.
        assertFalse(video.gaveUp)
        assertTrue(failures.isEmpty())
        // Twice is as good as once.
        video.shutDown()
        video.stop()
        assertEquals(1, pipe.closed)
    }

    @Test
    fun theLedgerTellsAStalledEncoderFromOneThatHoldsAPictureBack() {
        val ledger = VideoLedger()
        val draw = VideoPacer.Draw(keyframe = false, repeat = false, resend = false, capturedAt = 0)
        assertFalse(ledger.stalled(now = 5_000, afterMs = 2_000))
        ledger.drawn(1_000, draw, now = 1_000)
        ledger.drawn(2_000, draw, now = 1_033)
        // Two that have not come out could be an encoder passing pictures over.
        assertFalse(ledger.stalled(now = 9_000, afterMs = 2_000))
        ledger.drawn(3_000, draw, now = 1_066)
        assertFalse(ledger.stalled(now = 2_999, afterMs = 2_000))
        assertTrue(ledger.stalled(now = 3_000, afterMs = 2_000))
        // The first comes out after the others went in: held back, not stalled.
        assertSame(draw, ledger.out(1_000, now = 1_070)!!.draw)
        assertTrue(ledger.owed())
        ledger.drawn(4_000, draw, now = 1_100)
        assertFalse(ledger.stalled(now = 9_000, afterMs = 2_000))
        // A stamp nobody drew, and then one that leaves the pictures before it skipped.
        assertNull(ledger.out(1_500, now = 1_110))
        assertEquals(4_000L, ledger.out(4_000, now = 1_120)!!.ptsUs)
        assertFalse(ledger.owed())
    }
}
