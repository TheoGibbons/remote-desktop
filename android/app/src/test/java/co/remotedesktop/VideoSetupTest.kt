package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which encoder and size are tried for a phone's screen ([VideoSetup]),
 * which format its viewers get ([StreamFormat]), and when video is worth
 * trying again ([VideoWish]). Plain JVM.
 */
class VideoSetupTest {
    private fun encoder(name: String, hardware: Boolean, cbr: Boolean = true, upTo: Int = 8192) =
        VideoSetup.Encoder(name, hardware, cbr) { w, h -> maxOf(w, h) <= upTo }

    @Test
    fun theHardwareEncoderIsTriedFirstAtFullSizeThenSmaller() {
        // The S26's own list, as its codecs report themselves.
        val encoders = listOf(encoder("c2.android.avc.encoder", hardware = false, upTo = 2048),
            encoder("c2.qti.avc.encoder", hardware = true), encoder("OMX.qcom.video.encoder.avc", hardware = true))
        val attempts = VideoSetup.attempts(1080, 2340, encoders)
        assertEquals(listOf(
            "c2.qti.avc.encoder 1080x2340", "OMX.qcom.video.encoder.avc 1080x2340",
            "c2.qti.avc.encoder 810x1754", "OMX.qcom.video.encoder.avc 810x1754",
            // A software encoder only once the long side is 1280 or less.
            "c2.qti.avc.encoder 540x1170", "OMX.qcom.video.encoder.avc 540x1170", "c2.android.avc.encoder 540x1170"),
            attempts.map { "${it.encoder} ${it.width}x${it.height}" })
        assertTrue(attempts.all { it.constantBitrate })
    }

    @Test
    fun sidesAreEvenAndTheBitrateGoesByThePixels() {
        val attempts = VideoSetup.attempts(1081, 2401, listOf(encoder("hw", hardware = true, cbr = false)))
        assertEquals(listOf(1080 to 2400, 810 to 1800, 540 to 1200), attempts.map { it.width to it.height })
        assertEquals(listOf(3_000_000, 1_687_500, 750_000), attempts.map { it.bitrate })
        assertFalse(attempts.first().constantBitrate)
        // The same on its side.
        assertEquals(2_925_000, VideoSetup.attempts(2340, 1080, listOf(encoder("hw", true))).first().bitrate)
        // Never below what a picture needs to be read at all, nor above what a phone's upload should carry.
        assertEquals(VideoSetup.MIN_BITRATE, VideoSetup.bitrate(160, 120))
        assertEquals(VideoSetup.MAX_BITRATE, VideoSetup.bitrate(4000, 4000))
    }

    @Test
    fun aSizeIsAskedAboutBothWaysRound() {
        // As many encoders list themselves: up to 4096 wide and 2304 high, a picture on its side.
        val sideways = VideoSetup.Encoder("hw", hardware = true, constantBitrate = true) { w, h -> w <= 4096 && h <= 2304 }
        // An upright phone is 2,340 high. Read strictly it would be sent at three quarters size.
        assertEquals(listOf(1080 to 2340, 810 to 1754, 540 to 1170),
            VideoSetup.attempts(1080, 2340, listOf(sideways)).map { it.width to it.height })
        assertEquals(2340 to 1080, VideoSetup.attempts(2340, 1080, listOf(sideways)).first().let { it.width to it.height })
        // One that takes it neither way is still passed over.
        val small = VideoSetup.Encoder("small", hardware = true, constantBitrate = true) { w, h -> w <= 1920 && h <= 1088 }
        assertEquals(listOf(810 to 1754, 540 to 1170),
            VideoSetup.attempts(1080, 2340, listOf(small)).map { it.width to it.height })
    }

    @Test
    fun theSizesCanBeTakenUpPartOfTheWayDown() {
        val hw = encoder("hw", hardware = true)
        assertEquals(listOf(0, 1, 2), VideoSetup.attempts(1080, 2340, listOf(hw)).map { it.step })
        assertEquals(listOf(810 to 1754, 540 to 1170),
            VideoSetup.attempts(1080, 2340, listOf(hw), fromStep = 1).map { it.width to it.height })
        assertEquals(listOf(2), VideoSetup.attempts(1080, 2340, listOf(hw), fromStep = 2).map { it.step })
        assertTrue(VideoSetup.attempts(1080, 2340, listOf(hw), fromStep = VideoSetup.STEPS).isEmpty())
    }

