package co.remotedesktop

/**
 * Orders the gestures another device plays on this phone: the streamed `touch`
 * pointer, played as one continued stroke, and whole gestures such as taps and
 * pinches.
 *
 * Dispatching a gesture cancels the one still playing, so a tap, a pinch or a
 * new stroke waits for the segment in flight to finish rather than cut it off
 * (a cut-off last segment would turn the end of a drag into a cancel). Each
 * gesture gets a generation, and callbacks from an older one are ignored, so a
 * late cancel can never wipe out the stroke that replaced it.
 *
 * Bookkeeping only, with no platform calls, so a JVM test can drive it. Call
 * everything on one thread.
 */
internal class GestureSequencer<G>(private val player: Player<G>) {

    /** Plays segments and gestures, reporting back with [completed] or [cancelled]. */
    interface Player<G> {
        /** Puts a finger down at [x], [y] and keeps it there. */
        fun begin(generation: Int, x: Float, y: Float): Boolean

        /** Moves the finger from [fromX], [fromY] through [points] over
         *  [durationMs], then lifts it if [lift]. */
        fun extend(generation: Int, fromX: Float, fromY: Float, points: List<Pair<Float, Float>>,
                   durationMs: Long, lift: Boolean): Boolean

        /** Plays a whole gesture, such as a tap or a pinch. */
        fun play(generation: Int, gesture: G): Boolean
    }

    private sealed interface Event
    private data class Down(val x: Float, val y: Float) : Event
    private data class Move(val x: Float, val y: Float) : Event
    private data object Up : Event
    private class Whole(val gesture: Any?) : Event

    private val queue = ArrayDeque<Event>()

    /** The newest gesture's number; callbacks for any other are stale. */
    var generation = 0
        private set

    /** Something is playing, so nothing new may be dispatched yet. */
    private var playing = false

    /** The current stroke's finger is down. */
    private var strokeOpen = false
    private var x = 0f
    private var y = 0f
    private val pendingMoves = ArrayList<Pair<Float, Float>>()
    private var upRequested = false

    fun down(x: Float, y: Float) = enqueue(Down(x, y))
    fun move(x: Float, y: Float) = enqueue(Move(x, y))
    fun up() = enqueue(Up)
    fun whole(gesture: G) = enqueue(Whole(gesture))

    fun completed(generation: Int) {
        if (generation != this.generation) return
        playing = false
        drain()
    }

    fun cancelled(generation: Int) {
        if (generation != this.generation) return
        playing = false
        // Android stopped the stroke, e.g. because someone touched this screen: its finger is up.
        endStroke()
        drain()
    }

    private fun enqueue(event: Event) {
        queue.addLast(event)
        drain()
    }

    private fun endStroke() {
        strokeOpen = false
        pendingMoves.clear()
        upRequested = false
    }

    private fun drain() {
        while (true) {
            // Moves and the up of the current stroke never wait behind anything.
            while (true) {
                when (val event = queue.firstOrNull()) {
                    is Move -> if (strokeOpen) pendingMoves += event.x to event.y
                    Up -> if (strokeOpen) upRequested = true
                    else -> break
                }
                queue.removeFirst()
            }
            pump()
            val next = queue.firstOrNull() ?: return
            if (playing) return
            queue.removeFirst()
            // A new gesture also ends a stroke whose up never came.
            endStroke()
            val generation = ++generation
            playing = when (next) {
                is Down -> {
                    strokeOpen = true
                    x = next.x
                    y = next.y
                    player.begin(generation, next.x, next.y)
                }
                is Whole -> {
                    @Suppress("UNCHECKED_CAST")
                    player.play(generation, next.gesture as G)
                }
                else -> false
            }
            if (!playing) endStroke()
        }
    }

    /** Sends the current stroke's waiting moves, and its up, as one segment. */
    private fun pump() {
        if (playing || !strokeOpen || (pendingMoves.isEmpty() && !upRequested)) return
        val points = pendingMoves.toList()
        pendingMoves.clear()
        val lift = upRequested
        upRequested = false
        val fromX = x
        val fromY = y
        points.lastOrNull()?.let { x = it.first; y = it.second }
        playing = player.extend(generation, fromX, fromY, points, (16L * points.size).coerceIn(20L, 300L), lift)
        if (lift || !playing) endStroke()
    }
}
