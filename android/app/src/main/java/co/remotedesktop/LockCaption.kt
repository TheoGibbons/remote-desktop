package co.remotedesktop

import android.graphics.Bitmap
import co.remotedesktop.LockScreenReader.Area
import co.remotedesktop.LockScreenReader.Entry
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a viewer says and draws over the picture of a phone that is asleep,
 * locked or on its unlock screen (`lock-state` in PROTOCOL.md). The Windows
 * app decides the same way in LockOverlay.cs.
 */
internal object LockCaption {
    /**
     * What the phone being viewed last said in `lock-state`. [entry] is
     * where its pattern, PIN or password is entered, in the same shapes the
     * phone's own [LockScreenReader] finds them in; [message] is its lock
     * screen's message line.
     */
    data class State(val screenOn: Boolean, val locked: Boolean, val wakeFailed: String? = null,
                     val entry: Entry? = null, val message: String? = null)

    /** The longest message line shown; a phone sends a sentence. */
    const val MESSAGE_MAX = 200

    /** Reads a `lock-state`, or null if it makes no sense. */
    fun parse(msg: JSONObject): State? {
        val screenOn = msg.opt("screenOn") as? Boolean ?: return null
        val locked = msg.opt("locked") as? Boolean ?: return null
        // Only an unlock screen that is on show has anything to enter.
        val unlockScreen = screenOn && locked
        return State(screenOn, locked,
            wakeFailed = (msg.opt("wakeFailed") as? String)?.takeIf { !screenOn },
            entry = if (unlockScreen) entry(msg.opt("entry")) else null,
            message = if (unlockScreen) (msg.opt("message") as? String)?.trim()?.take(MESSAGE_MAX)?.takeIf { it.isNotEmpty() } else null)
    }

    /** Reads `entry`, or null if there is none or it makes no sense: the
     *  viewer then says what it would for a phone that sent none. */
    private fun entry(value: Any?): Entry? {
        val entry = value as? JSONObject ?: return null
        return when (entry.opt("kind")) {
            "pattern" -> area(entry.opt("area"))?.let { Entry.Pattern(it) }
            "pin" -> {
                val list = entry.opt("keys") as? JSONArray ?: return null
                val keys = ArrayList<LockScreenReader.Key>()
                for (i in 0 until list.length()) {
                    val key = list.opt(i) as? JSONObject ?: continue
                    val label = key.opt("label") as? String ?: continue
                    // A label this viewer doesn't know is a key it has no use for.
                    if (!isKeyLabel(label) || keys.any { it.label == label }) continue
                    area(key.opt("rect"))?.let { keys.add(LockScreenReader.Key(label, it)) }
                }
                // Without all ten digits it is not a pad a PIN can be entered on.
                if (('0'..'9').all { digit -> keys.any { it.label == digit.toString() } }) Entry.Pin(keys) else null
            }
            "password" -> Entry.Password
            else -> null
        }
    }

    fun isKeyLabel(label: String): Boolean =
        label == LockScreenReader.DELETE || label == LockScreenReader.OK || (label.length == 1 && label[0] in '0'..'9')

    /** Reads `[x, y, w, h]`, or null unless it is a rectangle with some size that lies on the screen. */
    private fun area(value: Any?): Area? {
        val list = value as? JSONArray ?: return null
        if (list.length() != 4) return null
        val v = DoubleArray(4)
        for (i in 0 until 4) {
            val number = list.opt(i) as? Number ?: return null
            v[i] = number.toDouble()
            if (v[i].isNaN() || v[i].isInfinite()) return null
        }
        val slack = 0.001
        if (v[0] < 0 || v[1] < 0 || v[2] <= 0 || v[3] <= 0 || v[0] + v[2] > 1 + slack || v[1] + v[3] > 1 + slack) return null
        return Area(v[0], v[1], v[2], v[3])
    }

    /**
     * Whether it matters that the picture is black. Android leaves the unlock
     * screen out of the picture, so on a locked phone a black picture is that
     * screen. A phone that never says how it stands (v1.0.53 or older) may
     * be asleep instead. A phone known to be unlocked is just showing
     * something dark.
     */
    fun blackMatters(state: State?): Boolean = state == null || (state.screenOn && state.locked)

