package co.remotedesktop

import org.junit.Assert.*
import org.junit.Test

/** When a phone holds a picture back, going by what its viewers say has arrived. Plain JVM. */
class InFlightTest {
    private val pc = listOf("pc")
    private val both = listOf("pc", "phone")

    @Test
    fun twoFramesMayBeOnTheirWayBeforeAnythingIsAcknowledged() {
        val flight = InFlight()
        assertTrue(flight.hasRoom(0))
        flight.sent(0, nowMs = 0, to = pc)
        assertTrue(flight.hasRoom(10))
        flight.sent(1, nowMs = 100, to = pc)
        assertFalse("A third waits for the first acknowledgement", flight.hasRoom(200))
        flight.acked("pc", 0, nowMs = 250)
        assertEquals(0L, flight.ackedBy("pc"))
        assertTrue(flight.hasRoom(250))
    }

    @Test
    fun thereIsRoomWhileNoFrameHasWaitedMuchLongerThanUsual() {
        val flight = InFlight()
        // Frames usually take 80 ms to be acknowledged; one took 300.
        flight.sent(0, 0, pc); flight.acked("pc", 0, 80)
        flight.sent(1, 1_000, pc); flight.acked("pc", 1, 1_300)
        flight.sent(2, 2_000, pc)
        assertTrue(flight.hasRoom(2_000 + 80 + InFlight.SLACK_MS))
        assertFalse(flight.hasRoom(2_000 + 80 + InFlight.SLACK_MS + 1))
        // Several on their way at once is fine while each is young.
        flight.acked("pc", 2, 2_500)
        flight.sent(3, 3_000, pc); flight.sent(4, 3_033, pc); flight.sent(5, 3_066, pc)
        assertTrue(flight.hasRoom(3_100))
        assertFalse("The oldest one sets it", flight.hasRoom(3_181))
        // One acknowledgement covers every frame up to its number.
        flight.acked("pc", 5, 3_200)
        assertTrue(flight.hasRoom(3_300))
        // "Usual" is the shortest in the last minute. Once the 80 ms is that old, 300 is usual.
        flight.sent(6, 30_000, pc); flight.acked("pc", 6, 30_300)
        flight.sent(7, 70_000, pc)
        assertTrue(flight.hasRoom(70_400))
        assertFalse(flight.hasRoom(70_401))
    }

    @Test
    fun theSlowestViewerSetsThePaceEachByItsOwnUsualTime() {
        val flight = InFlight()
        // The PC is near, the other phone is far.
        flight.sent(0, 0, both)
        flight.acked("pc", 0, 20); flight.acked("phone", 0, 400)
        flight.sent(1, 1_000, both)
        flight.acked("pc", 1, 1_020)
        // The far one is not late by its own measure, so its 400 ms does not hold the stream to one frame at a time.
        assertTrue(flight.hasRoom(1_300))
        assertTrue(flight.hasRoom(1_500))
        assertFalse("But once it is late, everyone waits for it", flight.hasRoom(1_501))
        flight.acked("phone", 1, 1_600)
        assertTrue(flight.hasRoom(1_600))
    }

    @Test
    fun aViewerThatHasGoneQuietStopsCountingUntilItAnswersAgain() {
        val flight = InFlight()
        flight.sent(0, 0, both)
        flight.acked("pc", 0, 50); flight.acked("phone", 0, 60)
        flight.sent(1, 1_000, both)
        flight.acked("pc", 1, 1_050)
        assertFalse(flight.hasRoom(1_200))
        assertFalse(flight.hasRoom(3_000))
        // Two seconds without a word: it no longer holds the PC back.
        assertTrue(flight.hasRoom(3_001))
        flight.sent(2, 3_001, both)
        flight.acked("pc", 2, 3_050)
        assertTrue(flight.hasRoom(3_500))
        // It answers again, and counts again from there.
        flight.acked("phone", 2, 3_600)
        flight.sent(3, 4_000, both)
        flight.acked("pc", 3, 4_050)
        assertFalse(flight.hasRoom(4_300))

        // A viewer that never acknowledges anything gets two frames, then two seconds, and is left out.
        val silent = InFlight()
        silent.sent(0, 0, pc); silent.sent(1, 400, pc)
        assertFalse(silent.hasRoom(800))
        assertTrue(silent.hasRoom(2_001))
        assertNull(silent.ackedBy("pc"))
    }

