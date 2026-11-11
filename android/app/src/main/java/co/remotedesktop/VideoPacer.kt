package co.remotedesktop

/**
 * Decides when the screen is drawn into the video encoder, and as what.
 *
 * The screen is mirrored into a texture that always holds its newest picture.
 * Nothing reaches the encoder until this says so, because a picture once
 * encoded has to be sent: every picture after it is built on it. So the
 * phone holds back before encoding, never after.
 *
 * The rules:
 * - A **changed** screen is encoded, no faster than one picture per
 *   [minIntervalMs]. While it waits, newer changes simply replace it in the
 *   texture, so the screen's last state always goes out in the end.
 * - Every encode waits for **room** (see [InFlight]): new pictures,
 *   keyframes and repeats alike.
 * - A **keyframe** is made when one is wanted, and not on a timer: when a
 *   stream starts, when a viewer joins, when a viewer says it lost a frame
 *   (at most one a second for all viewers together), and when a picture
 *   could not be sent. It is made even if the screen has not changed, from
 *   the picture the texture holds.
 * - As a safety net a changed picture becomes a keyframe when the last
 *   keyframe is [safetyKeyMs] old. Never on a still screen.
 * - On a **still** screen the picture is encoded again [sharpenAfterMs]
 *   after the last one that was not such a repeat, and then nothing more.
 *   That sharpens a picture that was encoded in the middle of a movement,
 *   and pushes the last real picture out of an encoder that holds one back.
 *
 * Plain, so a JVM test can drive it. Call on one thread.
 */
internal class VideoPacer(
    private val minIntervalMs: Long = 1000L / VideoSetup.FPS,
    /** How soon to look again while there is no room. */
    private val retryMs: Long = 15,
    private val sharpenAfterMs: List<Long> = listOf(150, 600),
    private val safetyKeyMs: Long = 30_000,
    private val viewerKeyEveryMs: Long = 1_000,
) {
    /**
     * One picture to encode now.
     * @param keyframe ask the encoder for a keyframe.
     * @param repeat the screen has not changed since the picture before.
     * @param resend it is made because the picture before went unacknowledged.
     * @param capturedAt when the screen was captured; for a repeat, now.
     */
    data class Draw(val keyframe: Boolean, val repeat: Boolean, val resend: Boolean, val capturedAt: Long)

    private var hasPicture = false
    private var changed = false
    private var changedAt = 0L
    private var keyWanted = false
    private var keyNotBefore = Long.MIN_VALUE
    private var keyByViewer = false
    private var resendWanted = false
    private var lastDrawAt = Long.MIN_VALUE / 2
    private var lastKeyAt = Long.MIN_VALUE / 2
    private var lastViewerKeyAt = Long.MIN_VALUE / 2
    /** When the last picture that calls for sharpening was drawn, and how many times it has been since. */
    private var settledFrom = 0L
    private var sharpened = Int.MAX_VALUE

    /** The texture holds a new picture of the screen, captured at [capturedAt]. */
    fun changed(capturedAt: Long) {
        hasPicture = true
        changed = true
        changedAt = capturedAt
    }

    /**
     * A keyframe is wanted. One that a viewer asked for ([byViewer]) is made
     * at most once in [viewerKeyEveryMs] for all viewers together: a request
     * that comes sooner is honoured when that time is up.
     */
    fun wantKeyframe(byViewer: Boolean = false) {
        if (byViewer) {
            // One is already on its way, for this viewer or for another reason.
            if (keyWanted) return
            keyByViewer = true
            keyNotBefore = lastViewerKeyAt + viewerKeyEveryMs
        } else keyNotBefore = Long.MIN_VALUE
        keyWanted = true
    }

    /**
     * Pictures go out again after a pause. What follows is a new stream,
     * which starts with a keyframe. A change of the screen that came during
     * the pause counts as captured now: no viewer was kept waiting for it,
     * and its age would show in the viewer's delay figure as if one had been.
     */
    fun resumed(now: Long) {
        if (changed) changedAt = now
        wantKeyframe()
    }

    /** The picture before went unacknowledged: it goes again, as a keyframe. */
    fun wantResend() {
        resendWanted = true
    }

    private fun keyDue(now: Long) = (keyWanted && now >= keyNotBefore) || resendWanted

    private fun sharpenDue(now: Long) =
        !changed && sharpened < sharpenAfterMs.size && now - settledFrom >= sharpenAfterMs[sharpened]

    /** What to encode at [now], if anything is due and there is room for it. The caller then draws it. */
    fun next(now: Long, hasRoom: Boolean): Draw? {
        // Before the screen's first picture the texture is empty, and would be encoded as black.
        if (!hasPicture) return null
        val key = keyDue(now)
        val sharpen = sharpenDue(now)
        if (!changed && !key && !sharpen) return null
        if (!hasRoom || now - lastDrawAt < minIntervalMs) return null

        val fresh = changed
        val keyframe = key || (fresh && now - lastKeyAt >= safetyKeyMs)
        val draw = Draw(keyframe, repeat = !fresh, resend = !fresh && resendWanted,
            capturedAt = if (fresh) changedAt else now)
        if (keyframe) {
            if (keyByViewer) lastViewerKeyAt = now
            lastKeyAt = now
            keyWanted = false
            keyByViewer = false
            keyNotBefore = Long.MIN_VALUE
            resendWanted = false
        }
        if (fresh || keyframe) {
            settledFrom = now
            sharpened = 0
        } else sharpened++
        changed = false
        lastDrawAt = now
        return draw
    }

    /** How long until [next] is worth calling again, or null when nothing is waiting. */
    fun delay(now: Long, hasRoom: Boolean): Long? {
        if (!hasPicture) return null
        val waits = ArrayList<Long>(3)
        if (changed || keyDue(now)) waits += if (hasRoom) 0L else retryMs
        else {
            if (keyWanted) waits += keyNotBefore - now
            if (!changed && sharpened < sharpenAfterMs.size)
                waits += maxOf(settledFrom + sharpenAfterMs[sharpened] - now, if (hasRoom) 0L else retryMs)
        }
        val soonest = waits.minOrNull() ?: return null
        return maxOf(soonest, lastDrawAt + minIntervalMs - now, 1L)
    }

    /**
     * A new encoder, with a texture that holds nothing yet. Its first
     * picture is a keyframe whatever is asked of it; asking all the same
     * keeps this class's account of keyframes true.
     */
    fun reset() {
        hasPicture = false
        changed = false
        keyWanted = true
        keyByViewer = false
        keyNotBefore = Long.MIN_VALUE
        resendWanted = false
        sharpened = Int.MAX_VALUE
    }
}
