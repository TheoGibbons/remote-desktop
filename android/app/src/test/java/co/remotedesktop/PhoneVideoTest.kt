package co.remotedesktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clock and a queue of tasks that a test moves forward by hand, standing in for the video's thread. */
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
    val drawn: MutableList<Drawn> = java.util.Collections.synchronizedList(ArrayList<Drawn>())
    @Volatile var closed = 0
    @Volatile var aborted = 0
    var failDraws: RuntimeException? = null
    var failClose = false

    /**
     * An encoder that has stopped answering: while this is set, a draw or a
     * close does not come back until it is opened. With real threads only.
     */
    @Volatile var hold: java.util.concurrent.CountDownLatch? = null

    /** Whether letting go of the encoder from outside ([abort]) frees a call that is held. */
    @Volatile var abortFrees = false

    /**
     * Runs in the middle of a draw, for a test on one thread to do what
     * another thread would be doing while this one is inside the encoder.
     */
    var whileDrawing: (() -> Unit)? = null

    /** Every rate the encoder was given after it started, in order. */
    val bitrates: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList<Int>())
    var failBitrate: RuntimeException? = null

    override fun setBitrate(bitsPerSecond: Int) {
        check(closed == 0) { "Given a bitrate after it was closed" }
        failBitrate?.let { throw it }
        hold?.await()
        bitrates += bitsPerSecond
    }

    /** The pictures handed over to be put into the texture, in order. */
    val shown: MutableList<ScreenPicture> = java.util.Collections.synchronizedList(ArrayList<ScreenPicture>())
    var failShows: RuntimeException? = null

    /**
     * What happens once a picture has been handed over: on a phone, Android
     * says a moment later that the texture holds it. A test sets this to
     * [arrived], or calls that itself when the moment has come.
     */
    var afterShow: (() -> Unit)? = null

    override fun show(picture: ScreenPicture) {
        check(closed == 0) { "Shown a picture after it was closed" }
        failShows?.let { throw it }
        hold?.await()
        shown += picture
        afterShow?.invoke()
    }

    /** The picture that was handed over has reached the texture. Its stamp is the drawing's, and tells nothing. */
    fun arrived() = events.picture(0)

    override fun draw(ptsUs: Long, keyframe: Boolean) {
        check(closed == 0) { "Drawn into after it was closed" }
        failDraws?.let { throw it }
        whileDrawing?.invoke()
        hold?.await()
        // As a draw fails that was waiting on an encoder which has been let go of.
        check(aborted == 0) { "eglSwapBuffers 0x300d" }
        drawn += Drawn(ptsUs, keyframe)
    }

    override fun close() {
        hold?.await()
        closed++
        if (failClose) throw IllegalStateException("close failed")
    }

    override fun abort() {
        aborted++
        if (abortFrees) hold?.countDown()
    }

    companion object {
        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        val SETS = bytes(0, 0, 0, 1, 0x67, 0x64, 0, 0x32, 0, 0, 0, 1, 0x68, 0xEE)
        val KEY = bytes(0, 0, 0, 1, 0x65, 0xB8, 0x40)
        val PICTURE = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x02)
    }

    /** The encoder gives its parameter sets, as it does once before its first picture. */
    fun config() = events.encoded(SETS, 0, config = true)

    /** How many bytes each picture the encoder gives is made up to, where that is more than it has. */
    var padTo = 0

    /** The encoder gives the picture for one draw: a keyframe if it was asked for one, unless told otherwise. */
    fun answer(draw: Drawn, keyframe: Boolean = draw.keyframe) {
        val unit = if (keyframe) KEY else PICTURE
        events.encoded(if (padTo > unit.size) unit + ByteArray(padTo - unit.size) { 0x55 } else unit, draw.ptsUs,
            config = false)
    }

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
        spare = { it() },
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

        // Nothing smaller is left to try, so video is given up, and not asked for again for a while.
        assertTrue(video.gaveUp)
        refuses = { false }
        assertFalse(video.start(1080, 2340))
        assertTrue(pipes.isEmpty())

        // Ten minutes later it is tried again. A phone with no encoder for the size at all:
        timer.advance(PhoneVideo.FORGET_MS)
        assertFalse(video.gaveUp)
        encoders = emptyList()
        assertFalse(video.start(1080, 2340))
        assertEquals("This phone has no video encoder for its screen's size." to null, video.lastFailure)
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
        val began = timer.now
        video.keyframe(byViewer = false)
        // Each time it is asked again, and each time an ordinary picture comes out.
        for (round in 1..200) {
            if (failures.isNotEmpty()) break
            timer.advance(40)
            while (answered < pipe.drawn.size) {
                if (pipe.drawn[answered].keyframe) asked++
                pipe.answer(pipe.drawn[answered++], keyframe = false)
            }
        }
        // On a still screen it is asked for a quarter of a second apart, not with every picture.
        assertTrue("asked $asked times", asked in PhoneVideo.KEY_TRIES..PhoneVideo.KEY_TRIES + 2)
        assertTrue(timer.now - began >= PhoneVideo.KEY_PATIENCE_MS)
        assertTrue(timer.now - began < PhoneVideo.KEY_PATIENCE_MS + 1_000)
        // The ordinary pictures it made instead were sent all the same: they are part of the stream.
        assertTrue(sent.size >= PhoneVideo.KEY_TRIES - 1)
        assertFailedOnce("The video encoder stopped working.", pipe)
    }

    @Test
    fun manyIgnoredRequestsInAMomentAreNotYetAFailure() {
        val pipe = running()
        // A stream that needs a keyframe on a changing screen: every picture is asked to be
        // one, and every picture that is not is turned away. Eight of them take a quarter of a second.
        sendResult = VideoSent.NEEDS_KEYFRAME
        var answered = pipe.drawn.size
        video.keyframe(byViewer = false)
        repeat(20) {
            screenChanges()
            timer.advance(34)
            while (answered < pipe.drawn.size) pipe.answer(pipe.drawn[answered++], keyframe = false)
        }
        assertTrue("${pipe.drawn.count { it.keyframe }}", pipe.drawn.count { it.keyframe } > PhoneVideo.KEY_TRIES)
        assertTrue(failures.isEmpty())
        // The encoder was only slow to take the request in: from here on it does as it is asked.
        sendResult = VideoSent.SENT
        repeat(60) {
            timer.advance(100)
            while (answered < pipe.drawn.size) pipe.answer(pipe.drawn[answered++])
        }
        assertTrue(failures.isEmpty())
        assertTrue(video.running)
        assertTrue(sent.last { it.keyframe }.keyframe)
    }

    @Test
    fun aKeyframeThatComesOneTryLateIsEnough() {
        val pipe = running()
        video.keyframe(byViewer = false)
        timer.advance()
        val first = pipe.drawn.last()
        assertTrue(first.keyframe)
        pipe.answer(first, keyframe = false)
        // Asked again by itself, a quarter of a second later.
        timer.advance(40)
        assertSame(first, pipe.drawn.last())
        timer.advance(PhoneVideo.KEY_RETRY_MS)
        val second = pipe.drawn.last()
        assertTrue(second.keyframe && second !== first)
        pipe.answer(second, keyframe = true)
        timer.advance(5_000)
        assertTrue(failures.isEmpty())
        assertEquals(listOf(false, true), sent.take(2).map { it.keyframe })
    }

    @Test
    fun anEncoderThatStartsAndThenFailsIsNextTriedAtASmallerSize() {
        // It takes the full size, and then gives nothing: the S20's encoder may, for all anyone has seen.
        running().events.failed(IllegalStateException("one"))
        timer.advance()
        assertEquals(1080 to 2340, pipes[0].width to pipes[0].height)
        assertFalse(video.gaveUp)
        assertTrue(video.usable)

        // The next try does not stumble over full size again.
        running().events.failed(IllegalStateException("two"))
        timer.advance()
        assertEquals(810 to 1754, pipes[1].width to pipes[1].height)
        assertFalse(video.gaveUp)

        // Half size is the last there is.
        val half = running()
        assertEquals(540 to 1170, half.width to half.height)
        // It works. A later start, after a turn say, stays at the size that works, however long after.
        timer.advance(3 * PhoneVideo.FORGET_MS)
        assertTrue(video.start(2340, 1080))
        assertEquals(1170 to 540, pipe.width to pipe.height)
        assertEquals(2, failures.size)
    }

    @Test
    fun whenEverySizeHasFailedVideoIsGivenUpAndTriedAgainTenMinutesLater() {
        repeat(3) {
            running().events.failed(IllegalStateException("failure $it"))
            timer.advance()
        }
        assertEquals(3, failures.size)
        assertEquals(listOf(1080, 810, 540), pipes.map { it.width })
        assertTrue(pipes.all { it.closed == 1 })
        assertTrue(video.gaveUp)
        assertFalse(video.usable)

        // None is started, however often it is asked for.
        assertFalse(video.start(1080, 2340))
        timer.advance(PhoneVideo.FORGET_MS - 1_000)
        assertFalse(video.start(1080, 2340))
        assertEquals(3, pipes.size)
        assertTrue(video.gaveUp)

        // Ten minutes after the last failure it is all forgotten: full size again.
        timer.advance(1_000)
        assertFalse(video.gaveUp)
        assertTrue(video.usable)
        assertTrue(video.start(1080, 2340))
        assertEquals(1080 to 2340, pipe.width to pipe.height)
    }

    @Test
    fun anEncoderThatWillNotStartAtASizeCostsNoTry() {
        // Refused at full size by every encoder: passed over within one start, as before.
        refuses = { it.step == 0 }
        val pipe = running()
        assertEquals(810 to 1754, pipe.width to pipe.height)
        assertTrue(failures.isEmpty())
        // That one then fails while running: half size next.
        pipe.events.failed(IllegalStateException("one"))
        timer.advance()
        assertEquals(540 to 1170, running().let { it.width to it.height })
        assertFalse(video.gaveUp)
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

    // ---------------- an encoder that stops answering ----------------

    @Test
    fun aCallIntoTheEncoderIsTimedWhileItLasts() {
        val pipe = running()
        assertEquals(0L, video.stuckFor(timer.now))
        var seen = -1L
        // Looked at from outside, in the middle of a draw that has taken three seconds so far.
        pipe.whileDrawing = {
            timer.now += 3_000
            seen = video.stuckFor(timer.now)
        }
        screenChanges()
        assertEquals(3_000L, seen)
        // It came back: nothing is being waited for.
        assertEquals(0L, video.stuckFor(timer.now + 60_000))
    }

    @Test
    fun aVideoGivenUpFromOutsideSendsNothingMoreAndIsNeverStartedAgain() {
        val pipe = running()
        screenChanges()
        val late = pipe.drawn.last()
        // Its thread did not come back from the encoder, and somebody else found that out.
        video.abandon()
        assertFalse(video.running)
        assertFalse(video.usable)
        // The encoder is let go of from outside, which is all that might free the thread.
        assertEquals(1, pipe.aborted)
        assertEquals(0, pipe.closed)

        // Whatever the encoder still gives goes nowhere.
        pipe.answer(late)
        assertTrue(sent.isEmpty())

        // The thread comes back after all. It closes what it had, and that is all it does.
        timer.advance(60_000)
        assertEquals(1, pipe.closed)
        assertEquals(4, pipe.drawn.size)
        // Whoever gave it up has said so already: nothing is reported from here.
        assertTrue(failures.isEmpty())
        assertFalse(video.start(1080, 2340))
        assertEquals(1, pipes.size)
        video.abandon()
        assertEquals(1, pipe.aborted)
    }

    @Test
    fun aDrawThatFailsOnceItsVideoWasGivenUpReportsNothing() {
        val pipe = running()
        // Given up in the middle of a draw, which then fails because the encoder was let go of under it.
        pipe.whileDrawing = { video.abandon() }
        screenChanges()
        timer.advance(10_000)
        assertTrue(failures.isEmpty())
        assertEquals(1, pipe.closed)
        assertEquals(3, pipe.drawn.size)
        assertFalse(video.gaveUp)
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
