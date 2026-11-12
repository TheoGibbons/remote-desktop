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

    /**
     * Whether the bitrate follows the link in smooth sharing
     * ([BitrateSteps]). Off, every encoder keeps the rate it starts with
     * and nothing else changes: pictures are still held back for room, so a
     * slow link shows fewer of them at full quality.
     *
     * It is off until the steps have been measured on a link held to a
     * rate, and is to be turned on only by what was measured there
     * (PHONE-VIDEO-PLAN.md, 3.1). A check on a phone turns it on in its own
     * build.
     */
    const val BITRATE_STEPS = false

    /** The picture at full size, then smaller, for an encoder that refuses or fails at the size before. */
    private val SCALES = listOf(1.0, 0.75, 0.5)

    /** How many sizes there are to try: full size is step 0. */
    val STEPS = SCALES.size

    /**
     * One of the phone's encoders. [takes] says whether it lists a size as
     * one it can do at [FPS] pictures a second.
     */
    class Encoder(val name: String, val hardware: Boolean, val constantBitrate: Boolean,
                  val takes: (width: Int, height: Int) -> Boolean)

    /**
     * One way of setting an encoder up. They are tried in turn until one starts.
     * @param step which of the sizes this is: 0 for full size.
     */
    data class Attempt(val encoder: String, val width: Int, val height: Int, val bitrate: Int,
                       val constantBitrate: Boolean, val step: Int = 0)

    /** H.264 pictures have even sides. */
    fun even(side: Int): Int = (side and 1.inv()).coerceAtLeast(2)

    fun bitrate(width: Int, height: Int): Int =
        (BITRATE * width * height / BITRATE_PIXELS).coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()

    /**
     * What to try for a screen mirrored at [width] x [height]: every size in
     * turn from [fromStep] on, and at each size the hardware encoders before
     * the software ones. Empty when the phone has nothing that will do, and
     * then pictures stay JPEGs.
     *
     * An encoder is asked about a size both ways round. Many list their
     * limits for a picture on its side (so wide, and at most so high) and
     * yet encode the same picture upright; going by the list alone, a phone
     * held upright would be sent smaller than it need be. If such an encoder
     * really cannot do it, it says so when it is started, and the next is tried.
     */
    fun attempts(width: Int, height: Int, encoders: List<Encoder>, fromStep: Int = 0): List<Attempt> {
        val inOrder = encoders.filter { it.hardware } + encoders.filter { !it.hardware }
        val attempts = ArrayList<Attempt>()
        for ((step, scale) in SCALES.withIndex()) {
            if (step < fromStep) continue
            val w = even((width * scale).toInt())
            val h = even((height * scale).toInt())
            for (encoder in inOrder) {
                if (!encoder.hardware && maxOf(w, h) > SOFTWARE_LONG_SIDE) continue
                if (takes(encoder, w, h) || takes(encoder, h, w))
                    attempts += Attempt(encoder.name, w, h, bitrate(w, h), encoder.constantBitrate, step)
            }
        }
        return attempts
    }

    private fun takes(encoder: Encoder, width: Int, height: Int): Boolean =
        try { encoder.takes(width, height) } catch (_: RuntimeException) { false }
}
