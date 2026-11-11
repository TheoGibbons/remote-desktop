package co.remotedesktop

/**
 * Whether the screen is held on for smooth sharing (see
 * [ScreenCaptureService]). A phone that sleeps and locks ends smooth sharing
 * on Android 15 and later, and it cannot be started again until someone
 * unlocks the phone and approves the prompt. So the screen is held on while a
 * device is viewing in smooth sharing, and let go:
 *
 *  - when the last viewer stops, leaves or loses approval;
 *  - when a viewer locks the phone from its menu;
 *  - after [idleMs] in which nobody did anything, so a viewer left open and
 *    forgotten does not keep an unattended phone awake and unlocked for good.
 *
 * What counts as someone doing something, and starts the [idleMs] again:
 *
 *  - input from a viewer, which is what wakes a sleeping phone
 *    ([InputAccessibilityService.wakesPhone]) and nothing else:
 *    acknowledgements, pings and the menu's questions are not;
 *  - a device beginning to view, when a person opened its viewer;
 *  - smooth sharing starting, which takes a person's yes to Android's prompt.
 *
 * A viewer that only comes back does not count. A PC whose connection drops
 * asks for the phone's screen again by itself when it is back, with nobody at
 * it. Were that a new start each time, a forgotten viewer on a poor link
 * would hold the phone awake for good, and would undo a viewer's Lock. So a
 * device is taken to be coming back, not starting, unless its last view ended
 * with its own `stop-view`, which is what closing a viewer sends. Devices are
 * told apart by their identity, which survives a reconnection; the id the
 * relay gives them does not.
 *
 * Used from the network thread and the main one.
 */
internal class KeepAwake(private val idleMs: Long = IDLE_MS) {
    /** The devices viewing now. */
    private val viewing = HashSet<String>()
    /** The devices that began to view and have not said `stop-view` since. */
    private val unfinished = HashSet<String>()
    private var sharing = false
    private var lastActed = 0L
    private var letGo = true

    private fun acted(now: Long) {
        lastActed = now
        letGo = false
    }

    /** Smooth sharing has started, or ended. */
    @Synchronized
    fun sharing(on: Boolean, now: Long) {
        if (on && !sharing) acted(now)
        sharing = on
    }

    /** [device] is viewing: its `start-view` was accepted, and it was not viewing before. */
    @Synchronized
    fun started(device: String, now: Long) {
        viewing.add(device)
        // Back after a lost connection, it is the same viewing as before.
        if (unfinished.add(device)) acted(now)
    }

    /**
     * [device] is no longer viewing. [byItself] when it said so with
     * `stop-view`; false when it left the session, the connection dropped on
     * either side, or it lost its approval.
     */
    @Synchronized
    fun stopped(device: String, byItself: Boolean) {
        viewing.remove(device)
        if (byItself) unfinished.remove(device)
    }

    /** A viewer tapped, dragged, typed or pressed a navigation button. */
    @Synchronized
    fun input(now: Long) {
        if (viewing.isNotEmpty()) acted(now)
    }

    /** A viewer chose Lock screen. The screen is not held again until someone does something. */
    @Synchronized
    fun locked() {
        letGo = true
    }

    /** How long from [now] the screen is to be held on; null when it is not to be held. */
    @Synchronized
    fun holdFor(now: Long): Long? =
        if (!sharing || viewing.isEmpty() || letGo) null else (lastActed + idleMs - now).takeIf { it > 0 }

    companion object {
        const val IDLE_MS = 10 * 60_000L
    }
}
