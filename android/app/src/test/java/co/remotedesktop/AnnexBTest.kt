package co.remotedesktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an encoded H.264 picture is sent as: parameter sets with every keyframe, and no filler. Plain JVM. */
class AnnexBTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun long(type: Int, vararg body: Int) = bytes(0, 0, 0, 1, type, *body)
    private fun short(type: Int, vararg body: Int) = bytes(0, 0, 1, type, *body)

    private val sps = long(0x67, 0x64, 0x00, 0x32)
    private val pps = long(0x68, 0xEE, 0x06)
    private val idr = long(0x65, 0xB8, 0x40, 0xF7)
    private val slice = long(0x41, 0x9A, 0x02, 0x11)
    private val filler = long(0x0C, 0xFF, 0xFF, 0xFF, 0x80)
    private val delimiter = long(0x09, 0xF0)
    private val sets = sps + pps

    private fun types(data: ByteArray) = AnnexB.units(data).map { it.type }

    @Test
    fun nalUnitsAreFoundBehindBothFormsOfStartCode() {
        val data = sps + short(0x68, 0xEE) + idr + short(0x41, 0x00, 0x00, 0x03, 0x01)
        val units = AnnexB.units(data)
        assertEquals(listOf(7, 8, 5, 1), units.map { it.type })
        // Each one runs to the start code of the next, and the last to the end.
        assertEquals(listOf(0, 8, 13, 21), units.map { it.from })
        assertEquals(listOf(8, 13, 21, data.size), units.map { it.until })
        // Bytes that only look like the beginning of a start code are part of the unit.
        assertTrue(AnnexB.units(bytes(1, 2, 3)).isEmpty())
        assertTrue(AnnexB.units(ByteArray(0)).isEmpty())
    }

    @Test
    fun aKeyframeGetsItsParameterSetsInFrontOnce() {
        // As the S26's encoder gives it: the picture alone, the sets having come before the first one.
        val sent = AnnexB.picture(idr, sets)!!
        assertTrue(sent.keyframe)
        assertArrayEquals(sps + pps + idr, sent.bytes)

        // An encoder that puts them there itself is left alone: not twice.
        val whole = sps + pps + idr
        val asIs = AnnexB.picture(whole, sets)!!
        assertTrue(asIs.keyframe)
        assertSame(whole, asIs.bytes)
        assertEquals(listOf(7, 8, 5), types(asIs.bytes))
    }

    @Test
    fun aKeyframeNobodyAskedForGetsThemToo() {
        // It is told by what it holds, not by whether one was asked for.
        val unasked = AnnexB.picture(short(0x65, 0x88, 0x84), sets)!!
        assertTrue(unasked.keyframe)
        assertEquals(listOf(7, 8, 5), types(unasked.bytes))
    }

    @Test
    fun anOrdinaryPictureIsSentAsItIs() {
        val sent = AnnexB.picture(slice, sets)!!
        assertFalse(sent.keyframe)
        assertSame(slice, sent.bytes)
    }

    @Test
    fun fillerIsLeftOut() {
        val padded = AnnexB.picture(slice + filler, sets)!!
        assertFalse(padded.keyframe)
        assertArrayEquals(slice, padded.bytes)

        val key = AnnexB.picture(idr + filler, sets)!!
        assertArrayEquals(sps + pps + idr, key.bytes)
        val whole = AnnexB.picture(sps + pps + filler + idr + filler, sets)!!
        assertArrayEquals(sps + pps + idr, whole.bytes)
        assertTrue(whole.keyframe)
    }

    @Test
    fun aDelimiterStaysFirstAndSetsThatCameHalfOrLateAreReplaced() {
        val sent = AnnexB.picture(delimiter + idr, sets)!!
        assertEquals(listOf(9, 7, 8, 5), types(sent.bytes))
        assertArrayEquals(delimiter + sps + pps + idr, sent.bytes)

        // Only one of the two, or behind the picture: the viewer wants SPS, PPS, picture, in that order.
        assertArrayEquals(sps + pps + idr, AnnexB.picture(sps + idr, sets)!!.bytes)
        assertArrayEquals(sps + pps + idr, AnnexB.picture(idr + sps + pps, sets)!!.bytes)
        assertArrayEquals(sps + pps + idr, AnnexB.picture(pps + sps + idr, sets)!!.bytes)
    }

    @Test
    fun aKeyframeWithNoParameterSetsToGiveItCannotBeSent() {
        assertNull(AnnexB.picture(idr, null))
        // One that brought its own can.
        assertTrue(AnnexB.picture(sps + pps + idr, null)!!.keyframe)
        // And an ordinary picture needs none.
        assertFalse(AnnexB.picture(slice, null)!!.keyframe)
    }

    @Test
    fun parameterSetsAreTakenOutOfWhatTheEncoderGivesFirst() {
        assertArrayEquals(sets, AnnexB.parameterSets(sps + pps))
        // In either order, with anything else beside them dropped.
        assertArrayEquals(sets, AnnexB.parameterSets(pps + long(0x06, 0x05) + sps))
        assertNull(AnnexB.parameterSets(sps))
        assertNull(AnnexB.parameterSets(pps))
        assertNull(AnnexB.parameterSets(ByteArray(0)))
    }
}
