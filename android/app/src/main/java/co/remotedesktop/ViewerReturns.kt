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
 * A viewer that asks by itself says so: its `start-view` carries
 * `resume: true`. That alone is not believed. It counts only from a device
 * this phone remembers as cut off: one whose last view ended some other way
 * than by its own `stop-view`, no longer than [withinMs] ago. Devices are
 * told apart by their identity, the key this phone approved, which survives
 * a reconnection; the id the relay gives them does not.
 *
 * A `start-view` without the mark is always a new viewing. So is the next
 * one after this phone's owner did something about its viewers: took the
 * device's approval away, switched control off, or stopped the connection.
 * What is remembered is dropped then, and nobody comes back unannounced
 * after that.
 *
 * The mark is needed because a missing `stop-view` does not mean the viewer
 * is still open. It is also missing when the viewer was closed while it
 * could reach nobody, when its app was closed, and when it gave up waiting
 * for this phone. As first built, any `start-view` from a device that was
 * cut off counted as a return, and a person who opened a viewer within five
 * minutes of any of those was not announced on the phone.
 *
 * [KeepAwake] tells a return from a start for the ten idle minutes of smooth
 * sharing, by the missing `stop-view` alone and with no limit of time. There
 * a view wrongly taken for a return costs nothing: the first tap starts the
 * ten minutes. Here it would leave the phone's owner not told that they are
 * being viewed.
 *
 * Used from the network thread and the main one.
 */
internal class ViewerReturns(private val withinMs: Long = WITHIN_MS) {
    /** When each device's view was cut off: ended, and not by its own `stop-view`. */
    private val cutOffAt = HashMap<String, Long>()

    /**
     * [device] begins to view: its `start-view` was accepted, and it was not
     * viewing before. Whatever is remembered of it is used up by this.
     * @param resume whether its `start-view` said it was asking by itself.
     * @return whether it is only back from a view that was cut off.
     */
    @Synchronized
    fun isBack(device: String, now: Long, resume: Boolean): Boolean {
        val at = cutOffAt.remove(device) ?: return false
        return resume && now - at in 0..withinMs
    }

    /**
     * [device] is no longer viewing. [byItself] when it said so with
     * `stop-view`; false when it left the session or the connection dropped
     * on either side.
     */
    @Synchronized
    fun ended(device: String, byItself: Boolean, now: Long) {
        // Nothing is kept of a device that has been gone too long to come back.
        cutOffAt.values.removeAll { now - it !in 0..withinMs }
        if (byItself) cutOffAt.remove(device) else cutOffAt[device] = now
    }

    /** [device] may no longer view, by this phone's doing. Its next view is announced. */
    @Synchronized
    fun forget(device: String) {
        cutOffAt.remove(device)
    }

    /** Control was switched off, or the connection stopped, on this phone: every next view is announced. */
    @Synchronized
    fun forgetAll() = cutOffAt.clear()

    /** Only [approved] devices are remembered: one whose approval was taken away while it was gone is not. */
    @Synchronized
    fun keep(approved: Collection<String>) {
        cutOffAt.keys.retainAll(approved.toSet())
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
