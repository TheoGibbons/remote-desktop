package co.remotedesktop

/**
 * Decides when the newest screen picture may go out: no faster than one per
 * [minIntervalMs], and only while the send queue has room. A picture that has
 * to wait is kept, and replaced by any newer one, so the screen's latest state
 * always goes out in the end, even when nothing changes on screen after it.
 *
 * Generic over the picture so a JVM test can drive it. Call on one thread.
 */
internal class FramePacer<T : Any>(
    private val minIntervalMs: Long,
    /** How soon to look again while the send queue is full. */
    private val retryMs: Long,
    /** Releases a picture that won't be sent. */
    private val release: (T) -> Unit,
) {
    private var held: T? = null
    private var lastSentAt = Long.MIN_VALUE / 2

    /** A new picture arrived. Returns it if it should be sent now (the caller
     *  then owns it), or null when it is kept for [retry]. */
    fun offer(frame: T, now: Long, hasRoom: Boolean): T? {
        held?.let(release)
        held = frame
        return takeIfDue(now, hasRoom)
    }

    /** The kept picture, if it may go out by [now]. */
    fun retry(now: Long, hasRoom: Boolean): T? = takeIfDue(now, hasRoom)

    /** How long until [retry] is worth calling, or null when nothing is kept. */
    fun retryDelay(now: Long, hasRoom: Boolean): Long? {
        if (held == null) return null
        val untilInterval = lastSentAt + minIntervalMs - now
        return maxOf(if (hasRoom) 0L else retryMs, untilInterval, 1L)
    }

    /** Drops the kept picture, e.g. when streaming stops. */
    fun clear() {
        held?.let(release)
        held = null
    }

    private fun takeIfDue(now: Long, hasRoom: Boolean): T? {
        val frame = held ?: return null
        if (!hasRoom || now - lastSentAt < minIntervalMs) return null
        held = null
        lastSentAt = now
        return frame
    }
}
