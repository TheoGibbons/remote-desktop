package co.remotedesktop

import kotlin.math.hypot

/**
 * Turns fingers on the picture of another phone into what that phone plays
 * (PROTOCOL.md, "Input — controlling Android"). Fingers map almost one to one:
 *
 * - Lifted within [WINDOW_MS] without moving: one `tap`, so network delay
 *   can't stretch it into a long-press.
 * - Still down after [WINDOW_MS]: `touch` down, and on lift an `up` delayed by
 *   the same amount, so the other phone sees the same press length.
 * - Moved past the touch slop: `touch` down where the finger started, a `move`
 *   at most every [MOVE_INTERVAL_MS], a last `move` where it lifted, then `up`
 *   (the other phone ignores the up's coordinates).
 * - A second finger within [WINDOW_MS]: one `pinch` when they lift, with
 *   `scale` = end spread / start spread. A second finger later is ignored, so
 *   the stroke under way stays valid.
 * - A gesture that starts in the black bars around the picture is ignored;
 *   moves that leave the picture are held at its edge.
 *
 * Plain data in and out, so a JVM test can drive it. Times are in ms on one
 * clock; call [tick] at [nextTick].
 */
internal class PhoneTouchInput(private val touchSlop: Float) {

    sealed interface Out
    /** Coordinates are 0..1 across the other phone's screen. */
    data class Tap(val x: Double, val y: Double) : Out
    data class Touch(val action: String, val x: Double, val y: Double) : Out
    data class Pinch(val x: Double, val y: Double, val zoomIn: Boolean, val scale: Double) : Out

    private enum class State { IDLE, PENDING, HELD, STROKE, PINCH, IGNORED }

    private var state = State.IDLE
    private var left = 0f
    private var top = 0f
    private var width = 0f
    private var height = 0f

    private var firstId = -1
    private var startX = 0f
    private var startY = 0f
    private var startAt = 0L
    /** How late the `touch` down went out, compared with the real finger. */
    private var downDelay = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var sentX = 0.0
    private var sentY = 0.0
    private var lastMoveAt = 0L
    private var waitingMove = false

    private var secondId = -1
    private var secondX = 0f
    private var secondY = 0f
    private var pinchStartSpread = 0f
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f

    /** A still press's up, held back by [downDelay]. */
    private var upDueAt: Long? = null

    /** Where the picture of the other phone is drawn, in view pixels. */
    fun setPicture(left: Float, top: Float, width: Float, height: Float) {
        this.left = left
        this.top = top
        this.width = width
        this.height = height
    }

    private fun inside(x: Float, y: Float) =
        width > 0 && height > 0 && x >= left && y >= top && x <= left + width && y <= top + height

    private fun nx(x: Float) = ((x - left) / width).toDouble().coerceIn(0.0, 1.0)
    private fun ny(y: Float) = ((y - top) / height).toDouble().coerceIn(0.0, 1.0)

    /** The first finger touches the view. */
    fun down(id: Int, x: Float, y: Float, now: Long): List<Out> {
        val out = flushUp()
        reset()
        if (!inside(x, y)) {
            state = State.IGNORED
            return out
        }
        state = State.PENDING
        firstId = id
        startX = x
        startY = y
        lastX = x
        lastY = y
        startAt = now
        return out
    }

    /** Another finger touches the view while one is down. */
    fun pointerDown(id: Int, x: Float, y: Float, now: Long): List<Out> {
        if (state != State.PENDING) return emptyList()
        secondId = id
        secondX = x
        secondY = y
        pinchStartSpread = hypot(x - lastX, y - lastY)
        pinchCenterX = (x + lastX) / 2
        pinchCenterY = (y + lastY) / 2
        state = State.PINCH
        return emptyList()
    }

    /** Fingers moved; [id] says which. Call once per finger per move event. */
    fun move(id: Int, x: Float, y: Float, now: Long): List<Out> {
        if (state == State.PINCH) {
            if (id == firstId) { lastX = x; lastY = y }
            if (id == secondId) { secondX = x; secondY = y }
            return emptyList()
        }
        if (id != firstId) return emptyList()
        lastX = x
        lastY = y
        val moved = hypot(x - startX, y - startY) > touchSlop
        return when (state) {
            State.PENDING -> if (!moved) emptyList() else {
                state = State.STROKE
                downDelay = 0
                listOf(touch("down", startX, startY), sendMove(now))
            }
            State.HELD -> if (!moved) emptyList() else {
                state = State.STROKE
                paceMove(now)
            }
            State.STROKE -> paceMove(now)
            else -> emptyList()
        }
    }

