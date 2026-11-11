package co.remotedesktop

import co.remotedesktop.SmoothSharing.Asker
import co.remotedesktop.SmoothSharing.ClosePrompt
import co.remotedesktop.SmoothSharing.Outcome
import co.remotedesktop.SmoothSharing.Phone
import co.remotedesktop.SmoothSharing.ShowPrompt
import co.remotedesktop.SmoothSharing.StopSharing
import co.remotedesktop.SmoothSharing.Tell
import org.junit.Assert.*
import org.junit.Test

/** Who may ask a phone for smooth sharing, when its prompt is shown and taken away, and who is told what. */
class SmoothSharingTest {
    private val viewer = Asker(approved = true, controlOn = true, kindAllowed = true, viewing = true)
    private val ready = Phone(sharing = false, inUse = true, sending = true, canShowPrompt = true, otherCapture = true)
    private val rules = SmoothSharing()

    private fun tell(state: String, reason: String? = null, vararg to: String) = Tell(to.toList(), state, reason)

    /** Every device that lacks any one of the four things, or several of them. */
    private val refused: List<Asker> = buildList {
        for (bits in 0 until 15) add(Asker(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0))
    }

    @Test
    fun onlyAnApprovedDeviceThatIsViewingMayAsk() {
        assertEquals(15, refused.size)
        assertTrue(refused.none { it.mayAsk })
        assertTrue(viewer.mayAsk)
        // By name, the ones that matter most.
        val stranger = viewer.copy(approved = false)
        val notViewing = viewer.copy(viewing = false)
        val phoneWithPhonesOff = viewer.copy(kindAllowed = false)
        val controlOff = viewer.copy(controlOn = false)
        for (asker in listOf(stranger, notViewing, phoneWithPhonesOff, controlOff)) assertTrue(asker in refused)

        for (asker in refused) {
            // Not answered at all, whatever state the phone is in, and nothing is shown.
            for (phone in listOf(ready, ready.copy(sharing = true), ready.copy(inUse = false), ready.copy(sending = false),
                ready.copy(canShowPrompt = false))) {
                assertEquals("$asker $phone", emptyList<Any>(), rules.request("x", asker, phone, now = 0))
                assertFalse(rules.prompting)
            }
            assertEquals(emptyList<Any>(), rules.stop("x", asker, sharing = true, otherCapture = true))
            assertEquals(emptyList<Any>(), rules.stop("x", asker, sharing = true, otherCapture = false))
        }
    }

    @Test
    fun aRefusedDeviceCannotJoinOrDisturbAPromptThatIsUp() {
        rules.request("pc", viewer, ready, now = 0)
        for (asker in refused) assertEquals(emptyList<Any>(), rules.request("x", asker, ready, now = 1))
        assertEquals(listOf("pc"), rules.waitingFor)
        // Nor is it told how the prompt ended.
        assertEquals(listOf(tell("declined", null, "pc")), rules.answered(Outcome.DECLINED, now = 2))
    }

    @Test
    fun aViewerOfAPhoneInUseGetsThePrompt() {
        assertEquals(listOf(tell("prompting", null, "pc"), ShowPrompt), rules.request("pc", viewer, ready, now = 1000))
        assertTrue(rules.prompting)
        assertEquals(1000 + SmoothSharing.ANSWER_MS, rules.giveUpAt)
    }

    @Test
    fun aPhoneThatCannotShowItSaysWhy() {
        // Already sharing: that is the answer, locked or not (Android 13 goes on sharing while locked).
        assertEquals(listOf(tell("started", null, "pc")),
            rules.request("pc", viewer, ready.copy(sharing = true, inUse = false), now = 0))
        assertEquals(listOf(tell("unavailable", SmoothSharing.UNLOCK_FIRST, "pc")),
            rules.request("pc", viewer, ready.copy(inUse = false), now = 0))
        // Nobody could see the prompt in a viewer while pictures are held back.
        assertEquals(listOf(tell("unavailable", SmoothSharing.WAITING_FOR_APPROVAL, "pc")),
            rules.request("pc", viewer, ready.copy(sending = false), now = 0))
        assertEquals(listOf(tell("unavailable", SmoothSharing.CANNOT_SHOW, "pc")),
            rules.request("pc", viewer, ready.copy(canShowPrompt = false), now = 0))
        assertFalse(rules.prompting)
    }

