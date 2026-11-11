package co.remotedesktop

/**
 * Whether there is room for another picture, judged by what the viewers say
 * has arrived (`frame-ack`) and not by the phone's own send queue.
 *
 * The phone's queue shows only what the app has not yet handed to the system.
 * The system then buffers a megabyte or more of its own, and the relay
 * buffers too, so on an upload slower than the stream pictures pile up where
 * the app cannot see them and arrive seconds old. A viewer's acknowledgement
 * says a picture has really arrived, wherever it waited on the way.
 *
 * The rules, for the frames of one stream:
 * - Each viewer has a **usual** time from a frame being sent to its being
 *   acknowledged: the shortest it has taken in the last minute.
 * - There is **room** while no frame has been waiting for a viewer's
 *   acknowledgement longer than that viewer's usual time and a little more.
 *   Until a viewer has acknowledged anything, two frames may be on their way
 *   to it.
 * - The slowest viewer sets the pace. A viewer that has left a frame
 *   unacknowledged for two seconds is **quiet** and stops counting, so one
 *   that has stalled does not freeze the others; it counts again as soon as
 *   it acknowledges anything.
 * - If the newest frame is still unacknowledged a second after it went, and
 *   nothing has gone since, it is **sent again** ([resendDue]): the relay
 *   drops pictures for a viewer that is backed up, and on a screen that then
 *   stops changing nobody would notice that the last one was lost. A viewer
 *   whose acknowledgements usually take longer than that is given its usual
 *   time and a little more instead, up to eight seconds: its picture is
 *   late, not lost. Further tries wait twice, four times and eight times as
 *   long, and then no more. The caller sends it again only while there is
 *   room, like any picture.
 *
 * Plain, so a JVM test can drive it. The caller holds one lock around every call.
 */
internal class InFlight(
    private val slackMs: Long = SLACK_MS,
    private val quietMs: Long = 2_000,
    private val usualOverMs: Long = 60_000,
    private val resendAfterMs: Long = 1_000,
    private val resendTries: Int = 4,
) {
    private class Viewer(var acked: Long) {
        /** Whether it has acknowledged anything yet. */
        var heard = false

        /** When each acknowledgement came, and how long its frame had taken. */
        val trips = ArrayDeque<Pair<Long, Long>>()
    }

    /** Frames that some viewer has not acknowledged: number, and when it was sent. */
    private val waiting = ArrayDeque<Pair<Long, Long>>()
    private val viewers = HashMap<String, Viewer>()
    private var newest = -1L
    private var newestAt = 0L
    private var resends = 0

    /**
     * Frame [seq] was handed to the socket for the viewers in [to]. [repeat]
     * says it is the picture before it, sent again.
     */
    fun sent(seq: Long, nowMs: Long, to: Collection<String>, repeat: Boolean = false) {
        viewers.keys.retainAll(to.toSet())
        // A viewer that joins a stream owes nothing for the frames before it came.
        for (viewer in to) if (viewer !in viewers) viewers[viewer] = Viewer(acked = seq - 1)
        waiting.addLast(seq to nowMs)
        while (waiting.size > MAX_WAITING) waiting.removeFirst()
        newest = seq
        newestAt = nowMs
        resends = if (repeat) resends + 1 else 0
    }

    /** [viewer] says frame [seq] is the highest that has reached it. */
    fun acked(viewer: String, seq: Long, nowMs: Long) {
        val state = viewers[viewer] ?: return
        if (seq <= state.acked || seq > newest) return
        state.acked = seq
        state.heard = true
        waiting.firstOrNull { it.first == seq }?.let { (_, sentAt) -> state.trips.addLast(nowMs to nowMs - sentAt) }
        while (state.trips.isNotEmpty() && nowMs - state.trips.first().first > usualOverMs) state.trips.removeFirst()
        val all = viewers.values.minOf { it.acked }
        while (waiting.isNotEmpty() && waiting.first().first <= all) waiting.removeFirst()
    }

    /** The highest frame [viewer] has acknowledged, or null if it has acknowledged none. */
    fun ackedBy(viewer: String): Long? = viewers[viewer]?.takeIf { it.heard }?.acked

    /** The shortest a frame has taken to be acknowledged by this viewer in the last minute. */
    private fun usual(viewer: Viewer, nowMs: Long): Long? =
        viewer.trips.filter { nowMs - it.first <= usualOverMs }.minOfOrNull { it.second }

    /** Whether another picture may be sent now. */
    fun hasRoom(nowMs: Long): Boolean {
        for (viewer in viewers.values) {
            val oldest = waiting.firstOrNull { it.first > viewer.acked } ?: continue
            val waited = nowMs - oldest.second
            // Stalled: it no longer sets the pace.
            if (waited > quietMs) continue
            val usual = usual(viewer, nowMs)
            if (usual == null) {
                if (waiting.count { it.first > viewer.acked } >= 2) return false
            } else if (waited > usual + slackMs) return false
        }
        return true
    }

    /** How long until the newest frame should be sent again if it stays
     *  unacknowledged, or null when it is not owed or has been tried enough. */
    fun resendIn(nowMs: Long): Long? {
        if (newest < 0 || resends >= resendTries) return null
        val owing = viewers.values.filter { it.acked < newest }
        if (owing.isEmpty()) return null
        // On a link where a picture takes more than a second to be acknowledged, a second
        // proves nothing, and a second copy would be one more picture for the link to carry.
        // Not without limit: a viewer back from a long stall has one very long time as its usual one.
        val usual = owing.maxOf { usual(it, nowMs) ?: 0L }
        val wait = (usual + slackMs).coerceIn(resendAfterMs, resendAfterMs shl (resendTries - 1))
        return (newestAt + (wait shl resends) - nowMs).coerceAtLeast(0)
    }

    /** Whether the newest frame should be sent again now. */
    fun resendDue(nowMs: Long): Boolean = resendIn(nowMs) == 0L

    /** The stream is over: nothing is on its way any more. */
    fun reset() {
        waiting.clear()
        viewers.clear()
        newest = -1
        resends = 0
    }

    companion object {
        /** How much longer than usual a frame may wait before the phone holds back. A starting value. */
        const val SLACK_MS = 100L

        /** At 30 pictures a second, twenty seconds' worth: far more than is ever really on its way. */
        private const val MAX_WAITING = 600
    }
}