    @Test
    fun aViewerThatJoinsOrLeavesChangesWhoIsWaitedFor() {
        val flight = InFlight()
        flight.sent(0, 0, pc); flight.acked("pc", 0, 50)
        flight.sent(1, 1_000, pc)
        // The phone joins at frame 2: it owes nothing for the frames before.
        flight.sent(2, 1_040, both)
        flight.acked("pc", 2, 1_090)
        flight.acked("phone", 1, 1_100) // not a frame it was sent
        assertNull(flight.ackedBy("phone"))
        assertTrue(flight.hasRoom(1_100))
        flight.sent(3, 1_200, both)
        flight.acked("pc", 3, 1_205)
        assertFalse("Two on their way to the newcomer", flight.hasRoom(1_210))
        // It leaves: nobody waits for it any more.
        flight.sent(4, 1_300, pc)
        flight.acked("pc", 4, 1_350)
        assertTrue(flight.hasRoom(1_400))
        // Nonsense is ignored: a frame not sent yet, one acknowledged already, someone not viewing.
        flight.acked("pc", 9, 1_400); flight.acked("pc", 2, 1_400); flight.acked("stranger", 4, 1_400)
        assertEquals(4L, flight.ackedBy("pc"))
        assertNull(flight.ackedBy("stranger"))
    }

    @Test
    fun theNewestFrameIsSentAgainIfNobodySaysItArrivedAndThenLessAndLessOften() {
        val flight = InFlight()
        assertNull(flight.resendIn(0))
        flight.sent(0, 0, pc); flight.acked("pc", 0, 50)
        assertNull("Acknowledged: nothing is owed", flight.resendIn(100))

        flight.sent(1, 1_000, pc)
        assertEquals(1_000L, flight.resendIn(1_000))
        assertFalse(flight.resendDue(1_999))
        assertTrue(flight.resendDue(2_000))
        // Sent again, as frame 2. The next try waits two seconds, then four, then eight.
        flight.sent(2, 2_000, pc, repeat = true)
        assertFalse(flight.resendDue(3_999))
        assertTrue(flight.resendDue(4_000))
        flight.sent(3, 4_000, pc, repeat = true)
        assertEquals(4_000L, flight.resendIn(4_000))
        flight.sent(4, 8_000, pc, repeat = true)
        assertEquals(8_000L, flight.resendIn(8_000))
        flight.sent(5, 16_000, pc, repeat = true)
        assertNull("Four tries, and then no more", flight.resendIn(60_000))

        // The screen changes: a new picture starts over.
        flight.sent(6, 61_000, pc)
        assertEquals(1_000L, flight.resendIn(61_000))
        // An acknowledgement of an earlier frame is not one of the newest.
        flight.sent(7, 61_050, pc)
        flight.acked("pc", 6, 61_100)
        assertFalse(flight.resendDue(62_049))
        assertTrue(flight.resendDue(62_050))
        flight.acked("pc", 7, 62_100)
        assertNull(flight.resendIn(62_100))

        // With two viewers it is owed while either has not acknowledged it.
        flight.sent(8, 70_000, both)
        flight.acked("pc", 8, 70_050)
        assertTrue(flight.resendDue(71_000))
        flight.acked("phone", 8, 71_010)
        assertNull(flight.resendIn(71_020))

        flight.reset()
        assertNull(flight.resendIn(80_000))
        assertTrue(flight.hasRoom(80_000))
        assertNull(flight.ackedBy("pc"))
    }

    @Test
    fun onASlowLinkAFrameIsGivenItsUsualTimeBeforeItIsSentAgain() {
        val flight = InFlight()
        // A picture takes a second and a half to be acknowledged here, every time.
        flight.sent(0, 0, pc); flight.acked("pc", 0, 1_500)
        flight.sent(1, 10_000, pc)
        assertEquals(1_500 + InFlight.SLACK_MS, flight.resendIn(10_000))
        assertFalse("A second proves nothing on this link", flight.resendDue(11_000))
        assertFalse(flight.resendDue(11_599))
        // It arrives when it usually does, and was never owed again.
        flight.acked("pc", 1, 11_500)
        assertNull(flight.resendIn(11_600))

        // One that really is lost goes again after that time, and the tries after it double from there.
        flight.sent(2, 20_000, pc)
        assertFalse(flight.resendDue(21_599))
        assertTrue(flight.resendDue(21_600))
        flight.sent(3, 21_600, pc, repeat = true)
        assertEquals(3_200L, flight.resendIn(21_600))
        flight.acked("pc", 3, 23_100)

        // The slowest viewer that still owes the frame sets the wait, and a near one does not shorten it.
        val two = InFlight()
        two.sent(0, 0, both)
        two.acked("pc", 0, 40); two.acked("phone", 0, 1_500)
        two.sent(1, 10_000, both)
        two.acked("pc", 1, 10_040)
        assertFalse(two.resendDue(11_000))
        assertTrue(two.resendDue(11_600))
        // Once only the near one owes it, its own second counts.
        two.sent(2, 20_000, both)
        two.acked("phone", 2, 20_900)
        assertFalse(two.resendDue(20_999))
        assertTrue(two.resendDue(21_000))

        // A viewer back from a long stall has one very long time to its name. The wait stops at eight seconds.
        val stalled = InFlight()
        stalled.sent(0, 0, pc); stalled.acked("pc", 0, 45_000)
        stalled.sent(1, 50_000, pc)
        assertEquals(8_000L, stalled.resendIn(50_000))
        stalled.sent(2, 58_000, pc, repeat = true)
        assertEquals(16_000L, stalled.resendIn(58_000))
    }
}