    @Test
    fun anEncoderThatCannotDoASizeIsNotTriedAtIt() {
        val small = encoder("small-hw", hardware = true, upTo = 1920)
        assertEquals(listOf(810 to 1754, 540 to 1170),
            VideoSetup.attempts(1080, 2340, listOf(small)).map { it.width to it.height })
        // Nothing that will do: pictures stay JPEGs.
        assertTrue(VideoSetup.attempts(1080, 2340, emptyList()).isEmpty())
        assertTrue(VideoSetup.attempts(1080, 2340, listOf(encoder("tiny", hardware = true, upTo = 320))).isEmpty())
        // A phone whose own answer throws is taken as a no.
        val broken = VideoSetup.Encoder("broken", hardware = true, constantBitrate = true) { _, _ -> error("no") }
        assertTrue(VideoSetup.attempts(1080, 2340, listOf(broken)).isEmpty())
    }

    // ---------------- which format the viewers get ----------------

    private val pc = setOf("h264", "jpeg")
    private val pcWithoutVideo = setOf("jpeg")
    private val phone = setOf("jpeg")
    private val oldApp = emptySet<String>()

    @Test
    fun h264OnlyWhenEveryViewerListsItAndThePhoneHasItToSend() {
        assertEquals(StreamFormat.H264, StreamFormat.of(listOf(pc), video = true))
        assertEquals(StreamFormat.H264, StreamFormat.of(listOf(pc, pc), video = true))
        // Accessibility viewing, or an encoder that does not work: JPEG, whatever the viewers offer.
        assertEquals(StreamFormat.JPEG, StreamFormat.of(listOf(pc), video = false))
        // A PC that does not offer it, and a phone viewing this phone, keep everyone on JPEG.
        assertEquals(StreamFormat.JPEG, StreamFormat.of(listOf(pcWithoutVideo), video = true))
        assertEquals(StreamFormat.JPEG, StreamFormat.of(listOf(pc, phone), video = true))
        assertEquals(StreamFormat.JPEG, StreamFormat.of(listOf(phone), video = true))
    }

    @Test
    fun oneViewerOnAnOlderAppPutsEveryoneOnType1() {
        assertEquals(StreamFormat.TYPE_1, StreamFormat.of(listOf(oldApp), video = true))
        assertEquals(StreamFormat.TYPE_1, StreamFormat.of(listOf(pc, oldApp), video = true))
        assertEquals(StreamFormat.TYPE_1, StreamFormat.of(listOf(pc, oldApp), video = false))
        // Every viewer has to list jpeg: one that does not could not be sent the fall-back.
        assertEquals(StreamFormat.TYPE_1, StreamFormat.of(listOf(setOf("h264")), video = true))
        assertEquals("h264", StreamFormat.H264.codec)
        assertEquals("jpeg", StreamFormat.JPEG.codec)
        assertEquals(null, StreamFormat.TYPE_1.codec)
    }

    // ---------------- when video is tried ----------------

    @Test
    fun videoIsWantedWhilePicturesGoOutAndEveryViewerTakesIt() {
        val wish = VideoWish()
        assertFalse(wish.wanted)
        assertEquals(VideoWish.Change(started = true, stopped = false, format = true), wish.update(on = true, allTakeVideo = true))
        assertTrue(wish.wanted)
        // Nothing new: nothing to do.
        assertFalse(wish.update(on = true, allTakeVideo = true).any)
        // A viewer that cannot draw it joins.
        assertEquals(VideoWish.Change(started = false, stopped = false, format = true), wish.update(on = true, allTakeVideo = false))
        assertFalse(wish.wanted)
        // Nobody views.
        assertEquals(VideoWish.Change(started = false, stopped = true, format = false), wish.update(on = false, allTakeVideo = false))
        // Viewers that would take video, but no pictures are going out.
        assertFalse(wish.update(on = false, allTakeVideo = true).any)
        assertFalse(wish.wanted)
    }

    @Test
    fun aTryThatFailedIsNotMadeAgainAndTheNextOneIs() {
        val wish = VideoWish()
        wish.update(on = true, allTakeVideo = true)
        val first = wish.tried()
        wish.failed(first)
        assertFalse(wish.wanted)
        // Looking again, with nothing changed, is not a new try.
        wish.update(on = true, allTakeVideo = true)
        assertFalse(wish.wanted)

        // Pictures pause and start again: a new try.
        wish.update(on = false, allTakeVideo = true)
        wish.update(on = true, allTakeVideo = true)
        assertTrue(wish.wanted)
        wish.failed(wish.tried())
        assertFalse(wish.wanted)

        // The viewers stop agreeing on video and agree again: a new try.
        wish.update(on = true, allTakeVideo = false)
        wish.update(on = true, allTakeVideo = true)
        assertTrue(wish.wanted)
    }

    @Test
    fun aFailureThatComesLateDoesNotCountAgainstANewerTry() {
        val wish = VideoWish()
        wish.update(on = true, allTakeVideo = true)
        val old = wish.tried()
        wish.update(on = false, allTakeVideo = true)
        wish.update(on = true, allTakeVideo = true)
        wish.failed(old)
        assertTrue(wish.wanted)
    }
}
