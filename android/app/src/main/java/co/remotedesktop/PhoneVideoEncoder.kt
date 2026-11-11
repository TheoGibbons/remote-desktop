package co.remotedesktop

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface

/**
 * One H.264 encoder for this phone's screen, with the OpenGL step in front
 * of it ([VideoGl]):
 *
 * ```
 * screen ─ virtual display ─► texture ─ draw() ─► encoder ─► Events.encoded
 * ```
 *
 * It decides nothing. [PhoneVideo] chooses the encoder, the size and the
 * moment of every picture; this sets the encoder up as PHONE-VIDEO-PLAN.md's
 * appendix A lists and passes on what comes out. Whatever goes wrong is
 * thrown from the constructor or from [draw], or reported to
 * [VideoPipe.Events.failed].
 *
 * Made, drawn into and closed on one thread, the one [handler] runs on,
 * which is where the screen's pictures are announced too. What the encoder
 * produces is announced on a thread of its own.
 *
 * That second thread also keeps watch on every draw. An encoder that has
 * stopped taking pictures without saying so leaves the draw waiting on it
 * for good, and with it the thread that everything else of the video's runs
 * on. A draw that has not come back after [STUCK_MS] is reported as a
 * failure, and the encoder is released from the watching thread, which is
 * what lets the draw return.
 */
internal class PhoneVideoEncoder(
    attempt: VideoSetup.Attempt,
    sourceWidth: Int,
    sourceHeight: Int,
    handler: Handler,
    private val events: VideoPipe.Events,
) : VideoPipe {
    override val width = attempt.width
    override val height = attempt.height
    override val bitrate = attempt.bitrate

    private val output = HandlerThread("video-out").also { it.start() }
    private val outputHandler = Handler(output.looper)
    @Volatile private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var gl: VideoGl? = null
    @Volatile private var closed = false

    private val callback = object : MediaCodec.Callback() {
        // The encoder is fed through its surface, so it never asks for input.
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (closed) return
            val data: ByteArray
            try {
                val buffer = checkNotNull(codec.getOutputBuffer(index))
                data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                codec.releaseOutputBuffer(index, false)
            } catch (e: RuntimeException) {
                // Closed under it, or broken: the first is no news, the second is.
                if (!closed) events.failed(e)
                return
            }
            if (data.isEmpty()) return
            events.encoded(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (closed) return
            // The parameter sets again, for an encoder that gives them only here.
            val sps = format.getByteBuffer("csd-0") ?: return
            val pps = format.getByteBuffer("csd-1") ?: return
            val sets = ByteArray(sps.remaining() + pps.remaining())
            sps.duplicate().get(sets, 0, sps.remaining())
            pps.duplicate().get(sets, sps.remaining(), pps.remaining())
            events.encoded(sets, 0, config = true)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!closed) events.failed(e)
        }
    }

    /** Runs on the encoder's thread when a draw has not come back. */
    private val stuck = Runnable {
        if (closed) return@Runnable
        events.failed(IllegalStateException("The encoder stopped taking pictures"))
        try {
            codec?.release()
        } catch (_: RuntimeException) {
            // It is being let go of on the other thread at this moment.
        }
    }

    /** What the screen is to be mirrored into. */
    val surface: Surface get() = checkNotNull(gl).surface

    init {
        try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    if (attempt.constantBitrate) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, VideoSetup.FPS)
                // In effect never: keyframes are made when one is asked for.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 600)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // What the viewer converts by, and so what the stream has to say of itself.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            val made = MediaCodec.createByCodecName(attempt.encoder)
            codec = made
            made.setCallback(callback, outputHandler)
            made.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val into = made.createInputSurface()
            input = into
            made.start()
            gl = VideoGl(into, width, height, sourceWidth, sourceHeight, handler,
                onPicture = { ns ->
                    val now = SystemClock.uptimeMillis()
                    if (!closed) events.picture(PhoneFrame.captureTime(ns / 1_000_000, now))
                },
                onFailure = { if (!closed) events.failed(it) })
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun draw(ptsUs: Long, keyframe: Boolean) {
        check(!closed) { "Closed" }
        if (keyframe) checkNotNull(codec).setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        })
        outputHandler.postDelayed(stuck, STUCK_MS)
        try {
            checkNotNull(gl).draw(ptsUs * 1000)
        } finally {
            outputHandler.removeCallbacks(stuck)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // OpenGL first: it draws into the encoder's input.
        gl?.release()
        gl = null
        codec?.let {
            try { it.stop() } catch (_: RuntimeException) {}
            try { it.release() } catch (_: RuntimeException) {}
        }
        codec = null
        try { input?.release() } catch (_: RuntimeException) {}
        input = null
        output.quitSafely()
    }

    companion object {
        private const val MIME = "video/avc"

        /** A draw takes a millisecond or two. One that takes this long is not coming back. */
        private const val STUCK_MS = 2_000L

        /** This phone's H.264 encoders, for [VideoSetup.attempts] to choose from. */
        fun encoders(): List<VideoSetup.Encoder> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }.mapNotNull { info ->
                val capabilities = try {
                    info.supportedTypes.firstOrNull { it.equals(MIME, ignoreCase = true) }
                        ?.let { info.getCapabilitiesForType(it) }
                } catch (_: RuntimeException) {
                    null
                } ?: return@mapNotNull null
                val video = capabilities.videoCapabilities ?: return@mapNotNull null
                VideoSetup.Encoder(
                    name = info.name,
                    hardware = isHardware(info),
                    constantBitrate = capabilities.encoderCapabilities
                        ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true,
                    takes = { width, height -> video.areSizeAndRateSupported(width, height, VideoSetup.FPS.toDouble()) })
            }

        private fun isHardware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
            val name = info.name.lowercase()
            return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw."))
        }
    }
}
