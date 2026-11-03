package co.remotedesktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import androidx.annotation.VisibleForTesting
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the desktop looked like the last time the viewer saw it, for the parts
 * the stream is not covering now.
 *
 * The host only streams the region the viewer can see, so panning back to
 * somewhere seen a moment ago used to show black until that region arrived
 * again. Whenever the viewer moves on to a new region, the frame it was
 * showing is merged into this desktop-sized canvas, which is drawn grey and
 * dimmed underneath the live frame. It is entirely local: the host, the
 * stream and the decoder never know about it.
 *
 * The canvas is a grid of [TILE]-pixel tiles at desktop resolution, each
 * created the first time a frame lands on it, so memory follows what has
 * actually been seen and never exceeds 2 bytes per desktop pixel (about 33 MB
 * for 7680x2160). Newer frames simply paint over older ones.
 *
 * Merging runs on a background thread and never writes to a tile that might be
 * on screen: each tile it touches is rebuilt as a copy and swapped in on the
 * main thread.
 */
class StaleBackdrop(
    private val onChanged: () -> Unit,
    private val worker: Executor = sharedWorker,
) {
    private val main = Handler(Looper.getMainLooper())

    // Main thread: what is drawn. `cols` is also read by the network thread
    // to decide whether a frame is worth copying at all.
    private var epoch = 0
    private var deskW = 0
    private var deskH = 0
    @Volatile private var cols = 0
    private var rows = 0
    private var shown: Array<Bitmap?> = emptyArray()

    // Worker thread: the newest version of every tile, which may be ahead of
    // [shown] by swaps still on their way to the main thread.
    private var built: Array<Bitmap?> = emptyArray()
    private var builtEpoch = -1

    private val pending = AtomicInteger()

    private val mergePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // No anti-aliasing: tiles must abut exactly, or their seams show.
    private val stalePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix().apply { setScale(BRIGHTNESS, BRIGHTNESS, BRIGHTNESS, 1f) })
        })
    }
    private val inverse = Matrix()
    private val visible = RectF()

    /**
     * Start over for a desktop of this size, or hold nothing at all when it is
     * 0x0 (also how the feature is switched off). Main thread.
     */
    fun reset(desktopW: Int, desktopH: Int) {
        epoch++
        // The worker's copies go too, after any merge already queued: until
        // the next merge, nothing else would let go of them.
        worker.execute {
            built = emptyArray()
            builtEpoch = -1
        }
        cols = if (desktopW > 0 && desktopH > 0) (desktopW + TILE - 1) / TILE else 0
        rows = if (cols > 0) (desktopH + TILE - 1) / TILE else 0
        // Not recycled: the worker may be copying one of these right now. They
        // are only tiles, and the collector knows what their pixels cost.
        shown = arrayOfNulls(cols * rows)
        deskW = desktopW
        deskH = desktopH
        onChanged()
    }

    /**
     * Whether a frame offered now would be used. Checked before paying for a
     * copy or a GPU readback: there is no desktop yet, or the worker is still
     * busy with earlier frames and this one would only queue behind them.
     */
    fun wantsFrame(): Boolean = cols > 0 && pending.get() < MAX_PENDING

    /**
     * Merge [frame], which shows [rect] of the desktop, into the canvas. Takes
     * ownership of [frame]. Main thread.
     */
    fun merge(frame: Bitmap, rect: Rect) {
        if (!wantsFrame() || rect.isEmpty || frame.width <= 0 || frame.height <= 0) {
            frame.recycle()
            return
        }
        val job = Job(frame, Rect(rect), deskW, deskH, cols, rows, epoch)
        pending.incrementAndGet()
        worker.execute {
            try {
                build(job)
            } catch (_: Exception) {
                // The backdrop is a nicety: losing a frame must never cost the session.
            } catch (_: OutOfMemoryError) {
            } finally {
                job.frame.recycle()
                pending.decrementAndGet()
            }
        }
    }

    private class Job(
        val frame: Bitmap, val rect: Rect,
        val deskW: Int, val deskH: Int, val cols: Int, val rows: Int, val epoch: Int,
    )

    /** Worker thread. */
    private fun build(job: Job) {
        if (job.epoch != builtEpoch) {
            builtEpoch = job.epoch
            built = arrayOfNulls(job.cols * job.rows)
        }
        val area = Rect(job.rect)
        if (!area.intersect(0, 0, job.deskW, job.deskH)) return
        val c0 = area.left / TILE
        val c1 = (area.right - 1) / TILE
        val r0 = area.top / TILE
        val r1 = (area.bottom - 1) / TILE
        val dst = RectF()
        var batch = ArrayList<Pair<Int, Bitmap>>(SWAP_BATCH)
        for (row in r0..r1) for (col in c0..c1) {
            val left = col * TILE
            val top = row * TILE
            val i = row * job.cols + col
            val tile = built[i]?.copy(Bitmap.Config.RGB_565, true)
                ?: Bitmap.createBitmap(
                    minOf(TILE, job.deskW - left), minOf(TILE, job.deskH - top),
                    Bitmap.Config.RGB_565,
                )
            // The whole frame, placed in this tile's coordinates; the tile's
            // own bounds do the clipping.
            dst.set(
                (job.rect.left - left).toFloat(), (job.rect.top - top).toFloat(),
                (job.rect.right - left).toFloat(), (job.rect.bottom - top).toFloat(),
            )
            Canvas(tile).drawBitmap(job.frame, null, dst, mergePaint)
            built[i] = tile
            batch.add(i to tile)
            // Swap in as we go, so a whole-desktop merge never holds a second
            // copy of every tile at once.
            if (batch.size >= SWAP_BATCH) {
                swapIn(job.epoch, batch)
                batch = ArrayList(SWAP_BATCH)
            }
        }
        if (batch.isNotEmpty()) swapIn(job.epoch, batch)
    }

    private fun swapIn(jobEpoch: Int, tiles: List<Pair<Int, Bitmap>>) {
        main.post {
            // Built for a desktop that has since changed: its tiles mean nothing now.
            if (jobEpoch != epoch) return@post
            for ((i, tile) in tiles) shown[i] = tile
            onChanged()
        }
    }

    /** Draw the tiles that fall inside a [viewW]x[viewH] view. Main thread. */
    fun draw(canvas: Canvas, desktopToView: Matrix, viewW: Int, viewH: Int) {
        if (cols == 0 || !desktopToView.invert(inverse)) return
        visible.set(0f, 0f, viewW.toFloat(), viewH.toFloat())
        inverse.mapRect(visible)
        val c0 = (visible.left / TILE).toInt().coerceIn(0, cols - 1)
        val c1 = (visible.right / TILE).toInt().coerceIn(0, cols - 1)
        val r0 = (visible.top / TILE).toInt().coerceIn(0, rows - 1)
        val r1 = (visible.bottom / TILE).toInt().coerceIn(0, rows - 1)
        canvas.save()
        canvas.concat(desktopToView)
        for (row in r0..r1) for (col in c0..c1) {
            val tile = shown[row * cols + col] ?: continue
            canvas.drawBitmap(tile, (col * TILE).toFloat(), (row * TILE).toFloat(), stalePaint)
        }
        canvas.restore()
    }

    @VisibleForTesting
    internal fun tileCount(): Int = shown.count { it != null }

    companion object {
        const val TILE = 512
        // How bright stale content is drawn, after taking out its colour.
        const val BRIGHTNESS = 0.5f
        private const val MAX_PENDING = 2
        private const val SWAP_BATCH = 8

        // One low-priority thread for every viewer, which exits when idle.
        private val sharedWorker: Executor = ThreadPoolExecutor(
            0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue(),
        ) { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "stale-backdrop").apply { isDaemon = true }
        }
    }
}

/**
 * Draws a [StaleBackdrop] underneath the video surface. The screen view sits
 * on top of that surface and cannot draw beneath it, so in video mode the
 * backdrop gets a layer of its own; in tile mode the screen view draws it
 * directly, under the frame.
 */
class StaleBackdropLayer(context: Context) : View(context) {
    private var backdrop: StaleBackdrop? = null
    private val desktopToView = Matrix()

    /** Draw [backdrop] through [desktopToView], or nothing when either is null. */
    fun show(backdrop: StaleBackdrop?, desktopToView: Matrix?) {
        this.backdrop = backdrop?.takeIf { desktopToView != null }
        desktopToView?.let { this.desktopToView.set(it) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        backdrop?.draw(canvas, desktopToView, width, height)
    }
}
