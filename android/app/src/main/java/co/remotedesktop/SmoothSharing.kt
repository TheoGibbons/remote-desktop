package co.remotedesktop

/**
 * Everything that is decided when a viewer asks this phone to start or stop
 * smooth sharing (`request-smooth`, `stop-smooth` and `smooth-status` in
 * PROTOCOL.md): who may ask, when Android's prompt is shown, who is told what,
 * and when a prompt nobody answers is taken away again.
 *
 * Starting to capture the screen because a message arrived is the most
 * sensitive thing a viewer can ask of a phone, so the rules are here, in a
 * class that touches nothing of Android and can be tried case by case:
 *
 *  - Only a device this phone has approved, with control switched on, of a
 *    kind that is let in (Allow other phones), and that is viewing right now
 *    may ask. Anyone else is not answered at all.
 *  - This class never approves the prompt. It can show it and take it away.
 *    The answer is a person's, on the phone or by a click in a viewer.
 *  - A viewer may stop smooth sharing only where the phone can go on showing
 *    its screen without it. Otherwise stopping would leave every viewer
 *    with no picture, and no way to see the prompt that brings it back.
 *  - One prompt at a time. A second request while one is up joins the wait.
 *  - After a prompt has come and gone without a yes, however it went, nobody
 *    may ask again for [repeatWaitMs]; twice that after the next one, and so
 *    on up to [MAX_WAIT_MS]. The phone's owner is not to be nagged by a
 *    viewer that keeps asking. A yes, or [FORGET_MS] of quiet, starts over.
 *  - A prompt is taken away after [answerMs] without an answer, when everyone
 *    who asked has stopped viewing, and when the screen turns off.
 *
 * Not thread safe: [SmoothPrompt] calls it on the main thread only.
 */