    /**
     * The words to show over the picture, or null for none. [string] turns a
     * string resource into words, with the other phone's name in them.
     */
    fun caption(state: State?, pictureBlack: Boolean, string: (Int) -> String): String? = when {
        state == null -> if (pictureBlack) string(R.string.phone_viewer_black) else null
        !state.screenOn -> string(when (state.wakeFailed) {
            null -> R.string.phone_viewer_asleep
            LockState.WAKE_NOTIFICATIONS -> R.string.phone_viewer_wake_failed_notifications
            else -> R.string.phone_viewer_wake_failed
        })
        !state.locked -> null
        // The grid or the keys are drawn, with the phone's own message: see [heading].
        state.entry is Entry.Pattern || state.entry is Entry.Pin -> null
        // Nothing to draw. The phone's message comes first: it is how a wrong try is answered.
        state.entry is Entry.Password -> listOfNotNull(state.message, string(R.string.phone_viewer_password)).joinToString("\n")
        // The lock screen itself is in the picture and needs no words.
        pictureBlack -> string(R.string.phone_viewer_unlock_hidden)
        else -> null
    }

    /** The line drawn above the grid or the keys: the phone's own message,
     *  or plain words when it sends none. Null when there is nothing to draw. */
    fun heading(state: State?, string: (Int) -> String): String? = when {
        state == null || !state.screenOn || !state.locked -> null
        state.entry is Entry.Pattern -> state.message ?: string(R.string.phone_viewer_draw_pattern)
        state.entry is Entry.Pin -> state.message ?: string(R.string.phone_viewer_enter_pin)
        else -> null
    }

    /**
     * The `tap` for a key of the PIN pad, without its `to`. It is marked
     * `onlyIfLocked`: where the keys are is what the other phone said a
     * moment ago, and if it has unlocked since, that place is now something
     * on its home screen. It drops the tap then.
     */
    fun pinTap(key: LockScreenReader.Key): JSONObject = JSONObject().put("type", "tap")
        .put("x", key.rect.x + key.rect.width / 2).put("y", key.rect.y + key.rect.height / 2)
        .put("onlyIfLocked", true)

    /**
     * What this phone's keyboard typed, as the other phone gets it while its
     * PIN pad is up: a tap on the key for each digit, on its delete key for
     * Backspace and on its OK key for Enter. Anything else typed is left
     * out, and a pad without one of those two keys gets nothing for it:
     * nothing typed at a PIN pad is sent as text. The Windows app has the
     * same rule in LockOverlay.PinTaps.
     */
    fun pinMessages(pin: Entry.Pin, typed: List<TypingDiff.Edit>): List<JSONObject> {
        fun key(label: String) = pin.keys.firstOrNull { it.label == label }
        return typed.flatMap { edit ->
            when (edit) {
                TypingDiff.Backspace -> listOfNotNull(key(LockScreenReader.DELETE))
                TypingDiff.Enter -> listOfNotNull(key(LockScreenReader.OK))
                is TypingDiff.Text -> edit.text.filter { it in '0'..'9' }.mapNotNull { key(it.toString()) }
            }
        }.map(::pinTap)
    }

    /**
     * The centres of the nine dots, row by row, as fractions of the screen.
     * The phone reports the grid as one square, and its dots sit at the
     * centres of an even three by three split of it.
     */
    fun dots(area: Area): List<Pair<Double, Double>> = List(9) { i ->
        Pair(area.x + area.width * (2 * (i % 3) + 1) / 6, area.y + area.height * (2 * (i / 3) + 1) / 6)
    }

    /** How near a dot's centre a finger has to pass to take it, as a share
     *  of the distance between dots. It is Android's own figure. */
    const val DOT_REACH = 0.3

    /** Which dot the point is on, 0 to 8 row by row, or -1 for none. */
    fun dotAt(x: Double, y: Double, area: Area): Int {
        val cellW = area.width / 3
        val cellH = area.height / 3
        return dots(area).indexOfFirst { (cx, cy) ->
            // Measured in cells, so it is a circle on the phone whatever the screen's shape.
            val dx = (x - cx) / cellW
            val dy = (y - cy) / cellH
            dx * dx + dy * dy <= DOT_REACH * DOT_REACH
        }
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

        /** Whether [look] would look at a picture now, or pass it over and
         *  answer with what it last saw. */
        @Synchronized
        fun isDue(state: State?, nowMs: Long): Boolean {
            val last = lookedAt
            return blackMatters(state) && (last == null || nowMs - last >= INTERVAL_MS)
        }

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
