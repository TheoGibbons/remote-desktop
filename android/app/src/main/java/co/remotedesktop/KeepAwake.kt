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
 *  - after [idleMs] without input from a viewer, so a viewer left open and
 *    forgotten does not keep an unattended phone awake and unlocked for good.
 *
 * Input is what wakes a sleeping phone ([InputAccessibilityService.wakesPhone]),
 * and nothing else: acknowledgements, pings and the menu's questions are not.
 */
internal class KeepAwake(private val idleMs: Long = IDLE_MS) {
    private var viewed = false
    private var lastInput = 0L
    private var letGo = true

    /** Whether an approved device is viewing. One that starts counts as input. */
    fun viewed(on: Boolean, now: Long) {
        if (on && !viewed) {
            lastInput = now
            letGo = false
        }
        viewed = on
    }

    /** A viewer tapped, dragged, typed or pressed a navigation button. */
    fun input(now: Long) {
        if (!viewed) return
        lastInput = now
        letGo = false
    }

    /** A viewer chose Lock screen. The screen is not held again until a viewer's next input. */
    fun locked() {
        letGo = true
    }

    /** How long from [now] the screen is to be held on; null when it is not to be held. */
    fun holdFor(now: Long): Long? =
        if (!viewed || letGo) null else (lastInput + idleMs - now).takeIf { it > 0 }

    companion object {
        const val IDLE_MS = 10 * 60_000L
    }
}
