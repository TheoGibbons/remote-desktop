package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the screen is drawn into the video encoder, and as what. Plain JVM. */
class VideoPacerTest {
    private val pacer = VideoPacer(minIntervalMs = 33, retryMs = 15, sharpenAfterMs = listOf(150, 600),
        safetyKeyMs = 30_000, viewerKeyEveryMs = 1_000)

    /** A pacer whose encoder has already made its first picture, a keyframe, at [at]. */
    private fun started(at: Long = 1_000): VideoPacer {
        pacer.reset()
        pacer.changed(at)
        assertEquals(VideoPacer.Draw(keyframe = true, repeat = false, resend = false, capturedAt = at),
            pacer.next(at, hasRoom = true))
        return pacer
    }

    /** Lets the two sharpening repeats after a picture at [from] go by. */
    private fun settle(from: Long) {
        assertTrue(pacer.next(from + 150, true)!!.repeat)
        assertTrue(pacer.next(from + 600, true)!!.repeat)
        assertNull(pacer.next(from + 5_000, true))
    }

    @Test
    fun nothingIsDrawnBeforeTheScreensFirstPicture() {
        pacer.reset()
        // A keyframe is wanted from the start, but the texture is empty: it would be a black one.
        pacer.wantKeyframe()
        assertNull(pacer.next(1_000, hasRoom = true))
        assertNull(pacer.delay(1_000, hasRoom = true))
        pacer.changed(1_010)
        assertEquals(VideoPacer.Draw(keyframe = true, repeat = false, resend = false, capturedAt = 1_010),
            pacer.next(1_012, hasRoom = true))
    }

    @Test
    fun aChangedScreenIsDrawnNoFasterThanThirtyASecondAndTheNewestGoes() {
        started(1_000)
        pacer.changed(1_005)
        assertNull(pacer.next(1_005, true))
        assertEquals(28L, pacer.delay(1_005, true))
        // More changes while it waits: they replace one another in the texture.
        pacer.changed(1_016)
        pacer.changed(1_030)
        assertNull(pacer.next(1_032, true))
        assertEquals(VideoPacer.Draw(keyframe = false, repeat = false, resend = false, capturedAt = 1_030),
            pacer.next(1_033, true))
        // One draw for the three changes.
        assertNull(pacer.next(1_066, true))
    }

    @Test
    fun everyEncodeWaitsForRoom() {
        started(1_000)
        pacer.changed(1_100)
        assertNull(pacer.next(1_100, hasRoom = false))
        assertEquals(15L, pacer.delay(1_100, hasRoom = false))
        pacer.changed(1_300)
        assertNull(pacer.next(1_300, hasRoom = false))
        // Room at last: the newest picture, not the one that first had to wait.
        assertEquals(1_300L, pacer.next(1_400, hasRoom = true)!!.capturedAt)

        // A keyframe waits too, and so does a repeat.
        pacer.wantKeyframe()
        assertNull(pacer.next(1_500, hasRoom = false))
        assertEquals(15L, pacer.delay(1_500, hasRoom = false))
        assertTrue(pacer.next(1_520, hasRoom = true)!!.keyframe)
        assertNull(pacer.next(1_520 + 150, hasRoom = false))
        assertEquals(15L, pacer.delay(1_520 + 150, hasRoom = false))
        assertTrue(pacer.next(1_520 + 190, hasRoom = true)!!.repeat)
    }

    @Test
    fun aStillScreenIsSharpenedTwiceAndThenSendsNothing() {
        started(1_000)
        assertNull(pacer.next(1_100, true))
        assertEquals(50L, pacer.delay(1_100, true))
        assertEquals(VideoPacer.Draw(keyframe = false, repeat = true, resend = false, capturedAt = 1_150),
            pacer.next(1_150, true))
        assertEquals(450L, pacer.delay(1_150, true))
        assertEquals(VideoPacer.Draw(keyframe = false, repeat = true, resend = false, capturedAt = 1_600),
            pacer.next(1_600, true))
        // And then nothing, for as long as the screen is still.
        assertNull(pacer.delay(1_600, true))
        assertNull(pacer.next(60_000, true))
        assertNull(pacer.next(600_000, true))
    }

    @Test
    fun repeatsStopWhenTheScreenChanges() {
        started(1_000)
        assertTrue(pacer.next(1_150, true)!!.repeat)
        pacer.changed(1_400)
        // What would have been the second repeat is the new picture, and the count starts over from it.
        assertFalse(pacer.next(1_600, true)!!.repeat)
        assertNull(pacer.next(1_700, true))
        assertTrue(pacer.next(1_750, true)!!.repeat)
        assertTrue(pacer.next(2_200, true)!!.repeat)
        assertNull(pacer.next(9_000, true))
    }

    @Test
    fun aKeyframeIsMadeOnAStillScreenWhenOneIsWanted() {
        started(1_000)
        settle(1_000)
        pacer.wantKeyframe()
        assertEquals(1L, pacer.delay(10_000, true))
        // The picture the texture holds, again: a repeat, stamped now.
        assertEquals(VideoPacer.Draw(keyframe = true, repeat = true, resend = false, capturedAt = 10_000),
            pacer.next(10_000, true))
        // Asked for once, made once. It is sharpened like any picture.
        assertFalse(pacer.next(10_150, true)!!.keyframe)
        assertFalse(pacer.next(10_600, true)!!.keyframe)
        assertNull(pacer.next(20_000, true))
    }

