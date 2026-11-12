package co.remotedesktop

/**
 * Tells a device that begins to view this phone from one that is only back,
 * for the two things a new viewer brings: the phone is woken, and the viewer
 * is announced on it with a toast.
 *
 * Both are for a person who opened a viewer. But a viewer also asks for the
 * screen by itself, with nobody at it, when it has been away: the relay was
 * restarted, or its connection or this phone's dropped. It is back under a
 * new id each time, so by its id every return looks like a new viewer, and a
 * phone left asleep would be woken and told "started viewing" at every
 * hiccup of the network.
 *
 * So a device is taken to be coming back, not starting, when its last view
 * ended some other way than by its own `stop-view`, which is what closing a
 * viewer sends, and no longer than [withinMs] ago. Devices are told apart by
 * their identity, the key this phone approved, which survives a
 * reconnection; the id the relay gives them does not.
 *
 * [KeepAwake] tells the two apart the same way, for the ten idle minutes of
 * smooth sharing, but with no limit of time. The difference is which
 * mistake costs something. A view also ends without a `stop-view` when
 * someone is done with it: the viewer's app was closed, or it stopped
 * waiting for this phone to come back. Taking the next view for a return
 * then costs [KeepAwake] nothing, since the first tap starts its ten
 * minutes. Here it would leave a person who opens a viewer hours later
 * looking at a phone that did not wake, and the person holding the phone
 * not told that it is being viewed again. So here a return is believed only
 * for a while.
 *
 * Used from the network thread and the main one.
 */
internal class ViewerReturns(private val withinMs: Long = WITHIN_MS) {
    /** When each device's view was cut off: ended, and not by its own `stop-view`. */
    private val cutOffAt = HashMap<String, Long>()

    /**
     * [device] begins to view: its `start-view` was accepted, and it was not
     * viewing before.
     * @return whether it is only back from a view that was cut off.
     */
    @Synchronized
    fun isBack(device: String, now: Long): Boolean {
        val at = cutOffAt.remove(device) ?: return false
        return now - at in 0..withinMs
    }

    /**
     * [device] is no longer viewing. [byItself] when it said so with
     * `stop-view`; false when it left the session, the connection dropped on
     * either side, or it lost its approval.
     */
    @Synchronized
    fun ended(device: String, byItself: Boolean, now: Long) {
        // Nothing is kept of a device that has been gone too long to come back.
        cutOffAt.values.removeAll { now - it !in 0..withinMs }
        if (byItself) cutOffAt.remove(device) else cutOffAt[device] = now
    }

    companion object {
        /**
         * Time enough for a relay that is being restarted, a router that is
         * starting up again, or a phone moving from Wi-Fi to mobile data,
         * and for a viewer that waits a minute for this phone after that.
         */
        const val WITHIN_MS = 5 * 60_000L
    }
}
