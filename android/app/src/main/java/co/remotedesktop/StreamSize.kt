package co.remotedesktop

/**
 * The size this phone's screen is sent at, by both ways of capturing it
 * ([AccessibilityScreenCapture] and [ScreenCaptureService]): the screen's own
 * shape, made smaller if its shorter side is over [MAX_SHORT_SIDE].
 *
 * The limit is on the shorter side so that a phone on its side is sent as
 * sharp as the same phone held upright: the same number of pixels, turned.
 * A limit on the width, as it used to be, sent a 1080 x 2340 phone at
 * 1080 x 498 once it was turned.
 */
internal object StreamSize {
    const val MAX_SHORT_SIDE = 1080

    fun of(screenWidth: Int, screenHeight: Int, maxShortSide: Int = MAX_SHORT_SIDE): Pair<Int, Int> {
        val shortSide = minOf(screenWidth, screenHeight)
        val scale = if (shortSide > maxShortSide) maxShortSide.toDouble() / shortSide else 1.0
        return Pair((screenWidth * scale).toInt().coerceAtLeast(1), (screenHeight * scale).toInt().coerceAtLeast(1))
    }
}
