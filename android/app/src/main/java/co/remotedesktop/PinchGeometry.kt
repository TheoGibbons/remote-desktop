package co.remotedesktop

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Where the two fingers of a pinch played on this phone start and end, in screen pixels. */
internal object PinchGeometry {

    data class Finger(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

    /** Keeps fingers off the very edge of the screen. */
    private const val EDGE = 16f

    /**
     * With [scale] (the end spread over the start spread of another phone's
     * pinch), the fingers move along the screen's diagonal through the centre,
     * never closer together than 1.2 x [minSpan]: Android's pinch detector
     * ignores fingers nearer than its minimum scaling span (about 27 mm), so a
     * pinch starting closer would barely zoom. A spread wider than the screen is
     * cut to fit, and the centre moves inward until both fingers are on screen.
     *
     * Without [scale] (a PC's mouse wheel), the fixed step played before it existed.
     */
    fun fingers(w: Int, h: Int, cx: Float, cy: Float, zoomIn: Boolean, scale: Double?, minSpan: Float): Pair<Finger, Finger> {
        if (scale == null || scale.isNaN() || scale <= 0.0) return fixedStep(w, h, cx, cy, zoomIn)
        val boxW = (w - 2 * EDGE).coerceAtLeast(1f)
        val boxH = (h - 2 * EDGE).coerceAtLeast(1f)
        val diagonal = hypot(boxW, boxH)
        val s = scale.toFloat().coerceIn(0.1f, 10f)
        val small = minSpan * 1.2f
        val start = (if (s >= 1f) small else small / s).coerceAtMost(diagonal)
        val end = (start * s).coerceIn(minOf(small, diagonal), diagonal)
        // Along the diagonal of the area fingers may use, so the widest spread still fits.
        val ux = boxW / diagonal
        val uy = boxH / diagonal
        val half = maxOf(start, end) / 2
        val x = cx.coerceIn(EDGE + half * ux, maxOf(EDGE + half * ux, w - EDGE - half * ux))
        val y = cy.coerceIn(EDGE + half * uy, maxOf(EDGE + half * uy, h - EDGE - half * uy))
        fun finger(sign: Float) = Finger(x + sign * ux * start / 2, y + sign * uy * start / 2,
            x + sign * ux * end / 2, y + sign * uy * end / 2)
        return finger(1f) to finger(-1f)
    }

    /** Two fingers 120 px apart moving to 520 px (or back) along a 45° diagonal. */
    private fun fixedStep(w: Int, h: Int, cx: Float, cy: Float, zoomIn: Boolean): Pair<Finger, Finger> {
        val near = 60f
        val far = 260f
        val (from, to) = if (zoomIn) near to far else far to near
        val dirX = cos(Math.toRadians(45.0)).toFloat()
        val dirY = sin(Math.toRadians(45.0)).toFloat()
        fun clampX(v: Float) = v.coerceIn(10f, w - 10f)
        fun clampY(v: Float) = v.coerceIn(10f, h - 10f)
        fun finger(sign: Float) = Finger(clampX(cx + sign * dirX * from), clampY(cy + sign * dirY * from),
            clampX(cx + sign * dirX * to), clampY(cy + sign * dirY * to))
        return finger(1f) to finger(-1f)
    }
}
