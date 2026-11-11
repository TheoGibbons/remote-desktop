package co.remotedesktop

/**
 * How many bits a second the phone's video encoder is given: fewer when the
 * link cannot carry what is sent, more again when it can. On a slow link the
 * picture then gets softer instead of slower.
 *
 * What it goes by is how long pictures had to wait for room ([InFlight]: a
 * viewer's acknowledgements were late, or the app's own queue was not
 * empty), second by second:
 * - A picture was kept waiting for more than [HELD_TOO_LONG_MS] of a second:
 *   the rate is multiplied by [DOWN], but not below [floor].
 * - There was room throughout, and the encoder used most of what it was
 *   given, for [CLEAR_SECONDS] such seconds: the rate is multiplied by [UP],
 *   and again after every [CLIMB_BACK_EVERY] more while it is below what the
 *   encoder started at, and every [CLIMB_EVERY] above that, up to the most
 *   allowed.
 * - A second in which nothing was encoded proves nothing about the link, and
 *   nor does one whose pictures needed few bits. They count for neither.
 * - Two requests for a keyframe within [KEY_ASKS_WITHIN_MS] mean the relay
 *   is dropping frames for a viewer that is backed up: a step down.
 * - After a step down, [SETTLE_MS] pass before pictures kept waiting count
 *   again: what was on its way at the old rate is still arriving. The same
 *   after an encoder starts, whose first picture is its largest.
 * - A step up that has to be taken back within [PROBE_MS] was one too many.
 *   The next is not tried for [FIRST_WAIT_MS], and twice as long each time
 *   it happens again, up to [MOST_WAIT_MS]: on a link that stays slow the
 *   picture would otherwise stutter every few seconds for the sake of trying.
 *
 * The rate is kept as a share of what an encoder of that size starts at, so
 * that a new encoder (the phone was turned) carries on where the old one
 * was. One that starts more than [KEEP_MS] after the last one ended starts
 * over: it may be another viewer on another link.
 *
 * Changing an encoder's rate needs no keyframe. Plain, so a JVM test can
 * drive it; any thread may call it.
 */
