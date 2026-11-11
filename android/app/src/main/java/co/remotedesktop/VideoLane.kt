package co.remotedesktop

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The capture thread's hold on the video ([PhoneVideo]), which lives on a
 * thread of its own.
 *
 * An encoder can stop answering without saying so, and a call into it then
 * never comes back. The video's thread is lost with it. This is what keeps
 * that from costing anything else:
 * - Everything asked of the video goes over to its thread. What needs an
 *   answer ([start], [stop]) waits for it, but not for ever.
 * - Every call into the encoder is timed by the video itself. One that has
 *   taken [patienceMs] is **wedged** ([wedgedJustNow] looks; so does anyone
 *   left waiting for an answer).
 * - A wedged video is given up for good: nothing more is sent from it,
 *   nothing more is asked of it, and to the caller it is simply not running
 *   and not usable. The caller points the screen back at its reader and
 *   goes on in JPEG, on its own thread, which was never held up for more
 *   than the wait.
 * - [wedge] is shared by every lane the app makes, so an encoder that wedged
 *   once is not tried again until the app's process has restarted. Whatever
 *   wedged is still in there, and a second try would only lose a second thread.
 *
 * Nothing here touches Android. Any thread may call it; the capture service
 * calls it from the capture thread and the main thread.
 */
internal class VideoLane(
    private val video: PhoneVideo,
    /** Runs a task on the video's thread. */
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long,
    private val wedge: AtomicBoolean = AtomicBoolean(),
    private val patienceMs: Long = PATIENCE_MS,
) {
    /** Whether the caller has heard of the wedge. One that happened before this lane was made is old news. */
    private val told = AtomicBoolean(wedge.get())

    /** Whether the video has been given up because its thread was lost. */
    val wedged: Boolean get() = wedge.get()

    val running: Boolean get() = !wedged && video.running
    val usable: Boolean get() = !wedged && video.usable
    val size: Pair<Int, Int>? get() = if (wedged) null else video.size
    val bitrate: Int? get() = if (wedged) null else video.bitrate
    val pipe: VideoPipe? get() = if (wedged) null else video.pipe
    val lastFailure: Pair<String, String?> get() = video.lastFailure

    /** As [PhoneVideo.start]. False also when the video's thread did not answer. */
    fun start(sourceWidth: Int, sourceHeight: Int): Boolean = ask(false) { video.start(sourceWidth, sourceHeight) }

    /** As [PhoneVideo.stop]: nothing of the video is sent after this returns, answered or not. */
    fun stop() = ask(Unit) { video.stop() }

    fun refused() = ask(Unit) { video.refused() }

    fun shutDown() = ask(Unit) { video.shutDown() }

    fun setSending(on: Boolean) = tell { video.setSending(on) }

    fun keyframe(byViewer: Boolean) = tell { video.keyframe(byViewer) }

    fun resend() = tell { video.resend() }

    /** A picture that has been handed over and that the video's thread has not come for yet. */
    private val waiting = AtomicReference<Pair<ScreenPicture, Long>?>()

    /**
     * As [PhoneVideo.show]: [picture] is the video's from here on, to draw
     * and to close. If the one before is still waiting for the video's
     * thread, this one takes its place, so at most one ever waits, and a
     * thread that never comes for it costs one picture and not a pile of them.
     */
    fun show(picture: ScreenPicture, capturedAtMs: Long) {
        if (wedged) {
            close(picture)
            return
        }
        waiting.getAndSet(picture to capturedAtMs)?.let { close(it.first) }
        post(Runnable { waiting.getAndSet(null)?.let { (next, at) -> video.show(next, at) } })
        // Given up at that very moment: nobody will come for it.
        if (wedged) dropWaiting()
    }

    private fun dropWaiting() {
        waiting.getAndSet(null)?.let { close(it.first) }
    }

    private fun close(picture: ScreenPicture) {
        try {
            picture.close()
        } catch (_: Exception) {
            // It is gone either way.
        }
    }

    /**
     * Whether the video has been found wedged and nobody has been told yet.
     * True once. It looks first, so calling it now and then is what finds a
     * draw that never came back.
     */
    fun wedgedJustNow(): Boolean {
        if (!wedged && video.stuckFor(clock()) >= patienceMs) giveUp()
        return wedged && told.compareAndSet(false, true)
    }

    private fun giveUp() {
        if (wedge.compareAndSet(false, true)) told.set(false)
        video.abandon()
        dropWaiting()
    }

    private fun tell(task: () -> Unit) {
        if (!wedged) post(Runnable(task))
    }

    /**
     * Has the video's thread do [task] and waits for the answer, for as
     * long as that thread is getting on with things. Several slow calls in
     * a row are not a wedge (an encoder that refuses three sizes takes a
     * while over each); one call that takes [patienceMs] is.
     */
    private fun <T> ask(otherwise: T, task: () -> T): T {
        if (wedged) return otherwise
        val answer = FutureTask(Callable(task))
        post(answer)
        val began = clock()
        while (true) {
            try {
                return answer.get(WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                val now = clock()
                // Stuck in one call, or not getting to this at all for far too long.
                if (video.stuckFor(now) >= patienceMs || now - began >= patienceMs * MOST_WAITS) {
                    giveUp()
                    return otherwise
                }
            } catch (_: ExecutionException) {
                return otherwise
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return otherwise
            }
        }
    }

    companion object {
        /** A call into an encoder takes a few milliseconds, or tens to start one. One that takes this long is not coming back. */
        const val PATIENCE_MS = 2_000L
        private const val WAIT_MS = 20L
        private const val MOST_WAITS = 5
    }
}
