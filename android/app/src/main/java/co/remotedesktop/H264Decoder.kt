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
     * Queue one access unit and render whatever comes out. Returns the
     * presentation timestamp of the frame that reached the surface, or -1 if
     * none did.
     *
     * The timestamp matters: decoders pipeline, so the frame that comes out
     * here is usually an *earlier* one than the unit just queued. The caller
     * needs to know which, because each frame covers a different part of the
     * desktop and drawing one at another's position puts it in the wrong
     * place until the next frame corrects it.
     */
    fun decode(accessUnit: ByteArray, presentationUs: Long, keyframe: Boolean): Long {
        if (!started) return -1L
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

            var renderedPts = -1L
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                when {
                    out >= 0 -> {
                        // The last one out is what ends up on the surface.
                        renderedPts = info.presentationTimeUs
                        // true = hand it to the surface rather than discard it.
                        codec.releaseOutputBuffer(out, true)
                    }
                    // Format and buffer changes need no action when decoding to
                    // a surface; the surface follows the stream.
                    else -> return renderedPts
                }
            }
        } catch (e: IllegalStateException) {
            // The codec has gone (device lost, app backgrounded mid-frame).
            started = false
            return -1L
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