internal class BitrateSteps(
    private val floor: Int = VideoSetup.MIN_BITRATE,
    private val ceiling: Int = VideoSetup.MAX_BITRATE,
    /**
     * Whether the rate an encoder starts at is also the most it is given.
     * So for Accessibility pictures, which come 2.5 a second: each already
     * gets many times the bits of one picture in thirty, and more would make
     * it as large as the JPEG it replaced.
     */
    private val startIsMost: Boolean = false,
) {
    private var base = floor
    private var factor = 1.0

    // The second being counted.
    private var secondFrom = NEVER
    private var heldSince = NEVER
    private var heldMs = 0L
    private var pictures = 0
    private var bytes = 0L

    private var clear = 0
    private var climbing = false
    private var settledAt = NEVER
    private var climbWaitMs = 0L
    private var noClimbBefore = NEVER
    private var lastUpAt = NEVER
    private var lastKeyAskAt = NEVER
    private var endedAt = NEVER

    /** Bits a second the encoder should be aiming at now. */
    val bitrate: Int
        @Synchronized get() = rate(factor)

    private val most: Int get() = if (startIsMost) base else maxOf(ceiling, base)

    private fun rate(share: Double): Int = (base * share).toLong().coerceIn(floor.toLong(), most.toLong()).toInt()

    /**
     * An encoder is being started that would, with nothing known about the
     * link, be given [startRate] bits a second.
     * @return what to give it.
     */
    @Synchronized fun begin(startRate: Int, now: Long): Int {
        if (endedAt == NEVER || now - endedAt > KEEP_MS) {
            factor = 1.0
            climbWaitMs = 0
            noClimbBefore = NEVER
            lastUpAt = NEVER
            lastKeyAskAt = NEVER
        }
        endedAt = NEVER
        base = maxOf(startRate, floor)
        clear = 0
        climbing = false
        settledAt = now + SETTLE_MS
        forgetSecond()
        return rate(factor)
    }

    /** The encoder has been let go of. */
    @Synchronized fun ended(now: Long) {
        endedAt = now
        forgetSecond()
    }

    /** Pictures have stopped for a while: nothing is waiting, and the second that was being counted is dropped. */
    @Synchronized fun paused() = forgetSecond()

    private fun forgetSecond() {
        secondFrom = NEVER
        heldSince = NEVER
        heldMs = 0
        pictures = 0
        bytes = 0
    }

    /**
     * Called whenever the encoder is looked at: [waiting] says whether a
     * picture is due to be encoded and is being kept back for lack of room.
     */
    @Synchronized fun look(now: Long, waiting: Boolean) {
        if (secondFrom == NEVER) secondFrom = now
        // Looked at every few milliseconds while a picture waits. A longer gap is not waiting.
        if (heldSince != NEVER) heldMs += (now - heldSince).coerceIn(0, MOST_BETWEEN_LOOKS_MS)
        heldSince = if (waiting) now else NEVER
    }

    /** The encoder produced a picture of [size] bytes. */
    @Synchronized fun encoded(size: Int) {
        pictures++
        bytes += size
    }

    /** A viewer asked for a keyframe, and the request was not one that crossed a keyframe on its way. */
    @Synchronized fun keyframeAsked(now: Long) {
        val again = lastKeyAskAt != NEVER && now - lastKeyAskAt <= KEY_ASKS_WITHIN_MS
        lastKeyAskAt = now
        if (again && settled(now)) down(now)
    }

    /**
     * Judges the second that has passed, if one has. Called as often as [look].
     * @return whether [bitrate] has changed by it.
     */
    @Synchronized fun step(now: Long): Boolean {
        if (secondFrom == NEVER || now - secondFrom < SECOND_MS) return false
        val began = secondFrom
        val held = heldMs
        val count = pictures
        val size = bytes
        secondFrom = now
        heldMs = 0
        pictures = 0
        bytes = 0
        // In a second that began before things had settled, pictures that were kept
        // waiting tell of the rate before. Room throughout counts in any second.
        val judged = settled(began)
        val before = rate(factor)
        if (judged && held > HELD_TOO_LONG_MS) {
            down(now)
            return rate(factor) != before
        }
        if (held > CLEAR_MS) {
            // Not room throughout.
            if (judged) {
                clear = 0
                climbing = false
            }
        } else if (count > 0 && size * 8 * VideoSetup.FPS >= USED_SHARE * before * count) clear++
        // The way back to where an encoder starts is taken faster than the way on from there:
        // that rate is known to be worth having, and what lies above it is only tried.
        val needed = if (!climbing) CLEAR_SECONDS else if (factor < 1.0) CLIMB_BACK_EVERY else CLIMB_EVERY
        if (clear >= needed && (noClimbBefore == NEVER || now >= noClimbBefore)) up(now)
        return rate(factor) != before
    }

    private fun settled(now: Long) = settledAt == NEVER || now >= settledAt

    private fun down(now: Long) {
        // A step up that did not hold: the next one waits, and longer each time.
        val probeFailed = lastUpAt != NEVER && now - lastUpAt <= PROBE_MS
        climbWaitMs = if (probeFailed) (climbWaitMs * 2).coerceIn(FIRST_WAIT_MS, MOST_WAIT_MS) else 0
        noClimbBefore = now + climbWaitMs
        lastUpAt = NEVER
        clear = 0
        climbing = false
        settledAt = now + SETTLE_MS
        // Not below the floor, and no further down in name than the rate really goes.
        factor = maxOf(factor * DOWN, floor.toDouble() / base)
    }

    private fun up(now: Long) {
        val before = rate(factor)
        factor = minOf(factor * UP, most.toDouble() / base)
        clear = 0
        climbing = true
        // Nothing to wait out after a step up: if it was one too many, the next second shows it.
        if (rate(factor) != before) lastUpAt = now
    }

    companion object {
        const val DOWN = 0.7
        const val UP = 1.25
        const val SECOND_MS = 1_000L
        const val HELD_TOO_LONG_MS = 300L
        /** A second with no more waiting than this had room throughout. */
        const val CLEAR_MS = 50L
        const val CLEAR_SECONDS = 5
        const val CLIMB_EVERY = 2
        const val CLIMB_BACK_EVERY = 1
        /** Pictures that came to this share of what the encoder was given, or more, used most of it. */
        const val USED_SHARE = 0.6
        const val SETTLE_MS = 2_000L
        const val KEY_ASKS_WITHIN_MS = 5_000L
        const val PROBE_MS = 10_000L
        const val FIRST_WAIT_MS = 10_000L
        const val MOST_WAIT_MS = 30_000L
        const val KEEP_MS = 10_000L
        private const val MOST_BETWEEN_LOOKS_MS = 100L
        private const val NEVER = Long.MIN_VALUE
    }
}
