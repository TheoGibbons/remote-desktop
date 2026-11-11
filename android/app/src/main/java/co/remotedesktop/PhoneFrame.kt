package co.remotedesktop

/**
 * Frame type 5: one picture of a phone's screen behind a header that says
 * which stream it belongs to, where it comes in that stream, how big it is
 * and when it was captured (PROTOCOL.md, "Phone frames"). The Windows app
 * reads and writes the same bytes in PhoneFrame.cs, and both are held to
 * [VECTOR].
 *
 * ```
 * [stream u64][seq u32][flags u8][width u16][height u16][captureMs u32][picture bytes]
 * ```
 *
 * Integers are big-endian. Nothing here touches Android.
 */
internal object PhoneFrame {
    const val HEADER_SIZE = 21

    /** `flags` bits 1 and 2: what the picture bytes are. */
    const val JPEG = 0
    const val H264 = 1

    private const val KEYFRAME = 0x01
    private const val FORMAT_SHIFT = 1
    private const val FORMAT_MASK = 0x03
    private const val SIDEWAYS = 0x08
    private const val REPEAT = 0x10

    /**
     * @param stream the stream's id, all 64 bits of it.
     * @param seq 0 for a stream's first frame and one more for each after it (unsigned 32 bits).
     * @param keyframe a picture that needs no earlier one. Every JPEG is.
     * @param format [JPEG] or [H264]; 2 and 3 are not used yet.
     * @param sideways the picture is the screen turned 90° clockwise. Not used yet.
     * @param repeat the same screen as an earlier frame, sent again.
     * @param captureMs the low 32 bits of the phone's uptime clock when the screen was captured.
     */
    data class Header(
        val stream: Long,
        val seq: Long,
        val keyframe: Boolean,
        val format: Int,
        val sideways: Boolean = false,
        val repeat: Boolean = false,
        val width: Int,
        val height: Int,
        val captureMs: Long,
    )

    /** The frame as it is encrypted: the header, then the picture. */
    fun build(header: Header, picture: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + picture.size)
        put(out, 0, 8, header.stream)
        put(out, 8, 4, header.seq)
        out[12] = ((if (header.keyframe) KEYFRAME else 0) or
            ((header.format and FORMAT_MASK) shl FORMAT_SHIFT) or
            (if (header.sideways) SIDEWAYS else 0) or
            (if (header.repeat) REPEAT else 0)).toByte()
        put(out, 13, 2, header.width.toLong())
        put(out, 15, 2, header.height.toLong())
        put(out, 17, 4, header.captureMs)
        picture.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Reads the header that starts at [offset], or null if [frame] is too short to hold one. */
    fun parse(frame: ByteArray, offset: Int = 0): Header? {
        if (offset < 0 || frame.size - offset < HEADER_SIZE) return null
        val flags = frame[offset + 12].toInt()
        return Header(
            stream = get(frame, offset, 8),
            seq = get(frame, offset + 8, 4),
            keyframe = flags and KEYFRAME != 0,
            format = (flags shr FORMAT_SHIFT) and FORMAT_MASK,
            sideways = flags and SIDEWAYS != 0,
            repeat = flags and REPEAT != 0,
            width = get(frame, offset + 13, 2).toInt(),
            height = get(frame, offset + 15, 2).toInt(),
            captureMs = get(frame, offset + 17, 4),
        )
    }

    private fun put(out: ByteArray, at: Int, bytes: Int, value: Long) {
        for (i in 0 until bytes) out[at + i] = (value ushr (8 * (bytes - 1 - i))).toByte()
    }

    private fun get(from: ByteArray, at: Int, bytes: Int): Long {
        var value = 0L
        for (i in 0 until bytes) value = (value shl 8) or (from[at + i].toLong() and 0xFF)
        return value
    }

    /** A stream's id as `screen-info` and `frame-ack` carry it: 16 hex digits. */
    fun streamHex(stream: Long): String = "%016x".format(stream)

    /** Reads those 16 hex digits, or null for anything else. */
    fun parseStream(text: String?): Long? {
        if (text == null || text.length != 16 || text.any { Character.digit(it, 16) < 0 }) return null
        return java.lang.Long.parseUnsignedLong(text, 16)
    }

    /** How far behind the clock a capture time may be and still be believed. */
    const val CAPTURE_TIME_SLACK_MS = 10_000L

    /**
     * When a picture was captured, for `captureMs`. Android stamps each
     * picture, and the stamp is taken if it is on the uptime clock: at or a
     * little before [nowMs]. A stamp from some other clock would make every
     * delay figure nonsense, so the time the picture reached the app is used
     * instead.
     */
    fun captureTime(reportedMs: Long, nowMs: Long): Long =
        if (reportedMs in (nowMs - CAPTURE_TIME_SLACK_MS)..nowMs) reportedMs else nowMs

    /**
     * A whole frame, as hex, that both apps build and read back in their
     * tests: stream `fedcba9876543210`, seq 4026531841, a JPEG keyframe,
     * 2340 x 1080, captured at 4294967280, and four bytes of picture. The
     * values have their top bits set, which is where two languages disagree.
     */
    const val VECTOR = "fedcba9876543210f00000010109240438fffffff0ffd8ffd9"

    /** The same header with every flag set and the format H.264, over no picture. */
    const val VECTOR_FLAGS = "fedcba9876543210f00000011b0438096000000000"

    /** Checks this code against the vectors PhoneFrame.cs is checked against. */
    fun selfTest(): Boolean {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val header = Header(stream = parseStream("fedcba9876543210") ?: return false, seq = 4026531841, keyframe = true,
            format = JPEG, width = 2340, height = 1080, captureMs = 4294967280)
        val picture = bytes("ffd8ffd9")
        if (hex(build(header, picture)) != VECTOR) return false
        if (parse(bytes(VECTOR)) != header) return false
        if (streamHex(header.stream) != "fedcba9876543210") return false

        val flags = header.copy(format = H264, sideways = true, repeat = true, width = 1080, height = 2400, captureMs = 0)
        if (hex(build(flags, ByteArray(0))) != VECTOR_FLAGS) return false
        if (parse(bytes(VECTOR_FLAGS)) != flags) return false
        // Too short to hold a header.
        return parse(bytes(VECTOR_FLAGS).copyOf(HEADER_SIZE - 1)) == null
    }
}