    @Test
    fun aChangedPictureThatIsDueCarriesTheKeyframe() {
        started(1_000)
        pacer.changed(2_000)
        pacer.wantKeyframe()
        assertEquals(VideoPacer.Draw(keyframe = true, repeat = false, resend = false, capturedAt = 2_000),
            pacer.next(2_000, true))
    }

    @Test
    fun viewersGetOneKeyframeASecondBetweenThem() {
        started(1_000)
        settle(1_000)
        // The first request is honoured at once.
        pacer.wantKeyframe(byViewer = true)
        assertTrue(pacer.next(10_000, true)!!.keyframe)
        // A second viewer asks a moment later, and a third: one keyframe, when the second is up.
        pacer.wantKeyframe(byViewer = true)
        pacer.wantKeyframe(byViewer = true)
        assertFalse(pacer.next(10_150, true)!!.keyframe)
        assertFalse(pacer.next(10_600, true)!!.keyframe)
        assertNull(pacer.next(10_999, true))
        assertEquals(1L, pacer.delay(10_999, true))
        assertTrue(pacer.next(11_000, true)!!.keyframe)
        assertFalse(pacer.next(11_150, true)!!.keyframe)
        assertFalse(pacer.next(11_600, true)!!.keyframe)
        assertNull(pacer.next(13_000, true))

        // A changing screen does not bring it forward: ordinary pictures go meanwhile.
        pacer.wantKeyframe(byViewer = true)
        assertTrue(pacer.next(13_000, true)!!.keyframe)
        pacer.wantKeyframe(byViewer = true)
        pacer.changed(13_100)
        assertEquals(VideoPacer.Draw(keyframe = false, repeat = false, resend = false, capturedAt = 13_100),
            pacer.next(13_100, true))
        pacer.changed(13_990)
        assertFalse(pacer.next(13_990, true)!!.keyframe)
        pacer.changed(14_030)
        assertTrue(pacer.next(14_030, true)!!.keyframe)
    }

    @Test
    fun aKeyframeThePhoneNeedsItselfIsNotHeldToTheViewersLimit() {
        started(1_000)
        settle(1_000)
        pacer.wantKeyframe(byViewer = true)
        assertTrue(pacer.next(10_000, true)!!.keyframe)
        // A viewer joins, or a picture could not be sent: at once, limit or no limit.
        pacer.wantKeyframe(byViewer = true)
        pacer.wantKeyframe()
        assertTrue(pacer.next(10_040, true)!!.keyframe)
        // And it served the viewer that was waiting too.
        assertFalse(pacer.next(10_190, true)!!.keyframe)
        assertFalse(pacer.next(10_640, true)!!.keyframe)
        assertNull(pacer.next(12_000, true))
    }

    @Test
    fun anUnacknowledgedPictureGoesAgainAsAKeyframeMarkedSo() {
        started(1_000)
        settle(1_000)
        pacer.wantResend()
        assertEquals(VideoPacer.Draw(keyframe = true, repeat = true, resend = true, capturedAt = 3_000),
            pacer.next(3_000, true))
        assertNull(pacer.next(3_040, true))

        // If the screen has changed meanwhile, the new picture is what goes, as a keyframe:
        // whatever was built on the lost one could not be drawn.
        settle(3_000)
        pacer.wantResend()
        pacer.changed(9_000)
        assertEquals(VideoPacer.Draw(keyframe = true, repeat = false, resend = false, capturedAt = 9_000),
            pacer.next(9_000, true))
    }

    @Test
    fun theSafetyKeyframeGoesByTheClockAndNeverOnAStillScreen() {
        started(1_000)
        settle(1_000)
        // Long past thirty seconds, and still: nothing.
        assertNull(pacer.next(100_000, true))
        assertNull(pacer.delay(100_000, true))
        // The next changed picture is the keyframe.
        pacer.changed(100_500)
        assertTrue(pacer.next(100_500, true)!!.keyframe)
        // Within thirty seconds of that one, changed pictures are ordinary.
        pacer.changed(120_000)
        assertFalse(pacer.next(120_000, true)!!.keyframe)
        pacer.changed(130_499)
        assertFalse(pacer.next(130_499, true)!!.keyframe)
        pacer.changed(130_540)
        assertTrue(pacer.next(130_540, true)!!.keyframe)
    }

    @Test
    fun afterAPauseTheNextPictureIsAKeyframeStampedNow() {
        started(1_000)
        settle(1_000)
        // The screen changed while nothing was being sent.
        pacer.changed(10_000)
        pacer.resumed(12_000)
        assertEquals(VideoPacer.Draw(keyframe = true, repeat = false, resend = false, capturedAt = 12_000),
            pacer.next(12_000, true))
        settle(12_000)
        // It did not: the same picture again, as a keyframe.
        pacer.resumed(20_000)
        assertEquals(VideoPacer.Draw(keyframe = true, repeat = true, resend = false, capturedAt = 20_000),
            pacer.next(20_000, true))
    }

    @Test
    fun aNewEncoderStartsOverAndWaitsForItsFirstPicture() {
        started(1_000)
        pacer.changed(1_100)
        pacer.wantKeyframe(byViewer = true)
        pacer.reset()
        // What was waiting belonged to the old encoder's texture.
        assertNull(pacer.next(1_200, true))
        assertNull(pacer.delay(1_200, true))
        pacer.changed(1_250)
        assertEquals(VideoPacer.Draw(keyframe = true, repeat = false, resend = false, capturedAt = 1_250),
            pacer.next(1_250, true))
        // Counted as a keyframe: the safety one is thirty seconds from here, not from the old encoder's.
        pacer.changed(20_000)
        assertFalse(pacer.next(20_000, true)!!.keyframe)
    }
}