    @Test
    fun oneAPromptAtATime() {
        rules.request("pc", viewer, ready, now = 0)
        // The same viewer again, and another: both wait for the one prompt.
        assertEquals(listOf(tell("prompting", null, "pc")), rules.request("pc", viewer, ready, now = 500))
        assertEquals(listOf(tell("prompting", null, "phone")), rules.request("phone", viewer, ready, now = 600))
        assertEquals(listOf("pc", "phone"), rules.waitingFor)
        // The time it is given runs from when it went up.
        assertEquals(SmoothSharing.ANSWER_MS, rules.giveUpAt)
        // And both hear the answer, once.
        assertEquals(listOf(tell("declined", null, "pc", "phone")), rules.answered(Outcome.DECLINED, now = 700))
        assertEquals(emptyList<Any>(), rules.answered(Outcome.DECLINED, now = 701))
    }

    @Test
    fun afterARefusalNobodyAsksAgainForTenSeconds() {
        rules.request("pc", viewer, ready, now = 0)
        rules.answered(Outcome.DECLINED, now = 2_000)
        assertEquals(listOf(tell("unavailable", SmoothSharing.JUST_DECLINED, "pc")),
            rules.request("pc", viewer, ready, now = 2_001))
        assertEquals(listOf(tell("unavailable", SmoothSharing.JUST_DECLINED, "phone")),
            rules.request("phone", viewer, ready, now = 11_999))
        assertFalse(rules.prompting)
        assertEquals(listOf(tell("prompting", null, "pc"), ShowPrompt), rules.request("pc", viewer, ready, now = 12_000))
    }

    @Test
    fun anApprovedPromptIsNewsOnlyOnceSharingRuns() {
        rules.request("pc", viewer, ready, now = 0)
        rules.request("phone", viewer, ready, now = 1)
        assertEquals(emptyList<Any>(), rules.answered(Outcome.GRANTED, now = 5))
        assertFalse(rules.prompting)
        assertEquals(listOf(tell("started", null, "pc", "phone")), rules.started())
        assertEquals(emptyList<Any>(), rules.started())
    }

    @Test
    fun sharingThatNeverGotGoingIsSaidToo() {
        rules.request("pc", viewer, ready, now = 0)
        rules.answered(Outcome.GRANTED, now = 5)
        assertEquals(listOf(tell("unavailable", SmoothSharing.DID_NOT_START, "pc")),
            rules.ended(wasRunning = false, stoppedBy = null, locked = false, viewers = listOf("pc", "other")))
        // With nobody waiting there is nobody to tell.
        assertEquals(emptyList<Any>(), rules.ended(wasRunning = false, stoppedBy = null, locked = false, viewers = listOf("pc")))
    }

    @Test
    fun everyWayAPromptEndsIsToldOnce() {
        fun asked(): SmoothSharing = SmoothSharing().also { it.request("pc", viewer, ready, now = 0) }

        asked().let {
            assertEquals(listOf(tell("declined", null, "pc")), it.answered(Outcome.DECLINED, now = 1))
            assertEquals(emptyList<Any>(), it.answered(Outcome.DECLINED, now = 2))
            assertEquals(emptyList<Any>(), it.tick(now = SmoothSharing.ANSWER_MS))
        }
        asked().let {
            assertEquals(listOf(ClosePrompt, tell("declined", SmoothSharing.SCREEN_TURNED_OFF, "pc")),
                it.answered(Outcome.SCREEN_OFF, now = 1))
            assertEquals(emptyList<Any>(), it.answered(Outcome.DECLINED, now = 2))
        }
        asked().let {
            assertEquals(listOf(ClosePrompt, tell("unavailable", SmoothSharing.CANNOT_SHOW, "pc")),
                it.answered(Outcome.NOT_SHOWN, now = 1))
            assertFalse(it.prompting)
        }
        asked().let {
            assertEquals(emptyList<Any>(), it.tick(now = SmoothSharing.ANSWER_MS - 1))
            assertEquals(listOf(ClosePrompt, tell("declined", SmoothSharing.NOBODY_ANSWERED, "pc")),
                it.tick(now = SmoothSharing.ANSWER_MS))
            assertEquals(emptyList<Any>(), it.tick(now = SmoothSharing.ANSWER_MS + 1))
            // An answer that comes after that changes nothing: nothing is started.
            assertEquals(emptyList<Any>(), it.answered(Outcome.GRANTED, now = SmoothSharing.ANSWER_MS + 2))
            assertEquals(emptyList<Any>(), it.started())
            // Nobody refused, so it can be asked for again at once.
            assertEquals(ShowPrompt, it.request("pc", viewer, ready, now = SmoothSharing.ANSWER_MS + 3).last())
        }
    }