internal class SmoothSharing(
    private val repeatWaitMs: Long = REPEAT_WAIT_MS,
    private val answerMs: Long = ANSWER_MS,
) {
    /** What is known of the device a request came from. Each is asked afresh for every message. */
    data class Asker(
        /** This phone has approved the device. */
        val approved: Boolean,
        /** "Allow remote control" is on in Permissions & Access. */
        val controlOn: Boolean,
        /** It is a PC, or a phone while Allow other phones is on. */
        val kindAllowed: Boolean,
        /** Its `start-view` was accepted and it has not stopped since. */
        val viewing: Boolean,
    ) {
        val mayAsk: Boolean get() = approved && controlOn && kindAllowed && viewing
    }

    /** How the phone stands when a request arrives. */
    data class Phone(
        /** Smooth sharing is running. */
        val sharing: Boolean,
        /** The screen is on and the phone is unlocked. */
        val inUse: Boolean,
        /** Pictures may go to viewers right now: no device is waiting to be approved. */
        val sending: Boolean,
        /** The prompt, or a notification that opens it, can be put on the screen. */
        val canShowPrompt: Boolean,
        /** The phone can show its screen without smooth sharing, so a viewer has a picture either way. */
        val otherCapture: Boolean,
    )

    /** How a prompt that was up came to an end. */
    enum class Outcome {
        /** Someone pressed its button to share. */
        GRANTED,
        /** Someone pressed Cancel, or Back. */
        DECLINED,
        /** The phone's screen turned off under it. */
        SCREEN_OFF,
        /** Android would not show it, and there was no other way to ask. */
        NOT_SHOWN,
    }

    /** One thing for [SmoothPrompt] to do. */
    sealed interface Step
    /** Send `smooth-status` with this [state] and [reason] to each of [to]. */
    data class Tell(val to: List<String>, val state: String, val reason: String? = null) : Step
    /** Put Android's prompt on the phone's screen. */
    object ShowPrompt : Step
    /** Take the prompt, or the notification standing in for it, away. */
    object ClosePrompt : Step
    /** End smooth sharing because a viewer asked. */
    object StopSharing : Step

    /** Who asked for the prompt that is up, in the order they asked. */
    private val waiting = LinkedHashSet<String>()
    /** Who is owed the news that sharing started: the prompt was approved and the service is on its way. */
    private val starting = LinkedHashSet<String>()
    private var promptSince: Long? = null
    /** How many prompts in a row have ended without a yes, and when the last of them did. */
    private var refusals = 0
    private var refusedAt: Long? = null

    /** A prompt was on the phone's screen and went again without a yes. */
    private fun refused(now: Long) {
        if (refusedAt?.let { now - it >= FORGET_MS } == true) refusals = 0
        refusals++
        refusedAt = now
    }

    /** How long nobody may ask yet; null when anyone may. */
    private fun waitLeft(now: Long): Long? {
        val since = refusedAt ?: return null
        val wait = (repeatWaitMs shl (refusals - 1).coerceIn(0, 16)).coerceAtMost(MAX_WAIT_MS)
        return (since + wait - now).takeIf { it > 0 }
    }

    /** Who is waiting for the prompt that is up. */
    val waitingFor: List<String> get() = waiting.toList()

    /** A prompt is up and unanswered. */
    val prompting: Boolean get() = promptSince != null

    /** When the prompt that is up is taken away if nobody answers it; null when none is up. */
    val giveUpAt: Long? get() = promptSince?.let { it + answerMs }

    /** `request-smooth` from [from]. */
    fun request(from: String, asker: Asker, phone: Phone, now: Long): List<Step> {
        if (!asker.mayAsk) return emptyList()
        fun no(reason: String) = listOf(Tell(listOf(from), UNAVAILABLE, reason))
        // With no picture to see the prompt in, the viewer is told where it has to be answered.
        val asking = Tell(listOf(from), PROMPTING, if (phone.otherCapture) null else ANSWER_ON_PHONE)
        return when {
            phone.sharing -> listOf(Tell(listOf(from), STARTED))
            !phone.inUse -> no(UNLOCK_FIRST)
            !phone.sending -> no(WAITING_FOR_APPROVAL)
            prompting -> {
                waiting.add(from)
                listOf(asking)
            }
            waitLeft(now) != null -> no(askAgainIn(waitLeft(now)!!))
            !phone.canShowPrompt -> no(CANNOT_SHOW)
            else -> {
                waiting.clear()
                waiting.add(from)
                promptSince = now
                // Told first: showing it may fail at once, and that is the later news.
                listOf(asking, ShowPrompt)
            }
        }
    }

    /**
     * `stop-smooth` from [from]. Whoever is viewing hears that it ended when
     * the service has stopped. Where smooth sharing is the only way the phone
     * can show its screen ([otherCapture] false) it is refused, with the reason.
     */
    fun stop(from: String, asker: Asker, sharing: Boolean, otherCapture: Boolean): List<Step> = when {
        !asker.mayAsk || !sharing -> emptyList()
        !otherCapture -> listOf(Tell(listOf(from), UNAVAILABLE, ONLY_WAY))
        else -> listOf(StopSharing)
    }

    /** The prompt came to an end by itself, or by a person's answer. */
    fun answered(outcome: Outcome, now: Long): List<Step> {
        if (!prompting) return emptyList()
        val asked = waiting.toList()
        waiting.clear()
        promptSince = null
        return when (outcome) {
            Outcome.GRANTED -> {
                starting.addAll(asked)
                refusals = 0
                refusedAt = null
                emptyList()
            }
            Outcome.DECLINED -> {
                refused(now)
                listOf(Tell(asked, DECLINED))
            }
            Outcome.SCREEN_OFF -> {
                refused(now)
                listOf(ClosePrompt, Tell(asked, DECLINED, SCREEN_TURNED_OFF))
            }
            // Nothing was put in front of anyone, so there is nothing to wait out.
            Outcome.NOT_SHOWN -> listOf(ClosePrompt, Tell(asked, UNAVAILABLE, CANNOT_SHOW))
        }
    }

    /** Time has passed. Takes away a prompt that has had its time. */
    fun tick(now: Long): List<Step> {
        val due = giveUpAt ?: return emptyList()
        if (now < due) return emptyList()
        val asked = waiting.toList()
        waiting.clear()
        promptSince = null
        refused(now)
        return listOf(ClosePrompt, Tell(asked, DECLINED, NOBODY_ANSWERED))
    }

    /**
     * Who is viewing has changed. A prompt nobody is waiting for any more is
     * taken away: it would otherwise stand on a phone that nobody is looking
     * at, for whoever picks it up next.
     */
    fun viewersNow(viewing: Collection<String>, now: Long): List<Step> {
        starting.retainAll(viewing.toSet())
        if (!prompting) return emptyList()
        waiting.retainAll(viewing.toSet())
        if (waiting.isNotEmpty()) return emptyList()
        promptSince = null
        // A viewer that asks, leaves and comes back to ask again waits like any other.
        refused(now)
        return listOf(ClosePrompt)
    }

    /**
     * Smooth sharing is running. Those who asked for it are told. That
     * includes whoever is waiting on a prompt that is still up: sharing can
     * start another way, from the phone's Permissions screen, and the prompt
     * a viewer asked for then has nothing left to ask.
     */
    fun started(): List<Step> {
        val asked = (waiting + starting).toList()
        val wasPrompting = prompting
        waiting.clear()
        starting.clear()
        promptSince = null
        return buildList {
            if (wasPrompting) add(ClosePrompt)
            if (asked.isNotEmpty()) add(Tell(asked, STARTED))
        }
    }

    /**
     * Smooth sharing is over, or never got going after the prompt was
     * approved ([wasRunning] false). [viewers] are the devices viewing now
     * that know this message.
     */
    fun ended(wasRunning: Boolean, stoppedBy: String?, locked: Boolean, viewers: Collection<String>): List<Step> {
        val asked = starting.toList()
        starting.clear()
        if (!wasRunning) return if (asked.isEmpty()) emptyList() else listOf(Tell(asked, UNAVAILABLE, DID_NOT_START))
        val to = (viewers + asked).distinct()
        if (to.isEmpty()) return emptyList()
        return listOf(Tell(to, ENDED, endedReason(stoppedBy, locked)))
    }

    companion object {
        const val REPEAT_WAIT_MS = 10_000L
        /** The longest anyone is made to wait before asking again. */
        const val MAX_WAIT_MS = 5 * 60_000L
        /** After this long without a prompt that went unanswered or refused, the wait is the shortest again. */
        const val FORGET_MS = 10 * 60_000L
        /** Long enough to find the prompt in a viewer's picture, scroll it and press it. */
        const val ANSWER_MS = 60_000L

        // `state` of `smooth-status`.
        const val PROMPTING = "prompting"
        const val STARTED = "started"
        const val DECLINED = "declined"
        const val ENDED = "ended"
        const val UNAVAILABLE = "unavailable"

        // `smooth` of `screen-info`.
        const val ON = "on"
        /** On, and the only way this phone can show its screen: a viewer cannot stop it. */
        const val ONLY = "only"
        const val AVAILABLE = "available"

        // Reasons. A viewer shows them as they are.
        const val UNLOCK_FIRST = "Unlock the phone first."
        const val ONLY_WAY = "Smooth video is the only way this phone can show its screen, so it can't be stopped from here. " +
            "It can be stopped on the phone."
        const val ANSWER_ON_PHONE = "The phone is asking. Someone has to approve it on the phone itself: " +
            "it has no picture to show until smooth video is on."
        const val WAITING_FOR_APPROVAL = "The phone is waiting for a new device to be approved."
        const val CANNOT_SHOW = "The phone can't show its prompt. Turn on smooth screen sharing in its Permissions screen."
        const val DID_NOT_START = "The phone could not start sharing its screen."
        const val SCREEN_TURNED_OFF = "The phone's screen turned off before the prompt was answered."
        const val NOBODY_ANSWERED = "Nobody answered the prompt on the phone."
        const val STOPPED_ON_PHONE = "Smooth video was stopped on the phone."
        const val PHONE_LOCKED = "Smooth video ended because the phone was locked."
        /** While the notification stands in for the prompt. */
        const val TAP_NOTIFICATION = "Tap the notification on the phone to see the prompt."

        /** Why a request is refused for now, with [leftMs] still to wait. */
        fun askAgainIn(leftMs: Long): String {
            val seconds = (leftMs + 999) / 1000
            val time = if (seconds < 60) "$seconds second${if (seconds == 1L) "" else "s"}"
                else ((seconds + 59) / 60).let { "$it minute${if (it == 1L) "" else "s"}" }
            return "The phone was asked a moment ago and nobody said yes. Ask again in $time."
        }

        /** `smooth` in `screen-info`: what a viewer's menu row can do. */
        fun offer(sharing: Boolean, canShowPrompt: Boolean, otherCapture: Boolean): String = when {
            sharing -> if (otherCapture) ON else ONLY
            canShowPrompt -> AVAILABLE
            else -> UNAVAILABLE
        }

        /**
         * Why smooth sharing ended. Android does not say, so: a viewer's
         * `stop-smooth` is known, and [stoppedBy] is that device's name;
         * otherwise a phone that is locked at that moment was ended by the
         * lock (Android 15 does that), and anything else was someone
         * stopping it on the phone.
         */
        fun endedReason(stoppedBy: String?, locked: Boolean): String = when {
            stoppedBy != null -> "Smooth video was stopped from $stoppedBy."
            locked -> PHONE_LOCKED
            else -> STOPPED_ON_PHONE
        }
    }
}
