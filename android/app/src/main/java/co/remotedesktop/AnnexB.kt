package co.remotedesktop

/**
 * H.264 as an encoder hands it over: NAL units one after another, each
 * behind a start code (`00 00 01`, or `00 00 00 01`). This reads only what
 * the phone needs to know to send a picture the way PROTOCOL.md says
 * ("Phone frames"): whether it is a keyframe, whether its parameter sets are
 * with it, and whether the encoder padded it.
 *
 * Nothing here touches Android.
 */
internal object AnnexB {
    const val IDR = 5
    const val SPS = 7
    const val PPS = 8
    const val DELIMITER = 9
    const val FILLER = 12

    /** One NAL unit inside a buffer: its type, and where it lies, start code included. */
    class Nal(val type: Int, val from: Int, val until: Int)

    /** One picture as it is sent, and whether a viewer can start from it. */
    class Picture(val bytes: ByteArray, val keyframe: Boolean)

    /** The NAL units in [data], in order. Bytes before the first start code belong to none. */
    fun units(data: ByteArray): List<Nal> {
        val found = ArrayList<Nal>(8)
        var from = -1
        var type = 0
        var i = 0
        while (i + 2 < data.size) {
            if (data[i + 2].toInt() > 1) {
                // No start code can end here or at either of the next two bytes.
                i += 3
                continue
            }
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                // A zero just before it is the long form of the same start code.
                val start = if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i
                if (from >= 0) found += Nal(type, from, maxOf(start, from))
                from = start
                type = if (i + 3 < data.size) data[i + 3].toInt() and 0x1F else 0
                i += 3
            } else i++
        }
        if (from >= 0) found += Nal(type, from, data.size)
        return found
    }

    /**
     * The parameter sets (SPS and PPS) out of what an encoder gives before
     * its first picture, ready to go in front of a keyframe. Null when either
     * is missing.
     */
    fun parameterSets(config: ByteArray): ByteArray? {
        val sets = units(config).filter { it.type == SPS || it.type == PPS }.sortedBy { if (it.type == SPS) 0 else 1 }
        if (sets.none { it.type == SPS } || sets.none { it.type == PPS }) return null
        return join(config, sets)
    }

    /**
     * One encoded picture as a viewer is to get it:
     * - a keyframe has its parameter sets in front of it: [parameterSets] are
     *   put there unless the encoder already did, whether or not the keyframe
     *   was asked for;
     * - filler, which an encoder at a constant bitrate pads with, is left out:
     *   it is no part of the picture, and the Windows decoder holds a picture
     *   back while filler follows it.
     *
     * @return null when it is a keyframe and there are no parameter sets to
     * give it: no viewer could start from it, and none can go on past it.
     */
    fun picture(encoded: ByteArray, parameterSets: ByteArray?): Picture? {
        val units = units(encoded)
        val idr = units.indexOfFirst { it.type == IDR }
        val filler = units.any { it.type == FILLER }
        if (idr < 0) return Picture(if (filler) join(encoded, units.filter { it.type != FILLER }) else encoded, false)

        val sps = units.indexOfFirst { it.type == SPS }
        val pps = units.indexOfFirst { it.type == PPS }
        if (sps in 0 until pps && pps < idr) {
            return Picture(if (filler) join(encoded, units.filter { it.type != FILLER }) else encoded, true)
        }
        if (parameterSets == null) return null
        // The encoder's own sets, if it gave only one of them or gave them late, go: ours replace them.
        val kept = units.filter { it.type != FILLER && it.type != SPS && it.type != PPS }
        // A delimiter, where there is one, stays the first thing in the picture.
        val lead = kept.takeWhile { it.type == DELIMITER }
        val rest = kept.drop(lead.size)
        val out = ByteArray(lead.sumOf { it.until - it.from } + parameterSets.size + rest.sumOf { it.until - it.from })
        var at = 0
        for (nal in lead) at = copy(encoded, nal, out, at)
        parameterSets.copyInto(out, at)
        at += parameterSets.size
        for (nal in rest) at = copy(encoded, nal, out, at)
        return Picture(out, true)
    }

    private fun join(data: ByteArray, units: List<Nal>): ByteArray {
        val out = ByteArray(units.sumOf { it.until - it.from })
        var at = 0
        for (nal in units) at = copy(data, nal, out, at)
        return out
    }

    private fun copy(data: ByteArray, nal: Nal, out: ByteArray, at: Int): Int {
        data.copyInto(out, at, nal.from, nal.until)
        return at + (nal.until - nal.from)
    }
}
