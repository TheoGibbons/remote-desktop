package co.remotedesktop

/**
 * How many bits a second the video encoder is given in smooth sharing:
 * fewer when the link cannot carry what is sent, more again when it can. On
 * a slow link the picture then gets softer instead of slower.
 *
 * It is not used for Accessibility pictures. They come 2.5 a second, so one
 * late acknowledgement or one large picture is most of a second's evidence,
 * and a rate that could only be taken away would be taken away for the
 * session. Their encoder keeps the rate it starts with.
 *
 * What it goes by is how long pictures that were due had to wait because a
 * viewer's acknowledgements were late ([InFlight]), second by second. A
 * real link is uneven, so one bad moment proves nothing:
 * - A **bad second** is one in which pictures waited more than
 *   [HELD_TOO_LONG_MS]. Two bad seconds in a row bring the rate down: it is
 *   multiplied by [DOWN], but not below [floor]. One alone (a viewer that was
 *   busy for a moment) counts for nothing, either way.
 * - A keyframe is the largest picture there is, and on an ordinary upload
 *   takes a good part of a second to arrive whatever the rate. So for
 *   [GRACE_MS] after every keyframe, whatever it was made for, waiting does
 *   not make a second bad. The same after an encoder starts, and for
 *   [DOWN_GRACE_MS] after a step down, while what was sent at the old rate
 *   is still arriving. So on a link that stays too slow, steps down come
 *   three seconds apart.
 * - Every other second in which pictures went is a **good second**, with
 *   however much ordinary waiting.
 * - Below the rate an encoder starts at, [BACK_SECONDS] good seconds bring
 *   the rate up, multiplied by [UP], and every good second after that does
 *   so again: that rate is where any encoder starts with nothing known, so
 *   getting back to it needs only a link that is not full.
 * - Above it, the link has to show that more is worth sending: only seconds
 *   count in which the pictures used most of what the encoder was given
 *   ([USED_SHARE]) and waited no more than [MUCH_WAITING_MS];
 *   [CLEAR_SECONDS] of them for the first step and [CLIMB_EVERY] for each
 *   one after, up to [ceiling]. Other seconds neither count nor start the
 *   count again.
 * - A second in which nothing was encoded proves nothing about the link.
 *   Nor does one in which the app's own send queue was full: that is a file
 *   being fetched from the phone, which fills the queue for as long as it
 *   lasts. Pictures wait then, and not for the link's sake.
 * - Two requests for a keyframe within [KEY_ASKS_WITHIN_MS] mean the relay
 *   is dropping frames for a viewer that is backed up: a step down, at most
 *   one every [GRACE_MS].
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
) {
    private var base = floor
    private var factor = 1.0

    // The second being counted.
    private var secondFrom = NEVER
    private var heldSince = NEVER
    private var heldMs = 0L
    private var pictures = 0
    private var bytes = 0L
    private var busyElsewhere = false

    /** Bad seconds in a row, up to the one that last closed. */
    private var bad = 0
    /** Good seconds since the last step, or since the count was last begun again. */
    private var good = 0
    private var climbing = false
    private var graceUntil = NEVER
    private var lastDownAt = NEVER
    private var climbWaitMs = 0L
    private var noClimbBefore = NEVER
    private var lastUpAt = NEVER
    private var lastKeyAskAt = NEVER
    private var endedAt = NEVER

    /** Bits a second the encoder should be aiming at now. */
    val bitrate: Int
        @Synchronized get() = rate(factor)

    private val most: Int get() = maxOf(ceiling, base)

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
            lastDownAt = NEVER
        }
        endedAt = NEVER
        base = maxOf(startRate, floor)
        good = 0
        climbing = false
        graceUntil = now + GRACE_MS
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
        busyElsewhere = false
        bad = 0
    }

    /**
     * Called whenever the encoder is looked at: [waiting] says whether a
     * picture is due to be encoded and is being kept back because a viewer's
     * acknowledgements are late. [elsewhere] says the app's own send queue
     * is full, which is a file on its way out of the phone: that second
     * then says nothing about what the link could carry of pictures.
     */
    @Synchronized fun look(now: Long, waiting: Boolean, elsewhere: Boolean = false) {
        if (secondFrom == NEVER) secondFrom = now
        if (elsewhere) busyElsewhere = true
        // Looked at every few milliseconds while a picture waits. A longer gap is not waiting.
        if (heldSince != NEVER) heldMs += (now - heldSince).coerceIn(0, MOST_BETWEEN_LOOKS_MS)
        heldSince = if (waiting) now else NEVER
    }

    /** The encoder produced a picture of [size] bytes. */
    @Synchronized fun encoded(size: Int) {
        pictures++
        bytes += size
    }

    /** A keyframe is being made, for whatever reason: what waits behind it for a while is its doing. */
    @Synchronized fun keyframe(now: Long) {
        graceUntil = maxOf(graceUntil, now + GRACE_MS)
    }

    /** A viewer asked for a keyframe, and the request was not one that crossed a keyframe on its way. */
    @Synchronized fun keyframeAsked(now: Long) {
        val again = lastKeyAskAt != NEVER && now - lastKeyAskAt <= KEY_ASKS_WITHIN_MS
        lastKeyAskAt = now
        if (again && (lastDownAt == NEVER || now - lastDownAt >= GRACE_MS)) down(now)
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
        val elsewhere = busyElsewhere
        secondFrom = now
        heldMs = 0
        pictures = 0
        bytes = 0
        busyElsewhere = false
        if (elsewhere) {
            // The link was carrying a file. Neither a bad second nor a good one.
            bad = 0
            return false
        }
        val before = rate(factor)
        if (held > HELD_TOO_LONG_MS) {
            // After a keyframe, a step down or an encoder's start, waiting is their doing.
            if (graceUntil != NEVER && began < graceUntil) {
                bad = 0
                return false
            }
            if (++bad >= BAD_SECONDS) {
                down(now)
                return rate(factor) != before
            }
            // If the screen then stayed still for a while, the next bad second does not follow on this one.
            if (now - began >= 2 * SECOND_MS) bad = 0
            return false
        }
        bad = 0
        if (count == 0) return false
        val below = factor < 1.0
        // Getting back to where an encoder starts needs only a link that is not full. Going
        // on from there needs pictures that used what they were given, and little waiting.
        if (below || (held <= MUCH_WAITING_MS && size * 8 * VideoSetup.FPS >= USED_SHARE * before * count)) good++
        val needed = when {
            climbing -> if (below) 1 else CLIMB_EVERY
            below -> BACK_SECONDS
            else -> CLEAR_SECONDS
        }
        if (good >= needed && (noClimbBefore == NEVER || now >= noClimbBefore)) up(now)
        return rate(factor) != before
    }

    private fun down(now: Long) {
        // A step up that did not hold: the next one waits, and longer each time.
        val probeFailed = lastUpAt != NEVER && now - lastUpAt <= PROBE_MS
        climbWaitMs = if (probeFailed) (climbWaitMs * 2).coerceIn(FIRST_WAIT_MS, MOST_WAIT_MS) else 0
        noClimbBefore = now + climbWaitMs
        lastUpAt = NEVER
        lastDownAt = now
        bad = 0
        good = 0
        climbing = false
        graceUntil = maxOf(graceUntil, now + DOWN_GRACE_MS)
        // Not below the floor, and no further down in name than the rate really goes.
        factor = maxOf(factor * DOWN, floor.toDouble() / base)
    }

    private fun up(now: Long) {
        val before = rate(factor)
        factor = minOf(factor * UP, most.toDouble() / base)
        good = 0
        climbing = true
        // Nothing to wait out after a step up: if it was one too many, the seconds that follow show it.
        if (rate(factor) != before) lastUpAt = now
    }

    companion object {
        const val DOWN = 0.7
        const val UP = 1.25
        const val SECOND_MS = 1_000L
        const val HELD_TOO_LONG_MS = 300L
        const val MUCH_WAITING_MS = 150L
        const val BAD_SECONDS = 2
        const val BACK_SECONDS = 3
        const val CLEAR_SECONDS = 5
        const val CLIMB_EVERY = 2
        /** Pictures that came to this share of what the encoder was given, or more, used most of it. */
        const val USED_SHARE = 0.6
        const val GRACE_MS = 2_000L
        const val DOWN_GRACE_MS = 1_000L
        const val KEY_ASKS_WITHIN_MS = 5_000L
        const val PROBE_MS = 10_000L
        const val FIRST_WAIT_MS = 10_000L
        const val MOST_WAIT_MS = 30_000L
        const val KEEP_MS = 10_000L
        private const val MOST_BETWEEN_LOOKS_MS = 100L
        private const val NEVER = Long.MIN_VALUE
    }
}