    @Test
    fun aPromptGoesWhenWhoeverAskedHasStoppedViewing() {
        rules.request("pc", viewer, ready, now = 0)
        rules.request("phone", viewer, ready, now = 1)
        // One of the two leaves: the other is still waiting.
        assertEquals(emptyList<Any>(), rules.viewersNow(listOf("phone", "bystander")))
        assertTrue(rules.prompting)
        // The other leaves, though a device that never asked is still viewing.
        assertEquals(listOf<Any>(ClosePrompt), rules.viewersNow(listOf("bystander")))
        assertFalse(rules.prompting)
        assertNull(rules.giveUpAt)
        // With no prompt up, viewers coming and going do nothing.
        assertEquals(emptyList<Any>(), rules.viewersNow(emptyList()))
    }

    @Test
    fun aViewerThatLeftIsNotToldSharingStarted() {
        rules.request("pc", viewer, ready, now = 0)
        rules.request("phone", viewer, ready, now = 1)
        rules.answered(Outcome.GRANTED, now = 2)
        rules.viewersNow(listOf("phone"))
        assertEquals(listOf(tell("started", null, "phone")), rules.started())
    }

    @Test
    fun stoppingIsForViewersToo() {
        assertEquals(listOf<Any>(StopSharing), rules.stop("pc", viewer, sharing = true, otherCapture = true))
        assertEquals(emptyList<Any>(), rules.stop("pc", viewer, sharing = false, otherCapture = true))
    }

    /** Android 10 and earlier, or the Accessibility service off: without smooth sharing there is no picture. */
    @Test
    fun aViewerCannotStopTheOnlyWayThePhoneHasOfShowingItsScreen() {
        assertEquals(listOf(tell("unavailable", SmoothSharing.ONLY_WAY, "pc")),
            rules.stop("pc", viewer, sharing = true, otherCapture = false))
        assertEquals(emptyList<Any>(), rules.stop("pc", viewer, sharing = false, otherCapture = false))

        // Asked to start on such a phone, the viewer is told the prompt cannot be answered from where it is.
        val blind = ready.copy(otherCapture = false)
        assertEquals(listOf(tell("prompting", SmoothSharing.ANSWER_ON_PHONE, "pc"), ShowPrompt),
            rules.request("pc", viewer, blind, now = 0))
        assertEquals(listOf(tell("prompting", SmoothSharing.ANSWER_ON_PHONE, "phone")),
            rules.request("phone", viewer, blind, now = 1))
    }

    @Test
    fun theEndIsToldWithItsReason() {
        fun ended(stoppedBy: String?, locked: Boolean) =
            rules.ended(wasRunning = true, stoppedBy, locked, viewers = listOf("pc", "phone")).single() as Tell

        assertEquals(tell("ended", "Smooth video was stopped from Test PC.", "pc", "phone"), ended("Test PC", locked = false))
        // A viewer's stop is known; the lock is only how things look at that moment.
        assertEquals("Smooth video was stopped from Test PC.", ended("Test PC", locked = true).reason)
        assertEquals(SmoothSharing.PHONE_LOCKED, ended(null, locked = true).reason)
        assertEquals(SmoothSharing.STOPPED_ON_PHONE, ended(null, locked = false).reason)
        // Nobody viewing, nobody told.
        assertEquals(emptyList<Any>(), rules.ended(wasRunning = true, stoppedBy = null, locked = false, viewers = emptyList()))
    }

    @Test
    fun whatAViewersMenuIsOffered() {
        assertEquals("on", SmoothSharing.offer(sharing = true, canShowPrompt = false, otherCapture = true))
        // On, and not to be stopped from a viewer: nothing else would show the screen.
        assertEquals("only", SmoothSharing.offer(sharing = true, canShowPrompt = true, otherCapture = false))
        assertEquals("available", SmoothSharing.offer(sharing = false, canShowPrompt = true, otherCapture = true))
        assertEquals("available", SmoothSharing.offer(sharing = false, canShowPrompt = true, otherCapture = false))
        assertEquals("unavailable", SmoothSharing.offer(sharing = false, canShowPrompt = false, otherCapture = true))
    }
}
