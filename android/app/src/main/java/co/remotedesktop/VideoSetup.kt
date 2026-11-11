package co.remotedesktop

/**
 * Which H.264 encoder a phone's screen is given to, at what size and at how
 * many bits a second. The phone's own list of encoders is read elsewhere
 * ([PhoneVideoEncoder.encoders]); what to try, and in what order, is decided
 * here.
 *
 * Nothing here touches Android.
 */
internal object VideoSetup {
    /** Pictures a second at most, and the rate the encoder is told to expect. */
    const val FPS = 30

    /** A software encoder is only worth having for a small picture. */
    const val SOFTWARE_LONG_SIDE = 1280

    /** Bits a second for a 1080 x 2400 picture; other sizes in proportion. */
    private const val BITRATE = 3_000_000L
    private const val BITRATE_PIXELS = 1080L * 2400L
    const val MIN_BITRATE = 300_000
    const val MAX_BITRATE = 8_000_000

    /** The picture at full size, then smaller, for an encoder that refuses or fails at the size before. */
    private val SCALES = listOf(1.0, 0.75, 0.5)

    /** One of the phone's encoders. [takes] says whether it can do a size at [FPS] pictures a second. */
    class Encoder(val name: String, val hardware: Boolean, val constantBitrate: Boolean,
                  val takes: (width: Int, height: Int) -> Boolean)

    /** One way of setting an encoder up. They are tried in turn until one starts. */
    data class Attempt(val encoder: String, val width: Int, val height: Int, val bitrate: Int,
                       val constantBitrate: Boolean)

    /** H.264 pictures have even sides. */
    fun even(side: Int): Int = (side and 1.inv()).coerceAtLeast(2)

    fun bitrate(width: Int, height: Int): Int =
        (BITRATE * width * height / BITRATE_PIXELS).coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()

    /**
     * What to try for a screen mirrored at [width] x [height]: every size in
     * turn, and at each size the hardware encoders before the software ones.
     * Empty when the phone has nothing that will do, and then pictures stay JPEGs.
     */
    fun attempts(width: Int, height: Int, encoders: List<Encoder>): List<Attempt> {
        val inOrder = encoders.filter { it.hardware } + encoders.filter { !it.hardware }
        val attempts = ArrayList<Attempt>()
        for (scale in SCALES) {
            val w = even((width * scale).toInt())
            val h = even((height * scale).toInt())
            for (encoder in inOrder) {
                if (!encoder.hardware && maxOf(w, h) > SOFTWARE_LONG_SIDE) continue
                val takes = try { encoder.takes(w, h) } catch (_: RuntimeException) { false }
                if (takes) attempts += Attempt(encoder.name, w, h, bitrate(w, h), encoder.constantBitrate)
            }
        }
        return attempts
    }
}
