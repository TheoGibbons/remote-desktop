package co.remotedesktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An encoder that stops answering: a call into it that does not come back.
 * Here it really does not, on a real thread, and what is checked is that
 * the thread that asked is not lost with it, that the video is given up and
 * says nothing more, and that an encoder which is only slow is left alone.
 * ScreenOutputsTest has what the capture service then does. Plain JVM.
 */
class VideoLaneTest {
    private companion object {
        /** How long a call into the encoder may take here before it counts as stuck. Two seconds in the app. */
        const val PATIENCE = 500L
    }

    /** The video's own thread. */
    private val thread = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "video-test").apply { isDaemon = true }
    }
    private val scheduled = ConcurrentHashMap<Runnable, ScheduledFuture<*>>()
    private val timer = object : PhoneVideo.Timer {
        override fun post(task: Runnable) { thread.execute(task) }
        override fun postDelayed(task: Runnable, delayMs: Long) {
            scheduled[task] = thread.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        }
        override fun remove(task: Runnable) { scheduled.remove(task)?.cancel(false) }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private val pipes = CopyOnWriteArrayList<FakeVideoPipe>()
    private val sent = AtomicInteger()
    private val failures = CopyOnWriteArrayList<String>()
    private val held = CopyOnWriteArrayList<CountDownLatch>()

    /** While set, no encoder finishes starting. */
    @Volatile private var holdStarting: CountDownLatch? = null

    /** How many sizes are refused, and how long the encoder takes to say so each time. */
    @Volatile private var refusedSteps = 0
    @Volatile private var refusalTakesMs = 0L

    private val video = PhoneVideo(
        encoders = { listOf(VideoSetup.Encoder("hardware", hardware = true, constantBitrate = true) { _, _ -> true }) },
        open = { attempt, _, _, events ->
            holdStarting?.await()
            if (attempt.step < refusedSteps) {
                Thread.sleep(refusalTakesMs)
                throw IllegalStateException("refused")
            }
            FakeVideoPipe(attempt, events).also { pipes += it }
        },
        timer = timer,
        clock = ::now,
        hasRoom = { true },
        send = { sent.incrementAndGet(); VideoSent.SENT },
        onFailed = { reason, _ -> failures += reason },
    )
    private val wedge = VideoWedge(::now)
    private val lane = VideoLane(video, post = { thread.execute(it) }, clock = ::now, wedge = wedge, patienceMs = PATIENCE)

    @After
    fun cleanup() {
        held.forEach { it.countDown() }
        thread.shutdownNow()
    }

    private fun latch() = CountDownLatch(1).also { held += it }

    private fun onVideoThread(task: () -> Unit) {
        thread.submit(task).get(5, TimeUnit.SECONDS)
    }

    /** Waits for something another thread is about to do. */
    private fun eventually(what: String, check: () -> Boolean) {
        val until = now() + 5_000
        while (!check()) {
            assertTrue("Never happened: $what", now() < until)
            Thread.sleep(5)
        }
    }

    /** Video running, with the encoder's first picture out. */
    private fun running(): FakeVideoPipe {
        assertTrue(lane.start(1080, 2340))
        lane.setSending(true)
        val pipe = pipes.last()
        onVideoThread {
            pipe.config()
            pipe.events.picture(now())
        }
        pipe.answerAll()
        eventually("the first picture is sent") { sent.get() == 1 }
        return pipe
    }

    @Test
    fun aDrawThatNeverComesBackCostsTheVideoAndNothingElse() {
        val pipe = running()
        assertFalse(lane.wedgedJustNow())

        // The screen changes, and the draw of it goes into the encoder and stays there.
        pipe.hold = latch()
        val began = now()
        thread.execute { pipe.events.picture(now()) }
        Thread.sleep(PATIENCE / 5)
        // Not long enough yet to say.
        assertFalse(lane.wedgedJustNow())
        assertTrue(lane.running)

        eventually("the stuck draw is found") { lane.wedgedJustNow() }
        assertTrue(now() - began >= PATIENCE)
        assertTrue(lane.wedged)
        assertFalse(lane.running)
        assertFalse(lane.usable)
        assertEquals(null, lane.size)
        // Said once.
        assertFalse(lane.wedgedJustNow())
        // The encoder is let go of from a thread of its own, to free the draw if anything will.
        eventually("the encoder is let go of") { pipe.aborted == 1 }

        // Nothing that is asked of the video now waits on its thread, which is still in that draw.
        val asked = now()
        assertFalse(lane.start(1080, 2340))
        lane.stop()
        lane.refused()
        lane.setSending(true)
        lane.keyframe(byViewer = true)
        lane.resend()
        lane.shutDown()
        assertTrue(now() - asked < PATIENCE / 2)
        assertEquals(1, pipes.size)

        // What the encoder still gives goes nowhere.
        pipe.answer(FakeVideoPipe.Drawn(now() * 1000, keyframe = false))
        assertEquals(1, sent.get())

        // The draw comes back at last, failing. The thread closes what it had and does nothing more.
        pipe.hold!!.countDown()
        eventually("the encoder is closed") { pipe.closed == 1 }
        Thread.sleep(50)
        assertTrue(failures.isEmpty())
        assertEquals(1, pipe.drawn.size)
        assertEquals(1, sent.get())
    }

    @Test
    fun lettingGoOfTheEncoderFromOutsideFreesADrawThatWasWaitingOnIt() {
        val pipe = running()
        // As a swap that waits for an encoder to take its picture: releasing the encoder ends the wait.
        pipe.hold = latch()
        pipe.abortFrees = true
        thread.execute { pipe.events.picture(now()) }
        eventually("the stuck draw is found") { lane.wedgedJustNow() }
        // Nobody opens the latch but the abort. The thread comes back and tidies up by itself.
        eventually("the encoder is closed") { pipe.closed == 1 }
        // The wedge was reported by whoever found it; the failed draw adds nothing.
        Thread.sleep(50)
        assertTrue(failures.isEmpty())
        assertFalse(lane.usable)
    }

    @Test
    fun anEncoderThatNeverFinishesStartingDoesNotHoldTheCallerForLong() {
        holdStarting = latch()
        val began = now()
        assertFalse(lane.start(1080, 2340))
        val took = now() - began
        assertTrue("took $took ms", took >= PATIENCE && took < PATIENCE + 2_000)
        // Not a refusal: the caller finds it was a wedge.
        assertTrue(lane.wedgedJustNow())
        assertFalse(lane.usable)
        assertTrue(pipes.isEmpty())

        // It starts after all, much too late. It is closed again at once and never used.
        holdStarting!!.countDown()
        eventually("the late encoder is closed") { pipes.size == 1 && pipes[0].closed == 1 }
        assertFalse(video.running)
        assertEquals(0, sent.get())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun anEncoderThatWillNotCloseDoesNotHoldTheCallerForLong() {
        val pipe = running()
        pipe.hold = latch()
        val began = now()
        // A turn, or the last viewer leaving, or sharing ending.
        lane.stop()
        val took = now() - began
        assertTrue("took $took ms", took >= PATIENCE && took < PATIENCE + 2_000)
        assertTrue(lane.wedgedJustNow())
        assertFalse(lane.running)
        // Stopped is stopped, answered or not: nothing more of it is sent.
        pipe.answer(FakeVideoPipe.Drawn(now() * 1000, keyframe = false))
        assertEquals(1, sent.get())
        pipe.hold!!.countDown()
        eventually("the encoder is closed") { pipe.closed >= 1 }
    }

    @Test
    fun anEncoderThatIsOnlySlowIsWaitedFor() {
        // Two sizes are refused, each after a while. Together that is longer than any one call may take.
        refusedSteps = 2
        refusalTakesMs = PATIENCE * 3 / 5
        val began = now()
        assertTrue(lane.start(1080, 2340))
        assertTrue(now() - began >= PATIENCE)
        assertFalse(lane.wedged)
        assertFalse(lane.wedgedJustNow())
        assertEquals(540 to 1170, lane.size)
        assertTrue(lane.running)
        lane.stop()
        assertEquals(1, pipes.single().closed)
        assertFalse(lane.wedged)
    }

    @Test
    fun aHealthyVideoIsNeverFoundWedged() {
        val pipe = running()
        repeat(15) {
            onVideoThread { pipe.events.picture(now()) }
            Thread.sleep(40)
            pipe.answerAll()
            assertFalse(lane.wedgedJustNow())
        }
        assertTrue(sent.get() > 5)
        lane.shutDown()
        assertEquals(1, pipe.closed)
        assertFalse(lane.wedged)
        assertFalse(lane.usable)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aWedgeIsRememberedByEveryLaneThatSharesTheFlag() {
        holdStarting = latch()
        assertFalse(lane.start(1080, 2340))
        assertTrue(lane.wedged)
        // The next run of smooth sharing, in the same process: video is not tried, and that is no news.
        val next = VideoLane(video, post = { thread.execute(it) }, clock = ::now, wedge = wedge, patienceMs = PATIENCE)
        val began = now()
        assertFalse(next.usable)
        assertFalse(next.start(1080, 2340))
        assertFalse(next.wedgedJustNow())
        assertTrue(now() - began < PATIENCE / 2)
        // A process of its own starts clean.
        assertFalse(VideoLane(video, { it.run() }, ::now, VideoWedge(::now), PATIENCE).wedged)
    }
}
