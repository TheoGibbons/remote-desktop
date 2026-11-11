package co.remotedesktop

import co.remotedesktop.PhoneVideoStream.Verdict
import org.junit.Assert.*
import org.junit.Test

/**
 * Type 5 frames: the header's bytes, which the Windows app must agree on, and
 * what a viewer makes of the frames that arrive. Plain JVM.
 */
class PhoneFrameTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun header(stream: Long = 7, seq: Long = 0, format: Int = PhoneFrame.JPEG) =
        PhoneFrame.Header(stream, seq, keyframe = true, format = format, width = 1080, height = 2340, captureMs = 5)

    @Test
    fun theHeaderIsTheBytesTheWindowsAppReadsAndWrites() {
        // The same vectors are in PhoneFrame.cs, checked by RemoteDesktopWin.PhoneViewTests.
        assertTrue(PhoneFrame.selfTest())
        // And encrypted on channel 5, the same blob comes out of all three implementations.
        assertTrue(Crypto.selfTest())

        val read = PhoneFrame.parse(bytes(PhoneFrame.VECTOR))!!
        assertEquals("fedcba9876543210", PhoneFrame.streamHex(read.stream))
        assertEquals(4026531841, read.seq)
        assertTrue(read.keyframe)
        assertEquals(PhoneFrame.JPEG, read.format)
        assertFalse(read.sideways)
        assertFalse(read.repeat)
        // A phone on its side: the long side is the width, and it fits.
        assertEquals(2340 to 1080, read.width to read.height)
        assertEquals(4294967280, read.captureMs)
        assertEquals(PhoneFrame.HEADER_SIZE + 4, bytes(PhoneFrame.VECTOR).size)
    }

    @Test
    fun everyFieldSurvivesTheTripAtItsLargestAndSmallest() {
        val picture = byteArrayOf(1, 2, 3)
        for (header in listOf(
            PhoneFrame.Header(-1, 0xFFFFFFFFL, keyframe = true, format = 3, sideways = true, repeat = true,
                width = 65535, height = 65535, captureMs = 0xFFFFFFFFL),
            PhoneFrame.Header(0, 0, keyframe = false, format = PhoneFrame.JPEG, width = 0, height = 0, captureMs = 0),
            PhoneFrame.Header(Long.MIN_VALUE, 0x80000000L, keyframe = false, format = PhoneFrame.H264, sideways = true,
                width = 2400, height = 1080, captureMs = 0x80000000L),
        )) {
            val frame = PhoneFrame.build(header, picture)
            assertEquals(header, PhoneFrame.parse(frame))
            assertArrayEquals(picture, frame.copyOfRange(PhoneFrame.HEADER_SIZE, frame.size))
            // Behind the frame-type byte, as it arrives.
            assertEquals(header, PhoneFrame.parse(byteArrayOf(5) + frame, 1))
        }
        assertNull(PhoneFrame.parse(ByteArray(PhoneFrame.HEADER_SIZE - 1)))
        assertNull(PhoneFrame.parse(ByteArray(PhoneFrame.HEADER_SIZE), 1))
        assertNotNull(PhoneFrame.parse(ByteArray(PhoneFrame.HEADER_SIZE)))
    }

    @Test
    fun aStreamIdIsSixteenHexDigitsAndNothingElse() {
        assertEquals("0000000000000001", PhoneFrame.streamHex(1))
        assertEquals("ffffffffffffffff", PhoneFrame.streamHex(-1))
        assertEquals(-1L, PhoneFrame.parseStream("ffffffffffffffff"))
        assertEquals(-1L, PhoneFrame.parseStream("FFFFFFFFFFFFFFFF"))
        assertEquals(0x0123456789abcdefL, PhoneFrame.parseStream("0123456789abcdef"))
        for (bad in listOf(null, "", "123", "0123456789abcdef0", "0123456789abcdeg", "+123456789abcdef", "-123456789abcdef",
            " 123456789abcdef")) assertNull(bad, PhoneFrame.parseStream(bad))
    }

    @Test
    fun aCaptureTimeFromAnotherClockIsNotBelieved() {
        // Just before now, on the same clock: taken.
        assertEquals(99_950L, PhoneFrame.captureTime(99_950, 100_000))
        assertEquals(100_000L, PhoneFrame.captureTime(100_000, 100_000))
        // Ahead of the clock, or far behind it: the stamp is on some other clock.
        assertEquals(100_000L, PhoneFrame.captureTime(100_001, 100_000))
        assertEquals(100_000L, PhoneFrame.captureTime(0, 100_000))
        assertEquals(100_000L, PhoneFrame.captureTime(100_000 - PhoneFrame.CAPTURE_TIME_SLACK_MS - 1, 100_000))
    }

    // ---------------- the viewer's side ----------------

    private val id = "00000000000000aa"

    @Test
    fun framesThatFollowTheirAnnouncementAreDrawnAndNoOthers() {
        val stream = PhoneVideoStream()
        // A phone on an older app announces nothing: its type 1 frames are drawn.
        assertTrue(stream.drawsType1)
        assertEquals(Verdict.REFUSE, stream.take(header(stream = 0xaa)))

        stream.announce(id, paused = false)
        assertEquals(0xaaL, stream.current)
        assertFalse("Type 1 is refused while a stream is announced", stream.drawsType1)
        assertEquals(Verdict.DRAW, stream.take(header(stream = 0xaa, seq = 0)))
        assertEquals(Verdict.REFUSE, stream.take(header(stream = 0xab, seq = 1)))
        assertEquals(Verdict.DRAW, stream.take(header(stream = 0xaa, seq = 1)))
        // H.264 was not offered. It arrived, and is not drawn.
        assertEquals(Verdict.SKIP, stream.take(header(stream = 0xaa, seq = 2, format = PhoneFrame.H264)))
        assertEquals(0, stream.gaps)
    }

    @Test
    fun aMissingNumberIsCountedAndARepeatedOneRefused() {
        val stream = PhoneVideoStream()
        stream.announce(id, paused = false)
        // A viewer that joins a stream part-way starts counting where it came in.
        assertEquals(Verdict.DRAW, stream.take(header(stream = 0xaa, seq = 40)))
        assertEquals(0, stream.gaps)
        assertEquals(Verdict.DRAW, stream.take(header(stream = 0xaa, seq = 43)))
        assertEquals(2, stream.gaps)
        // A frame played back, whoever by.
        assertEquals(Verdict.REFUSE, stream.take(header(stream = 0xaa, seq = 43)))
        assertEquals(Verdict.REFUSE, stream.take(header(stream = 0xaa, seq = 41)))
        assertEquals(2, stream.gaps)

        // The same stream announced again, as it is with a new size: the count goes on.
        stream.announce(id, paused = false)
        assertEquals(Verdict.REFUSE, stream.take(header(stream = 0xaa, seq = 43)))
        assertEquals(Verdict.DRAW, stream.take(header(stream = 0xaa, seq = 44)))

        // A new stream starts from nothing.
        stream.announce("00000000000000ab", paused = false)
        assertEquals(Verdict.REFUSE, stream.take(header(stream = 0xaa, seq = 45)))
        assertEquals(Verdict.DRAW, stream.take(header(stream = 0xab, seq = 0)))
    }

    @Test
    fun aStreamThatEndsLetsType1BackInAndAPauseLetsNothingIn() {
        val stream = PhoneVideoStream()
        stream.announce(id, paused = false)
        // Another viewer needs type 1, so everyone gets it.
        stream.announce(null, paused = false)
        assertTrue(stream.drawsType1)
        assertEquals(Verdict.REFUSE, stream.take(header(stream = 0xaa, seq = 5)))

        // Nothing is being sent, e.g. while a device waits to be approved:
        // whatever arrives now is not the phone's.
        stream.announce(id, paused = false)
        stream.announce("", paused = true)
        assertFalse(stream.drawsType1)
        assertNull(stream.current)
        assertEquals(Verdict.REFUSE, stream.take(header(stream = 0xaa, seq = 6)))

        // An id that is not one is no stream. Paused with an id means the id.
        stream.announce("xyz", paused = false)
        assertTrue(stream.drawsType1)
        stream.announce(id, paused = true)
        assertEquals(Verdict.DRAW, stream.take(header(stream = 0xaa, seq = 7)))

        stream.forget()
        assertTrue(stream.drawsType1)
        assertNull(stream.current)
    }

    @Test
    fun acknowledgementsAreThirtyMillisecondsApartAndTheLastOneAlwaysGoes() {
        val acks = AckPacer()
        assertEquals(AckPacer.Ack(0xaa, 0), acks.received(0xaa, 0, nowMs = 1000))
        assertNull(acks.dueIn(1000))
        // Two more inside 30 ms: neither goes now, and the higher one is owed.
        assertNull(acks.received(0xaa, 1, nowMs = 1010))
        assertNull(acks.received(0xaa, 2, nowMs = 1020))
        assertEquals(10L, acks.dueIn(1020))
        assertNull(acks.due(1029))
        assertEquals(AckPacer.Ack(0xaa, 2), acks.due(1030))
        assertNull(acks.due(1100))
        assertNull(acks.dueIn(1100))
        // Counted from the one that went, not from the frame.
        assertNull(acks.received(0xaa, 3, nowMs = 1059))
        assertEquals(AckPacer.Ack(0xaa, 3), acks.due(1060))
        assertEquals(AckPacer.Ack(0xaa, 4), acks.received(0xaa, 4, nowMs = 1090))

        assertNull(acks.received(0xaa, 5, nowMs = 1091))
        acks.forget()
        assertNull(acks.due(5000))
        assertEquals(AckPacer.Ack(0xab, 0), acks.received(0xab, 0, nowMs = 5000))
    }
}
