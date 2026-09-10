package co.remotedesktop

import android.os.SystemClock

/** Thread-safe counters written by the WebSocket/decode thread and sampled by UI. */
internal class ViewerStreamStats {
    data class Snapshot(
        val sampledAtMs: Long,
        val frames: Long,
        val streamBytes: Long,
        val rects: Long,
        val dirtyPixels: Long,
        val decodeNanos: Long,
        val keyframes: Long,
        val sequenceGaps: Long,
        val decodeErrors: Long,
        val surfaceWidth: Int,
        val surfaceHeight: Int,
        val surfacePixels: Long,
        val lastFrameAgoMs: Long?,
    )

    private var frames = 0L
    private var streamBytes = 0L
    private var rects = 0L
    private var dirtyPixels = 0L
    private var decodeNanos = 0L
    private var keyframes = 0L
    private var sequenceGaps = 0L
    private var decodeErrors = 0L
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfacePixels = 0L
    private var lastFrameAtMs = 0L

    @Synchronized
    fun recordFrame(
        bytes: Int,
        rectCount: Int,
        changedPixels: Long,
        decodeTimeNanos: Long,
        isKeyframe: Boolean,
        hadSequenceGap: Boolean,
        width: Int,
        height: Int,
    ) {
        frames++
        streamBytes += bytes
        rects += rectCount
        dirtyPixels += changedPixels
        decodeNanos += decodeTimeNanos
        if (isKeyframe) keyframes++
        if (hadSequenceGap) sequenceGaps++
        surfaceWidth = width
        surfaceHeight = height
        surfacePixels = width.toLong() * height.toLong()
        lastFrameAtMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun recordDecodeError() {
        decodeErrors++
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val now = SystemClock.elapsedRealtime()
        return Snapshot(
            sampledAtMs = now,
            frames = frames,
            streamBytes = streamBytes,
            rects = rects,
            dirtyPixels = dirtyPixels,
            decodeNanos = decodeNanos,
            keyframes = keyframes,
            sequenceGaps = sequenceGaps,
            decodeErrors = decodeErrors,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            surfacePixels = surfacePixels,
            lastFrameAgoMs = if (lastFrameAtMs > 0) now - lastFrameAtMs else null,
        )
    }
}
