package co.remotedesktop

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * What a viewer says over the picture of a phone that is asleep, locked or
 * on its unlock screen (`lock-state` in PROTOCOL.md). The Windows app decides
 * the same way in LockOverlay.cs.
 */
internal object LockCaption {
    /** What the phone being viewed last said in `lock-state`. */
    data class State(val screenOn: Boolean, val locked: Boolean, val wakeFailed: String? = null)

    /** Reads a `lock-state`, or null if it makes no sense. */
    fun parse(msg: JSONObject): State? {
        val screenOn = msg.opt("screenOn") as? Boolean ?: return null
        val locked = msg.opt("locked") as? Boolean ?: return null
        return State(screenOn, locked, (msg.opt("wakeFailed") as? String)?.takeIf { !screenOn })
    }

    /**
     * Whether it matters that the picture is black. Android leaves the unlock
     * screen out of the picture, so on a locked phone a black picture is that
     * screen. A phone that never says how it stands (v1.0.53 or older) may
     * be asleep instead. A phone known to be unlocked is just showing
     * something dark.
     */
    fun blackMatters(state: State?): Boolean = state == null || (state.screenOn && state.locked)

    /** The string to show over the picture, or null for none. */
    fun text(state: State?, pictureBlack: Boolean): Int? = when {
        state == null -> if (pictureBlack) R.string.phone_viewer_black else null
        !state.screenOn -> when (state.wakeFailed) {
            null -> R.string.phone_viewer_asleep
            LockState.WAKE_NOTIFICATIONS -> R.string.phone_viewer_wake_failed_notifications
            else -> R.string.phone_viewer_wake_failed
        }
        // The lock screen itself is in the picture and needs no words.
        state.locked && pictureBlack -> R.string.phone_viewer_unlock_hidden
        else -> null
    }

    /** A colour channel this low or lower is black. Dark themes are lighter. */
    private const val DARK = 16

    /** How much of the picture may be lit and still leave it nearly all
     *  black: an unlock screen keeps its Back arrow, and a phone whose screen
     *  is off sends the words "Screen unavailable". */
    private const val LIT_PERCENT = 1

    /** How far apart the pixels looked at are: about a hundred across the shorter side. */
    fun sampleStep(width: Int, height: Int): Int = (minOf(width, height) / 96).coerceAtLeast(1)

    fun isLit(argb: Int): Boolean =
        (argb shr 16 and 0xFF) > DARK || (argb shr 8 and 0xFF) > DARK || (argb and 0xFF) > DARK

    fun nearlyAllBlack(lit: Int, looked: Int): Boolean = looked > 0 && lit * 100 <= looked * LIT_PERCENT

    /** Whether a picture is nearly all black, from a coarse sample of it. */
    fun isNearlyBlack(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val step = sampleStep(width, height)
        val row = IntArray(width)
        var lit = 0
        var looked = 0
        for (y in step / 2 until height step step) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in step / 2 until width step step) {
                looked++
                if (isLit(row[x])) lit++
            }
        }
        return nearlyAllBlack(lit, looked)
    }

    /**
     * Looks at the pictures as they are decoded, twice a second at most and
     * only while a black one would mean something, and remembers the answer.
     */
    class BlackWatch(private val test: (Bitmap) -> Boolean = ::isNearlyBlack) {
        private var lookedAt: Long? = null
        private var black = false

        /** Whether the last picture looked at was black; false while it doesn't matter. */
        @Synchronized
        fun look(bitmap: Bitmap, state: State?, nowMs: Long): Boolean {
            if (!blackMatters(state)) return false
            val last = lookedAt
            if (last == null || nowMs - last >= INTERVAL_MS) {
                lookedAt = nowMs
                black = test(bitmap)
            }
            return black
        }

        /** The phone's state changed: what was seen before says nothing about now. */
        @Synchronized
        fun forget() {
            lookedAt = null
            black = false
        }

        companion object {
            const val INTERVAL_MS = 500L
        }
    }
}
