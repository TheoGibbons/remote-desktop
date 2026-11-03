package co.remotedesktop

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Looper
import android.view.TextureView
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h800dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StaleBackdropTest {
    private val queued = ArrayDeque<Runnable>()
    private val queuedWorker = Executor { queued.add(it) }
    private val inlineWorker = Executor { it.run() }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun runQueued() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
        idleMain()
    }

    private fun solid(color: Int, w: Int = 100, h: Int = 100): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** The backdrop drawn at one view pixel per desktop pixel, over black. */
    private fun render(backdrop: StaleBackdrop, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        backdrop.draw(canvas, Matrix(), w, h)
        return out
    }

    private fun render(view: View): Bitmap {
        val out = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(out))
        return out
    }

    /** A grey of about [level]: stale content has had its colour taken out. */
    private fun assertGrey(level: Int, pixel: Int) {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        assertTrue("not grey: #%06x".format(pixel and 0xFFFFFF), maxOf(r, g, b) - minOf(r, g, b) <= 3)
        assertEquals("brightness of #%06x".format(pixel and 0xFFFFFF), level.toFloat(), g.toFloat(), 8f)
    }

    private fun assertBlack(pixel: Int) = assertEquals(Color.BLACK, pixel or 0xFF000000.toInt())

    @Test
    fun aFrameIsKeptGreyAndDimmedWhereItWasAndNowhereElse() {
        val backdrop = StaleBackdrop({}, inlineWorker)
        backdrop.reset(2000, 1200)
        backdrop.merge(solid(Color.WHITE), Rect(600, 100, 1000, 500))
        idleMain()

        // Only the one tile the frame landed on exists.
        assertEquals(1, backdrop.tileCount())
        val out = render(backdrop, 2000, 1200)
        assertGrey(128, out.getPixel(800, 300))
        assertBlack(out.getPixel(550, 300)) // same tile, outside the frame
        assertBlack(out.getPixel(1500, 900))
    }

    @Test
    fun colouredContentComesBackGrey() {
        val backdrop = StaleBackdrop({}, inlineWorker)
        backdrop.reset(1000, 1000)
        backdrop.merge(solid(Color.rgb(250, 40, 40)), Rect(0, 0, 500, 500))
        idleMain()
        val pixel = render(backdrop, 1000, 1000).getPixel(250, 250)
        assertEquals(Color.red(pixel).toFloat(), Color.green(pixel).toFloat(), 3f)
        assertTrue(Color.red(pixel) < 125)
    }

    @Test
    fun newerFramesPaintOverOlderOnesAndTheRestIsKept() {
        val backdrop = StaleBackdrop({}, inlineWorker)
        backdrop.reset(2000, 1200)
        backdrop.merge(solid(Color.WHITE), Rect(0, 0, 1000, 600))
        backdrop.merge(solid(Color.rgb(136, 136, 136)), Rect(500, 0, 1500, 600))
        idleMain()

        val out = render(backdrop, 2000, 1200)
        assertGrey(128, out.getPixel(200, 300)) // only the first frame saw it
        assertGrey(69, out.getPixel(700, 300)) // both did: the newer one wins
        assertGrey(69, out.getPixel(1200, 300))
        assertBlack(out.getPixel(1700, 300))
    }

    @Test
    fun scaledFramesAreStretchedToTheDesktopAreaTheyShow() {
        val backdrop = StaleBackdrop({}, inlineWorker)
        backdrop.reset(2000, 1200)
        // A 100x50 frame of a 1600x800 area: half white, half black.
        val frame = solid(Color.BLACK, 100, 50)
        Canvas(frame).drawRect(0f, 0f, 50f, 50f, android.graphics.Paint().apply { color = Color.WHITE })
        backdrop.merge(frame, Rect(200, 200, 1800, 1000))
        idleMain()

        val out = render(backdrop, 2000, 1200)
        assertGrey(128, out.getPixel(400, 600))
        assertBlack(out.getPixel(1600, 600))
        // Spans tile boundaries without gaps.
        assertGrey(128, out.getPixel(511, 511))
        assertGrey(128, out.getPixel(512, 512))
    }

    @Test
    fun aDifferentDesktopStartsOverAndDropsMergesStillInFlight() {
        val backdrop = StaleBackdrop({}, queuedWorker)
        backdrop.reset(2000, 1200)
        backdrop.merge(solid(Color.WHITE), Rect(0, 0, 1000, 600))
        backdrop.reset(1000, 1000)
        runQueued()
        assertEquals(0, backdrop.tileCount())

        // The worker starts over too, rather than copying tiles of the old desktop.
        backdrop.merge(solid(Color.WHITE), Rect(0, 0, 100, 100))
        runQueued()
        assertEquals(1, backdrop.tileCount())
        val out = render(backdrop, 1000, 1000)
        assertGrey(128, out.getPixel(50, 50))
        assertBlack(out.getPixel(300, 300))
    }

    @Test
    fun anEmptyDesktopHoldsAndDrawsNothing() {
        val backdrop = StaleBackdrop({}, inlineWorker)
        backdrop.reset(2000, 1200)
        backdrop.merge(solid(Color.WHITE), Rect(0, 0, 1000, 600))
        idleMain()
        backdrop.reset(0, 0)
        idleMain()
        assertFalse(backdrop.wantsFrame())
        assertEquals(0, backdrop.tileCount())
        assertBlack(render(backdrop, 2000, 1200).getPixel(200, 300))
    }

    @Test
    fun aBusyWorkerTurnsFramesAwayInsteadOfQueueingThem() {
        val backdrop = StaleBackdrop({}, queuedWorker)
        assertFalse("no desktop yet", backdrop.wantsFrame())
        backdrop.reset(2000, 1200)
        assertTrue(backdrop.wantsFrame())
        runQueued() // the reset's own clean-up

        backdrop.merge(solid(Color.WHITE), Rect(0, 0, 100, 100))
        backdrop.merge(solid(Color.WHITE), Rect(0, 0, 100, 100))
        assertFalse(backdrop.wantsFrame())
        val refused = solid(Color.WHITE)
        backdrop.merge(refused, Rect(0, 0, 100, 100))
        assertTrue("a refused frame is still released", refused.isRecycled)
        assertEquals(2, queued.size)

        runQueued()
        assertTrue(backdrop.wantsFrame())
    }

    // ---- the screen view ----

    // 1000x800 view, 2000x1600 desktop: fit is exactly 1:2, no letterbox.
    private fun screen(): RemoteScreenView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = RemoteScreenView(activity)
        activity.setContentView(view)
        view.setDesktopSize(2000, 1600)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals(1000, view.width)
        assertEquals(800, view.height)
        return view
    }

    /** keepStale merges on the real background worker; wait for it to land. */
    private fun awaitStale(view: View, x: Int, y: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            idleMain()
            if (Color.green(render(view).getPixel(x, y)) > 64) return
            Thread.sleep(10)
        }
        throw AssertionError("stale frame never arrived")
    }

    /**
     * The tile path seeing the white left half of the desktop, then moving on
     * to the red right half — so the left half is only what was last seen.
     */
    private fun leaveLeftHalf(view: RemoteScreenView) {
        val left = solid(Color.WHITE, 500, 800)
        view.setFrame(left, Rect(0, 0, 1000, 1600))
        idleMain()
        view.keepStale(left, Rect(0, 0, 1000, 1600))
        view.setFrame(solid(Color.RED, 500, 800), Rect(1000, 0, 2000, 1600))
    }

    /**
     * Long enough for a frame that *was* wrongly kept to show: the main thread
     * hands it to the worker, the worker builds tiles, the main thread swaps
     * them in. Checking straight after the first step would always see black.
     */
    private fun settle() {
        idleMain()
        Thread.sleep(500)
        idleMain()
    }

    @Test
    fun tileModeDrawsTheBackdropUnderTheLiveFrame() {
        val view = screen()
        leaveLeftHalf(view)
        awaitStale(view, 250, 400)

        val out = render(view)
        assertGrey(128, out.getPixel(250, 400)) // left half: what it last showed
        assertEquals(Color.RED, out.getPixel(750, 400)) // right half: live
    }

    @Test
    fun switchingTheSettingOffDropsWhatItHeldAndKeepsNothingMore() {
        val view = screen()
        leaveLeftHalf(view)
        awaitStale(view, 250, 400)

        view.setStaleBackdropEnabled(false)
        idleMain()
        assertBlack(render(view).getPixel(250, 400))

        leaveLeftHalf(view)
        settle()
        assertBlack(render(view).getPixel(250, 400))

        // Back on, it starts from nothing and keeps frames again.
        view.setStaleBackdropEnabled(true)
        idleMain()
        assertBlack(render(view).getPixel(250, 400))
        leaveLeftHalf(view)
        awaitStale(view, 250, 400)
    }

    @Test
    fun turningItOnAfterTheDesktopArrivedStillKnowsTheDesktopSize() {
        // Off from the start: the desktop size still has to be known once on.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = RemoteScreenView(activity)
        view.setStaleBackdropEnabled(false)
        activity.setContentView(view)
        view.setDesktopSize(2000, 1600)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        view.setStaleBackdropEnabled(true)
        leaveLeftHalf(view)
        awaitStale(view, 250, 400)
    }

    @Test
    fun keepStaleIgnoresACanvasThatIsNotOnScreen() {
        val view = screen()
        view.setFrame(solid(Color.RED, 500, 800), Rect(1000, 0, 2000, 1600))
        idleMain()
        view.keepStale(solid(Color.WHITE, 500, 800), Rect(0, 0, 1000, 1600))
        settle()
        assertBlack(render(view).getPixel(250, 400))
    }

    @Test
    fun videoModeHandsTheBackdropToTheLayerUnderTheSurface() {
        val view = screen()
        val activity = view.context as Activity
        val layer = StaleBackdropLayer(activity)
        layer.layout(0, 0, view.width, view.height)
        view.attachBackdropLayer(layer)
        leaveLeftHalf(view)
        awaitStale(view, 250, 400)

        // Video takes over: the screen view becomes a transparent overlay, so
        // the backdrop has to come from the layer beneath the surface.
        view.attachVideo(TextureView(activity))
        view.setVideoFrame(500, 800, Rect(1000, 0, 2000, 1600))
        idleMain()
        assertEquals(0, Color.alpha(render(view).getPixel(250, 400)))
        assertGrey(128, render(layer).getPixel(250, 400))

        // And back to tiles: the layer steps aside, the view draws it again.
        view.attachVideo(null)
        view.setFrame(solid(Color.RED, 500, 800), Rect(1000, 0, 2000, 1600))
        idleMain()
        render(view)
        assertEquals(0, Color.alpha(render(layer).getPixel(250, 400)))
    }
}
