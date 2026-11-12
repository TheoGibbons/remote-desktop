package co.remotedesktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.HardwareBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawing of a screenshot onto the canvas that feeds the video encoder,
 * as far as it can be tried without a phone: on a canvas of the ordinary
 * kind, over a bitmap. That it fills the canvas whatever the two sizes are,
 * the right way up, and that the canvas is always handed back. That a
 * surface's own canvas takes a screenshot out of graphics memory, and that
 * the picture reaches the encoder, only a phone can show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotDrawingTest {
    private val red = 0xFFCC2200.toInt()
    private val blue = 0xFF0033CC.toInt()

    /** A screenshot whose top half is red and whose bottom half is blue. */
    private fun screenshot(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { if (it / width < height / 2) red else blue }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun drawnOnto(width: Int, height: Int, shot: Bitmap): Bitmap {
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        target.eraseColor(Color.GREEN)
        val canvas = Canvas(target)
        val posted = ArrayList<Canvas>()
        ScreenshotDrawing.draw(shot, { canvas }, { posted += it })
        // Handed back once, and it is the one that was taken.
        assertEquals(1, posted.size)
        assertSame(canvas, posted.single())
        return target
    }

    private fun assertTopRedBottomBlueAndNothingLeftOver(drawn: Bitmap) {
        val right = drawn.width - 1
        val bottom = drawn.height - 1
        for (x in listOf(0, drawn.width / 2, right)) {
            assertEquals(red, drawn.getPixel(x, 0))
            assertEquals(red, drawn.getPixel(x, drawn.height / 4))
            assertEquals(blue, drawn.getPixel(x, drawn.height * 3 / 4))
            assertEquals(blue, drawn.getPixel(x, bottom))
        }
    }

    @Test
    fun aScreenshotOfTheCanvasSizeIsDrawnAsItIs() {
        assertTopRedBottomBlueAndNothingLeftOver(drawnOnto(90, 200, screenshot(90, 200)))
    }

    @Test
    fun aLargerScreenshotIsScaledDownToFillTheCanvas() {
        // A phone whose screen is 1440 wide is sent at 1080.
        assertTopRedBottomBlueAndNothingLeftOver(drawnOnto(90, 200, screenshot(120, 266)))
    }

    @Test
    fun aSmallerOneFillsItToo() {
        assertTopRedBottomBlueAndNothingLeftOver(drawnOnto(90, 200, screenshot(45, 100)))
    }

    @Test
    fun theCanvasIsHandedBackWhenTheScreenshotCannotBeDrawn() {
        val closed = screenshot(90, 200).also { it.recycle() }
        val canvas = Canvas(Bitmap.createBitmap(90, 200, Bitmap.Config.ARGB_8888))
        val posted = ArrayList<Canvas>()
        try {
            ScreenshotDrawing.draw(closed, { canvas }, { posted += it })
            fail("A recycled bitmap was drawn")
        } catch (_: RuntimeException) {
            // What the encoder's owner takes for the encoder having failed.
        }
        assertEquals(listOf(canvas), posted)
    }

    @Test
    fun aSurfaceThatGivesNoCanvasIsNotHandedOneBack() {
        val posted = ArrayList<Canvas>()
        try {
            ScreenshotDrawing.draw(screenshot(90, 200), { throw IllegalStateException("Surface has already been released.") },
                { posted += it })
            fail("Nothing was thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("released"))
        }
        assertTrue(posted.isEmpty())
    }

    @Test
    fun closingAScreenshotLetsGoOfItsBitmapAndOfTheGraphicsMemoryBehindIt() {
        val buffer = HardwareBuffer.create(64, 128, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val bitmap = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888)
        val picture = BitmapPicture(bitmap, buffer)
        assertFalse(buffer.isClosed)
        picture.close()
        assertTrue(bitmap.isRecycled)
        assertTrue(buffer.isClosed)
        // Closed a second time, as when a failure and its caller both let go: nothing happens.
        picture.close()

        // One with no graphics memory behind it: a picture the app drew.
        val drawn = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        BitmapPicture(drawn).close()
        assertTrue(drawn.isRecycled)
    }
}