    /** One finger lifts while another stays down. */
    fun pointerUp(id: Int, x: Float, y: Float, now: Long): List<Out> {
        if (id == secondId) { secondX = x; secondY = y }
        if (id == firstId) { lastX = x; lastY = y }
        return when {
            state == State.PINCH && (id == firstId || id == secondId) -> endPinch()
            // The stroke's own finger lifted; the rest of the gesture is ignored.
            id == firstId -> lift(now).also { state = State.IGNORED }
            else -> emptyList()
        }
    }

    /** The last finger lifts. */
    fun up(id: Int, x: Float, y: Float, now: Long): List<Out> {
        if (id == firstId) { lastX = x; lastY = y }
        if (id == secondId) { secondX = x; secondY = y }
        val out = when (state) {
            State.PINCH -> endPinch()
            State.IGNORED, State.IDLE -> emptyList()
            else -> if (id == firstId) lift(now) else emptyList()
        }
        state = State.IDLE
        return out
    }

    /** The system took the touch away, or the viewer is going: never leave a finger down there. */
    fun cancel(): List<Out> {
        val out = flushUp().toMutableList()
        if (state == State.HELD || state == State.STROKE) out += touch("up", lastX, lastY)
        reset()
        return out
    }

    /** What is due by [now]: a held-back down or up, or a paced move. */
    fun tick(now: Long): List<Out> {
        val out = ArrayList<Out>()
        upDueAt?.let { if (now >= it) out += flushUp() }
        when (state) {
            State.PENDING -> if (now >= startAt + WINDOW_MS) {
                state = State.HELD
                downDelay = now - startAt
                out += touch("down", startX, startY)
            }
            State.STROKE -> if (waitingMove && now >= lastMoveAt + MOVE_INTERVAL_MS) out += sendMove(now)
            else -> {}
        }
        return out
    }

    /** When [tick] next has something to do, or null. */
    fun nextTick(): Long? = listOfNotNull(
        upDueAt,
        if (state == State.PENDING) startAt + WINDOW_MS else null,
        if (state == State.STROKE && waitingMove) lastMoveAt + MOVE_INTERVAL_MS else null,
    ).minOrNull()

    private fun lift(now: Long): List<Out> = when (state) {
        State.PENDING -> listOf(Tap(nx(startX), ny(startY)))
        State.HELD -> {
            // Still: the other phone keeps the finger down as long as this one was.
            upDueAt = now + downDelay
            emptyList()
        }
        State.STROKE -> {
            val out = ArrayList<Out>()
            if (nx(lastX) != sentX || ny(lastY) != sentY) out += sendMove(now)
            out += touch("up", lastX, lastY)
            out
        }
        else -> emptyList()
    }

    private fun endPinch(): List<Out> {
        state = State.IGNORED
        val endSpread = hypot(secondX - lastX, secondY - lastY)
        if (pinchStartSpread <= touchSlop || endSpread <= 0f) return emptyList()
        val scale = (endSpread / pinchStartSpread).toDouble()
        // Fingers that barely moved apart or together: nothing to zoom.
        if (scale in 0.95..1.05) return emptyList()
        return listOf(Pinch(nx(pinchCenterX), ny(pinchCenterY), scale > 1.0, scale))
    }

    private fun paceMove(now: Long): List<Out> {
        if (now >= lastMoveAt + MOVE_INTERVAL_MS) return listOf(sendMove(now))
        waitingMove = true
        return emptyList()
    }

    private fun sendMove(now: Long): Out {
        lastMoveAt = now
        waitingMove = false
        return touch("move", lastX, lastY)
    }

    private fun touch(action: String, x: Float, y: Float): Touch {
        val t = Touch(action, nx(x), ny(y))
        sentX = t.x
        sentY = t.y
        return t
    }

    private fun flushUp(): List<Out> {
        if (upDueAt == null) return emptyList()
        upDueAt = null
        return listOf(Touch("up", sentX, sentY))
    }

    private fun reset() {
        state = State.IDLE
        firstId = -1
        secondId = -1
        waitingMove = false
        downDelay = 0
        lastMoveAt = 0L
    }

    companion object {
        /** How long a finger may rest before it counts as pressed, which is
         *  also how long a second finger has to turn it into a pinch. */
        const val WINDOW_MS = 150L
        const val MOVE_INTERVAL_MS = 30L
    }
}
