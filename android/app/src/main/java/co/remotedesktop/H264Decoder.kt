package co.remotedesktop

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface

/**
 * Decodes the host's H.264 stream (binary frame type 4) straight onto a
 * [Surface], so frames go from the hardware decoder to the display without
 * ever becoming a Bitmap. That is the whole point of the codec path: the JPEG
 * tile path costs a full-frame decode and a canvas blit per frame, which is
 * where the viewer's 8 ms/frame was going.
 *
 * Fed whole Annex B access units. SPS and PPS arrive in-band on every keyframe,
 * which AVC decoders configure themselves from, so no out-of-band csd is set up
 * here — it also means a viewer can join or recover mid-stream on any frame the
 * host flags as a keyframe.
 *
 * Not thread-safe, and deliberately so: it is only ever touched from the single
 * network thread that delivers binary frames.
 */
class H264Decoder(surface: Surface, val width: Int, val height: Int) {

    private val codec = MediaCodec.createDecoderByType(MIME)
    private val info = MediaCodec.BufferInfo()
    private var started = false

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Tells the decoder not to buffer frames looking for reordering
            // opportunities. The host encodes without B-frames, so there is
            // nothing to reorder and the latency is pure loss.
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        codec.configure(format, surface, null, 0)
        codec.start()
        started = true
    }

    /**
     * Queue one access unit and render whatever comes out. Returns true if a
     * frame reached the surface. A decoder legitimately produces nothing for
     * the first unit or two while it configures itself from the in-band SPS.
     */
    fun decode(accessUnit: ByteArray, presentationUs: Long, keyframe: Boolean): Boolean {
        if (!started) return false
        try {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                if (buffer != null) {
                    buffer.clear()
                    buffer.put(accessUnit)
                    val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    codec.queueInputBuffer(index, 0, accessUnit.size, presentationUs, flags)
                }
            }

            var rendered = false
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                when {
                    out >= 0 -> {
                        // true = hand it to the surface rather than discard it.
                        codec.releaseOutputBuffer(out, true)
                        rendered = true
                    }
                    // Format and buffer changes need no action when decoding to
                    // a surface; the surface follows the stream.
                    else -> return rendered
                }
            }
        } catch (e: IllegalStateException) {
            // The codec has gone (device lost, app backgrounded mid-frame).
            started = false
            return false
        }
    }

    fun release() {
        started = false
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }

    private companion object {
        const val MIME = "video/avc"
        // Non-blocking-ish: the network thread should not stall behind a
        // decoder that is momentarily full.
        const val INPUT_TIMEOUT_US = 10_000L
    }
}
