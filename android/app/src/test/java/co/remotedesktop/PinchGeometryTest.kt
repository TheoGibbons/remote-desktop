package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

/** Where an injected pinch puts its fingers. Plain JVM. */
class PinchGeometryTest {
    // A 1080 x 2400 phone whose pinch detector needs fingers 446 px apart (27 mm at 420 dpi).
    private val w = 1080
    private val h = 2400
    private val minSpan = 446f

    private fun startSpread(f: Pair<PinchGeometry.Finger, PinchGeometry.Finger>) =
        hypot(f.first.startX - f.second.startX, f.first.startY - f.second.startY)

    private fun endSpread(f: Pair<PinchGeometry.Finger, PinchGeometry.Finger>) =
        hypot(f.first.endX - f.second.endX, f.first.endY - f.second.endY)

    private fun assertOnScreen(f: Pair<PinchGeometry.Finger, PinchGeometry.Finger>) {
        for (finger in listOf(f.first, f.second)) {
            for (x in listOf(finger.startX, finger.endX)) assertTrue("x $x", x in 15.9f..(w - 15.9f))
            for (y in listOf(finger.startY, finger.endY)) assertTrue("y $y", y in 15.9f..(h - 15.9f))
        }
    }

    @Test
    fun zoomingInStartsWideEnoughToCountAndEndsScaleTimesWider() {
        val f = PinchGeometry.fingers(w, h, 540f, 1200f, zoomIn = true, scale = 2.0, minSpan = minSpan)
        assertEquals(minSpan * 1.2f, startSpread(f), 0.5f)
        assertEquals(2f, endSpread(f) / startSpread(f), 0.001f)
        // Centred where the pinch was.
        assertEquals(540f, (f.first.startX + f.second.startX) / 2, 0.5f)
        assertEquals(1200f, (f.first.endY + f.second.endY) / 2, 0.5f)
        assertOnScreen(f)
    }

    @Test
    fun zoomingOutEndsWideEnoughToCountToo() {
        val f = PinchGeometry.fingers(w, h, 540f, 1200f, zoomIn = false, scale = 0.5, minSpan = minSpan)
        assertEquals(minSpan * 1.2f, endSpread(f), 0.5f)
        assertEquals(0.5f, endSpread(f) / startSpread(f), 0.001f)
        assertOnScreen(f)
    }

    @Test
    fun nearAnEdgeTheCentreMovesInwardUntilBothFingersFit() {
        val f = PinchGeometry.fingers(w, h, 5f, 2390f, zoomIn = true, scale = 2.0, minSpan = minSpan)
        assertOnScreen(f)
        assertEquals(2f, endSpread(f) / startSpread(f), 0.001f)
    }

    @Test
    fun aSpreadWiderThanTheScreenIsCutToFit() {
        val f = PinchGeometry.fingers(w, h, 540f, 1200f, zoomIn = true, scale = 10.0, minSpan = minSpan)
        assertOnScreen(f)
        assertTrue(endSpread(f) <= hypot(w - 32f, h - 32f) + 0.5f)
        assertTrue(endSpread(f) > startSpread(f) * 3)
    }

    @Test
    fun withoutScaleThePcsFixedStepIsUnchanged() {
        val d = (60 / sqrt(2.0)).toFloat()
        val e = (260 / sqrt(2.0)).toFloat()
        fun assertFinger(expected: List<Float>, actual: PinchGeometry.Finger) =
            assertEquals(expected, listOf(actual.startX, actual.startY, actual.endX, actual.endY)
                .zip(expected) { a, b -> if (kotlin.math.abs(a - b) < 0.001f) b else a })
        val zoomIn = PinchGeometry.fingers(w, h, 540f, 1200f, zoomIn = true, scale = null, minSpan = minSpan)
        assertFinger(listOf(540 + d, 1200 + d, 540 + e, 1200 + e), zoomIn.first)
        assertFinger(listOf(540 - d, 1200 - d, 540 - e, 1200 - e), zoomIn.second)
        val zoomOut = PinchGeometry.fingers(w, h, 100f, 1200f, zoomIn = false, scale = null, minSpan = minSpan)
        // Each finger is clamped on its own, as before.
        assertFinger(listOf(10f, 1200 - e, 100 - d, 1200 - d), zoomOut.second)
    }
}
